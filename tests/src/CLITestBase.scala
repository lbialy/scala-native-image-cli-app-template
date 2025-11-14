import munit.FunSuite
import os.Path
import scala.util.{Try, Success, Failure}
import scala.util.boundary, boundary.break
import os.RelPath
import com.pty4j.PtyProcessBuilder
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters.*

/** Base class for end-to-end blackbox tests against a compiled CLI artifact.
  *
  * The binary path is read from the CLI_BINARY_PATH environment variable. If not set, it defaults to "dist/myapp"
  * relative to the project root.
  *
  * Example usage:
  * {{{
  * class MyCLITests extends CLITestBase {
  *   test("hello command works") {
  *     val result = runCli("hello", "--who", "Alice")
  *     assertEquals(result.exitCode, 0)
  *     assert(result.stdout.contains("Hello, Alice"))
  *   }
  *
  *   test("version command works") {
  *     val result = runCli("version")
  *     assertEquals(result.exitCode, 0)
  *     assert(result.stdout.contains("v1.0.0"))
  *   }
  *
  *   test("interactive command with stdin") {
  *     val result = runCliWithStdin(stdin = "great\n")(
  *       "hello", "--verbose"
  *     )
  *     assertEquals(result.exitCode, 0)
  *   }
  * }
  * }}}
  */
abstract class CLITestBase extends FunSuite:

  // Log test configuration at startup
  override def beforeAll(): Unit =
    super.beforeAll()
    val debugMode = sys.env.get("CLI_TEST_DEBUG").getOrElse("not set")
    val timeoutMs = sys.env.get("CLI_TEST_TIMEOUT_MS").getOrElse("not set")
    val binaryPath = sys.env.get("CLI_BINARY_PATH").getOrElse("not set")
    val niAgent = sys.env.get("NI_AGENT").getOrElse("not set")
    println(s"[CLITestBase] Test configuration:")
    println(s"  CLI_TEST_DEBUG=$debugMode")
    println(s"  CLI_TEST_TIMEOUT_MS=$timeoutMs")
    println(s"  CLI_BINARY_PATH=$binaryPath")
    println(s"  NI_AGENT=$niAgent")
    println(s"  OS: ${System.getProperty("os.name")}")
    println(s"  Java: ${System.getProperty("java.version")}")

  // Override test to add logging
  override def test(name: String)(body: => Any)(implicit loc: munit.Location): Unit =
    super.test(name) {
      println(s"[TEST START] $name")
      val startTime = System.currentTimeMillis()
      try
        body
      finally
        val duration = System.currentTimeMillis() - startTime
        println(s"[TEST END] $name (${duration}ms)")
    }(loc)

  /** Synchronized buffer for thread-safe string accumulation with optional binary capture.
    */
  private class SyncBuffer:
    private val buffer = StringBuilder()
    private val binaryBuffer = scala.collection.mutable.ArrayBuffer[Byte]()

    def append(str: String): Unit = synchronized:
      buffer.append(str)

    def appendBytes(bytes: Array[Byte], offset: Int, length: Int): Unit = synchronized:
      binaryBuffer.appendAll(bytes.slice(offset, offset + length))

    def current: String = synchronized:
      buffer.toString

    def currentBytes: Array[Byte] = synchronized:
      binaryBuffer.toArray

    def clear(): Unit = synchronized:
      buffer.clear()
      binaryBuffer.clear()

  /** Result of a CLI execution containing stdout, stderr, and exit code.
    */
  case class CLIResult(
      stdout: String,
      stderr: String,
      exitCode: Int
  ):
    def isSuccess: Boolean = exitCode == 0
    def isFailure: Boolean = exitCode != 0

  /** Interactive CLI session for back-and-forth communication with stdin/stdout.
    *
    * Example usage:
    * {{{
    * val session = startInteractiveCli("hello", "--who", "Scala")
    * val output1 = session.readUntil("weather")
    * session.write("great\n")
    * val output2 = session.readUntil("awesome")
    * val result = session.close()
    * }}}
    */
  class InteractiveCLISession private[CLITestBase] (
      private val process: Process,
      private val args: Seq[String]
  ):
    private val stdoutBuffer = SyncBuffer()
    private val stderrBuffer = SyncBuffer()
    private var stdinClosed = false
    private var processFinished = false
    private val debugMode = sys.env.get("CLI_TEST_DEBUG").exists(v => v.toLowerCase == "true" || v == "1")
    private val defaultTimeoutMs = sys.env.get("CLI_TEST_TIMEOUT_MS").flatMap(_.toIntOption).getOrElse(5000)

    // Start background threads to read stdout and stderr
    private val stdoutThread = new Thread:
      override def run(): Unit =
        try
          val reader = process.getInputStream()
          val buffer = new Array[Byte](8192)
          var bytesRead = reader.read(buffer)
          while bytesRead != -1 do
            if bytesRead > 0 then
              stdoutBuffer.appendBytes(buffer, 0, bytesRead)
              stdoutBuffer.append(String(buffer, 0, bytesRead, StandardCharsets.UTF_8))
            bytesRead = reader.read(buffer)
        catch case _: java.io.IOException => () // Stream closed, normal termination

    private val stderrThread = new Thread:
      override def run(): Unit =
        try
          val reader = process.getErrorStream()
          val buffer = new Array[Byte](8192)
          var bytesRead = reader.read(buffer)
          while bytesRead != -1 do
            if bytesRead > 0 then
              stderrBuffer.appendBytes(buffer, 0, bytesRead)
              stderrBuffer.append(String(buffer, 0, bytesRead, StandardCharsets.UTF_8))
            bytesRead = reader.read(buffer)
        catch case _: java.io.IOException => () // Stream closed, normal termination

    stdoutThread.start()
    stderrThread.start()

    /** Escapes special characters for display in debug output.
      */
    private def escapeForDisplay(text: String): String =
      text
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
        .replace("\u001b", "\\u001b")

    /** Prints a debug snapshot of current stdout/stderr state.
      */
    private def printDebugSnapshot(label: String): Unit =
      val stdout = stdoutBuffer.current
      val stderr = stderrBuffer.current
      println(s"\n========== DEBUG: $label ==========")
      println(s"STDOUT (${stdout.length} chars):\n$stdout")
      if stderr.nonEmpty then
        println(s"STDERR (${stderr.length} chars):\n$stderr")
      println(s"Process alive: ${process.isAlive()}")
      println("=" * 50 + "\n")

    /** Writes text to stdin. Can be called multiple times for interactive communication.
      */
    def write(text: String): Unit =
      if stdinClosed then throw IllegalStateException("Cannot write to stdin: it has already been closed")
      else if processFinished then throw IllegalStateException("Cannot write to stdin: process has already finished")
      else
        val stdin = process.getOutputStream()
        stdin.write(text.getBytes(StandardCharsets.UTF_8))
        stdin.flush()
        if debugMode then
          Thread.sleep(100) // Give time for output to arrive
          printDebugSnapshot(s"After writing:\n${escapeForDisplay(text)}")

    /** Writes a line to stdin (adds newline automatically).
      */
    def writeLine(line: String): Unit =
      write(line + "\n")

    /** Writes a single character to stdin. Useful for testing raw mode terminals that respond to individual keypresses.
      */
    def writeChar(char: Char): Unit =
      write(char.toString)

    /** Sends the Enter/Return key (newline character).
      */
    def sendEnter(): Unit =
      write("\n")

    /** Sends the Escape key (ESC character).
      */
    def sendEscape(): Unit =
      write("\u001b")

    /** Sends the Tab key.
      */
    def sendTab(): Unit =
      write("\t")

    /** Sends the Backspace key.
      */
    def sendBackspace(): Unit =
      write("\u0008")

    /** Sends the Delete key.
      */
    def sendDelete(): Unit =
      write("\u007f")

    /** Sends the Up Arrow key (ANSI escape sequence).
      */
    def sendUpArrow(): Unit =
      write("\u001b[A")

    /** Sends the Down Arrow key (ANSI escape sequence).
      */
    def sendDownArrow(): Unit =
      write("\u001b[B")

    /** Sends the Right Arrow key (ANSI escape sequence).
      */
    def sendRightArrow(): Unit =
      write("\u001b[C")

    /** Sends the Left Arrow key (ANSI escape sequence).
      */
    def sendLeftArrow(): Unit =
      write("\u001b[D")

    /** Sends a control character (e.g., Ctrl+C, Ctrl+D).
      *
      * @param char
      *   The character to send as a control character (e.g., 'c' for Ctrl+C, 'd' for Ctrl+D)
      */
    def sendControl(char: Char): Unit =
      val controlCode = (char.toLower - 'a' + 1).toChar
      write(controlCode.toString)

    /** Sends Ctrl+C (interrupt signal).
      */
    def sendCtrlC(): Unit =
      sendControl('c')

    /** Sends Ctrl+D (end-of-file signal).
      */
    def sendCtrlD(): Unit =
      sendControl('d')

    /** Sends a space character.
      */
    def sendSpace(): Unit =
      write(" ")

    /** Gets all stdout output captured so far.
      */
    def readAvailable(): String =
      stdoutBuffer.current

    /** Gets all stderr output captured so far.
      */
    def readStderrAvailable(): String =
      stderrBuffer.current

    /** Reads from stdout until the given pattern appears (blocking). Returns all output read so far (including the
      * pattern).
      *
      * @param pattern
      *   The substring to wait for
      * @param timeoutMs
      *   Optional timeout in milliseconds (default: value from CLI_TEST_TIMEOUT_MS env var or 5000ms)
      * @param ignoreAnsi
      *   If true, strips ANSI codes before matching (default: true)
      */
    def readUntil(pattern: String, timeoutMs: Int = -1, ignoreAnsi: Boolean = true): String =
      val actualTimeout = if timeoutMs == -1 then defaultTimeoutMs else timeoutMs
      if debugMode then
        printDebugSnapshot(s"Before readUntil:\n'$pattern' (timeout: ${actualTimeout}ms)")
      boundary[String]:
        val startTime = System.currentTimeMillis()
        while System.currentTimeMillis() - startTime < actualTimeout do
          val currentOutput = stdoutBuffer.current
          val textToMatch = if ignoreAnsi then stripAnsiCodes(currentOutput) else currentOutput
          if textToMatch.contains(pattern) then
            if debugMode then
              printDebugSnapshot(s"After readUntil found:\n'$pattern'")
            break(currentOutput)

          // Check if process finished
          if !process.isAlive() then
            processFinished = true
            stdoutThread.join(1000) // Wait for reader thread to finish
            if debugMode then
              printDebugSnapshot(s"Process finished while waiting for:\n'$pattern'")
            break(stdoutBuffer.current)

          Thread.sleep(50) // Small delay to avoid busy-waiting
        end while
        if debugMode then
          printDebugSnapshot(s"Timeout waiting for:\n'$pattern'")
        throw RuntimeException(
          s"Timeout waiting for pattern '$pattern' in stdout. Current output:\n${stdoutBuffer.current}"
        )

    /** Reads from stderr until the given pattern appears (blocking). Returns all stderr output read so far (including
      * the pattern).
      */
    def readStderrUntil(pattern: String, timeoutMs: Int = -1, ignoreAnsi: Boolean = true): String =
      val actualTimeout = if timeoutMs == -1 then defaultTimeoutMs else timeoutMs
      boundary[String]:
        val startTime = System.currentTimeMillis()
        while System.currentTimeMillis() - startTime < actualTimeout do
          val currentOutput = stderrBuffer.current
          val textToMatch = if ignoreAnsi then stripAnsiCodes(currentOutput) else currentOutput
          if textToMatch.contains(pattern) then break(currentOutput)

          if !process.isAlive() then
            processFinished = true
            stderrThread.join(1000) // Wait for reader thread to finish
            break(stderrBuffer.current)

          Thread.sleep(50)
        end while
        throw RuntimeException(
          s"Timeout waiting for pattern '$pattern' in stderr. Current output:\n${stderrBuffer.current}"
        )

    /** Gets all stdout output read so far.
      */
    def stdoutSoFar: String =
      stdoutBuffer.current

    /** Gets all stderr output read so far.
      */
    def stderrSoFar: String =
      stderrBuffer.current

    /** Checks if the process is still running.
      */
    def isAlive: Boolean = process.isAlive()

    /** Closes stdin and waits for the process to finish, returning the final result.
      *
      * @param waitTimeoutMs Maximum time to wait for process to finish (default: 30000ms / 30 seconds)
      */
    def close(waitTimeoutMs: Long = 30000): CLIResult =
      if debugMode then
        printDebugSnapshot(s"Closing session (timeout: ${waitTimeoutMs}ms)")

      if !stdinClosed then
        process.getOutputStream().close()
        stdinClosed = true

      // Wait for process with timeout
      val startTime = System.currentTimeMillis()
      while process.isAlive() && (System.currentTimeMillis() - startTime < waitTimeoutMs) do
        Thread.sleep(100)

      if process.isAlive() then
        process.destroy()
        Thread.sleep(1000)
        if process.isAlive() then
          process.destroyForcibly()

      val exitCode = if process.isAlive() then -1 else process.exitValue()
      processFinished = true

      // Wait for reader threads to finish
      stdoutThread.join(2000)
      stderrThread.join(2000)

      if debugMode then
        printDebugSnapshot(s"Session closed with exit code: $exitCode")

        // Write binary output to files for debugging
        try
          val outFile = os.pwd / "test-interactive-stdout.bin"
          val errFile = os.pwd / "test-interactive-stderr.bin"
          os.write.over(outFile, stdoutBuffer.currentBytes)
          os.write.over(errFile, stderrBuffer.currentBytes)
          println(s"[InteractiveCLISession] Binary output written to: ${outFile} and ${errFile}")
        catch
          case e: Exception =>
            println(s"[InteractiveCLISession] Failed to write binary files: ${e.getMessage}")

      CLIResult(
        stdout = stdoutBuffer.current,
        stderr = stderrBuffer.current,
        exitCode = exitCode
      )

  /** Gets the path to the CLI binary. Reads from CLI_BINARY_PATH environment variable, or defaults to "dist/myapp".
    */
  protected def cliBinaryPath: Path =
    val envPath = sys.env.get("CLI_BINARY_PATH")
    envPath match
      case Some(path) =>
        val p =
          if path.startsWith("/") then Path(path)
          else os.pwd / RelPath(path)
        if os.isFile(p) then p
        else
          throw IllegalArgumentException(
            s"CLI_BINARY_PATH environment variable points to non-existent file: $path"
          )
      case None =>
        // Default to dist/myapp relative to project root
        // Try to find project root by looking for Justfile or app/project.scala
        val projectRoot = findProjectRoot()
        val defaultPath = projectRoot / "dist" / "myapp"
        if os.isFile(defaultPath) then defaultPath
        else
          throw IllegalArgumentException(
            s"CLI binary not found at default path: $defaultPath. " +
              s"Please set CLI_BINARY_PATH environment variable or build the binary first."
          )
    end match
  /** Finds the project root by looking for Justfile or app/project.scala
    */
  private def findProjectRoot(): Path = boundary[Path]:
    var current = os.pwd
    while current != current / ".." do
      if os.isFile(current / "Justfile") || os.isFile(current / "app" / "project.scala") then break(current)

      current = current / ".."
    end while
    // Fallback to current working directory
    os.pwd

  /** Builds the command to execute, wrapping with native-image-agent if NI_AGENT env var is true.
    *
    * @param binary
    *   Path to the CLI binary
    * @param args
    *   Command line arguments
    * @return
    *   Sequence of command parts to execute
    */
  private def buildCommand(binary: Path, args: Seq[String]): Seq[String] =
    val useAgent = sys.env.get("NI_AGENT").exists(v => v.toLowerCase == "true" || v == "1")
    val niAgentMode = sys.env.get("NI_AGENT_MODE").getOrElse("merge")
    if useAgent then
      val projectRoot = findProjectRoot()
      val configOutputDir = projectRoot / "app" / "resources" / "META-INF" / "native-image"
      Seq(
        "java",
        s"-agentlib:native-image-agent=config-$niAgentMode-dir=$configOutputDir",
        "-jar",
        binary.toString
      ) ++ args
    else Seq(binary.toString) ++ args

  /** Starts an interactive CLI session for back-and-forth communication.
    *
    * @param args
    *   Command line arguments to pass to the CLI
    * @return
    *   InteractiveCLISession that allows writing to stdin and reading from stdout incrementally
    *
    * Example:
    * {{{
    * val session = startInteractiveCli("hello", "--who", "Scala")
    * val prompt = session.readUntil("weather")
    * session.writeLine("great")
    * val response = session.readUntil("awesome")
    * val result = session.close()
    * }}}
    */
  protected def startInteractiveCli(args: String*): InteractiveCLISession =
    val binary = cliBinaryPath
    val cmd = buildCommand(binary, args)

    // Build environment map with TERM set if not present
    val env = new java.util.HashMap[String, String](System.getenv())
    if !env.containsKey("TERM") then env.put("TERM", "xterm-256color")

    // Create PTY process for proper terminal interaction
    val builder = new PtyProcessBuilder()
      .setCommand(cmd.toArray)
      .setEnvironment(env)
      .setDirectory(os.pwd.toString)

    // Enable Windows-specific ANSI support
    val isWindows = System.getProperty("os.name").toLowerCase.contains("win")
    if isWindows then
      builder.setWindowsAnsiColorEnabled(true)
      builder.setUseWinConPty(true)

    val process = builder.start()

    InteractiveCLISession(process, args)

  /** Runs the CLI with the given arguments and returns the result.
    *
    * @param args
    *   Command line arguments to pass to the CLI
    * @return
    *   CLIResult containing stdout, stderr, and exit code
    */
  protected def runCli(args: String*): CLIResult =
    runCliWithStdin(stdin = "")(args*)

  /** Runs the CLI with the given arguments and stdin input.
    *
    * @param stdin
    *   Input to write to stdin
    * @param args
    *   Command line arguments to pass to the CLI
    * @return
    *   CLIResult containing stdout, stderr, and exit code
    */
  protected def runCliWithStdin(stdin: String = "", timeoutMs: Long = 30000)(args: String*): CLIResult =
    val debugMode = sys.env.get("CLI_TEST_DEBUG").exists(v => v.toLowerCase == "true" || v == "1")
    val binary = cliBinaryPath
    val cmd = buildCommand(binary, args)

    if debugMode then
      println(s"[runCliWithStdin] Running command: ${cmd.mkString(" ")}")
      println(s"[runCliWithStdin] Stdin: ${stdin.replace("\n", "\\n").replace("\r", "\\r")}")
      println(s"[runCliWithStdin] Timeout: ${timeoutMs}ms")

    if stdin.nonEmpty then
      // Build environment map with TERM set if not present
      val env = new java.util.HashMap[String, String](System.getenv())
      if !env.containsKey("TERM") then env.put("TERM", "xterm-256color")

      // Use PTY for stdin input to ensure proper terminal handling on all platforms
      val process = try
        val builder = new PtyProcessBuilder()
          .setCommand(cmd.toArray)
          .setEnvironment(env)
          .setDirectory(os.pwd.toString)

        // Enable Windows-specific ANSI support
        val isWindows = System.getProperty("os.name").toLowerCase.contains("win")
        if isWindows then
          builder.setWindowsAnsiColorEnabled(true)
          builder.setUseWinConPty(true)

        builder.start()
      catch
        case e: Exception =>
          if debugMode then
            println(s"[runCliWithStdin] Failed to start PTY process: ${e.getMessage}")
            e.printStackTrace()
          throw e

      if debugMode then
        println(s"[runCliWithStdin] PTY process spawned")

      // Read stdout and stderr in separate threads to avoid blocking
      // Start these BEFORE writing stdin to avoid race conditions
      val stdoutBuffer = SyncBuffer()
      val stderrBuffer = SyncBuffer()

      val stdoutThread = new Thread:
        override def run(): Unit =
          try
            val reader = process.getInputStream()
            val buffer = new Array[Byte](4096)
            var bytesRead = reader.read(buffer)
            while bytesRead != -1 do
              if bytesRead > 0 then
                stdoutBuffer.appendBytes(buffer, 0, bytesRead)
                stdoutBuffer.append(String(buffer, 0, bytesRead, StandardCharsets.UTF_8))
              bytesRead = reader.read(buffer)
          catch case _: java.io.IOException => ()

      val stderrThread = new Thread:
        override def run(): Unit =
          try
            val reader = process.getErrorStream()
            val buffer = new Array[Byte](4096)
            var bytesRead = reader.read(buffer)
            while bytesRead != -1 do
              if bytesRead > 0 then
                stderrBuffer.appendBytes(buffer, 0, bytesRead)
                stderrBuffer.append(String(buffer, 0, bytesRead, StandardCharsets.UTF_8))
              bytesRead = reader.read(buffer)
          catch case _: java.io.IOException => ()

      stdoutThread.start()
      stderrThread.start()

      // Give threads a moment to start reading before we write stdin
      Thread.sleep(50)

      // Write stdin to the process (but DON'T close it yet for PTY processes)
      val stdinBytes = stdin.getBytes(StandardCharsets.UTF_8)
      val processStdin = process.getOutputStream()
      try
        processStdin.write(stdinBytes)
        processStdin.flush()
        if debugMode then
          println(s"[runCliWithStdin] Stdin written, keeping stream open until process completes")
      catch
        case _: java.io.IOException => () // stdin already closed, ignore

      // Wait for process to complete with timeout
      val startTime = System.currentTimeMillis()
      while process.isAlive() && (System.currentTimeMillis() - startTime < timeoutMs) do
        Thread.sleep(100)

      // Now close stdin after process completes or times out
      try
        processStdin.close()
      catch
        case _: java.io.IOException => ()

      val exitCode = if process.isAlive() then
        // Process didn't finish in time, kill it
        if debugMode then
          println(s"[runCliWithStdin] Process timeout after ${timeoutMs}ms, killing process")
          println(s"[runCliWithStdin] Stdout so far: ${stdoutBuffer.current}")
          println(s"[runCliWithStdin] Stderr so far: ${stderrBuffer.current}")
        process.destroy()
        Thread.sleep(1000)
        if process.isAlive() then process.destroyForcibly()
        -1
      else
        process.exitValue()

      // Wait for reader threads to finish
      stdoutThread.join(2000)
      stderrThread.join(2000)

      if debugMode then
        println(s"[runCliWithStdin] Process finished with exit code: $exitCode")
        println(s"[runCliWithStdin] Final stdout (${stdoutBuffer.current.length} chars): ${stdoutBuffer.current}")
        println(s"[runCliWithStdin] Final stderr (${stderrBuffer.current.length} chars): ${stderrBuffer.current}")

        // Write binary output to files for debugging
        try
          val outFile = os.pwd / "test-stdout.bin"
          val errFile = os.pwd / "test-stderr.bin"
          val outBytes = stdoutBuffer.currentBytes
          val errBytes = stderrBuffer.currentBytes
          os.write.over(outFile, outBytes)
          os.write.over(errFile, errBytes)
          println(s"[runCliWithStdin] Binary output written to: ${outFile} and ${errFile}")

          // Print first 256 bytes as hex for immediate debugging
          if outBytes.nonEmpty then
            val hexDump = outBytes.take(256).map(b => f"${b & 0xff}%02x").mkString(" ")
            println(s"[runCliWithStdin] First ${math.min(256, outBytes.length)} bytes (hex):\n$hexDump")
        catch
          case e: Exception =>
            println(s"[runCliWithStdin] Failed to write binary files: ${e.getMessage}")

      CLIResult(
        stdout = stdoutBuffer.current,
        stderr = stderrBuffer.current,
        exitCode = exitCode
      )
    else
      // No stdin, use os.proc for simplicity
      val proc = os.proc(cmd)
      val result = proc.call(
        stdout = os.Pipe,
        stderr = os.Pipe,
        check = false
      )

      CLIResult(
        stdout = result.out.text(),
        stderr = result.err.text(),
        exitCode = result.exitCode
      )

  /** Runs the CLI with the given arguments and returns the result as a Try. Useful for testing error cases.
    *
    * @param args
    *   Command line arguments to pass to the CLI
    * @return
    *   Try[CLIResult] - Success if exit code is 0, Failure otherwise
    */
  protected def runCliTry(args: String*): Try[CLIResult] =
    val result = runCli(args*)
    if result.isSuccess then Success(result)
    else
      Failure(
        RuntimeException(
          s"CLI exited with code ${result.exitCode}\n" +
            s"STDOUT: ${result.stdout}\n" +
            s"STDERR: ${result.stderr}"
        )
      )

  /** Asserts that the CLI execution was successful (exit code 0).
    *
    * @param result
    *   The CLIResult to check
    * @param clue
    *   Optional message to include in assertion failure
    */
  protected def assertCliSuccess(result: CLIResult, clue: String = ""): Unit =
    if !result.isSuccess then
      val message = if clue.nonEmpty then s"$clue\n" else ""
      fail(
        s"${message}Expected CLI to succeed, but it exited with code ${result.exitCode}\n" +
          s"STDOUT:\n${result.stdout}\n" +
          s"STDERR:\n${result.stderr}"
      )

  /** Asserts that the CLI execution failed (non-zero exit code).
    *
    * @param result
    *   The CLIResult to check
    * @param expectedExitCode
    *   Optional expected exit code
    * @param clue
    *   Optional message to include in assertion failure
    */
  protected def assertCliFailure(
      result: CLIResult,
      expectedExitCode: Option[Int] = None,
      clue: String = ""
  ): Unit =
    if result.isSuccess then
      val message = if clue.nonEmpty then s"$clue\n" else ""
      fail(s"${message}Expected CLI to fail, but it succeeded with exit code 0")

    expectedExitCode.foreach: expected =>
      assertEquals(result.exitCode, expected, s"Expected exit code $expected, but got ${result.exitCode}")

  /** Asserts that stdout contains the given substring.
    *
    * @param result
    *   The CLIResult to check
    * @param substring
    *   The substring to search for
    * @param clue
    *   Optional message to include in assertion failure
    */
  protected def assertStdoutContains(result: CLIResult, substring: String, clue: String = ""): Unit =
    if !result.stdout.contains(substring) then
      val message = if clue.nonEmpty then s"$clue\n" else ""
      fail(
        s"${message}Expected stdout to contain '$substring', but it didn't.\n" +
          s"STDOUT:\n${result.stdout}"
      )

  /** Asserts that stderr contains the given substring.
    *
    * @param result
    *   The CLIResult to check
    * @param substring
    *   The substring to search for
    * @param clue
    *   Optional message to include in assertion failure
    */
  protected def assertStderrContains(result: CLIResult, substring: String, clue: String = ""): Unit =
    if !result.stderr.contains(substring) then
      val message = if clue.nonEmpty then s"$clue\n" else ""
      fail(
        s"${message}Expected stderr to contain '$substring', but it didn't.\n" +
          s"STDERR:\n${result.stderr}"
      )

  /** Strips ANSI escape codes from a string. ANSI codes are sequences like \u001b[32m (green), \u001b[36m (cyan),
    * \u001b[0m (reset), etc.
    *
    * On Windows PTY without proper ANSI support, the escape character (0x1B) can be replaced with
    * UTF-8 sequence e2 86 90 (leftwards arrow ←, U+2190) at the byte level.
    */
  protected def stripAnsiCodes(text: String): String =
    var result = text

    // First pass: Remove standard ANSI escape sequences with ESC (0x1B)
    result = result.replaceAll("\u001b\\[[0-9;?]*[A-Za-z]", "")
    result = result.replaceAll("\u001b\\[[0-9;?]*m", "")

    // Second pass: Remove Windows PTY corrupted sequences where ESC (0x1B) becomes U+2190 (←)
    // Hex dump shows: 1b 5b 33 32 6d becomes e2 86 90 5b 33 32 6d (← [ 3 2 m)
    result = result.replaceAll("←\\[[0-9;?]*[A-Za-z]", "")
    result = result.replaceAll("←\\[[0-9;?]*m", "")

    // Third pass: Remove Windows-corrupted sequences where \u001b becomes ?
    result = result.replaceAll("\\?\\[[0-9;?]*[A-Za-z]", "")
    result = result.replaceAll("\\?\\[[0-9;?]*m", "")

    // Fourth pass: Remove partial sequences that got mangled
    // Pattern like "25l", "25h", "0G", "2K", "1B", "4A", etc - cursor movement codes
    result = result.replaceAll("\\d+[lhGKABCDHfJms]", "")

    // Fifth pass: Clean up remaining artifacts
    result = result.replaceAll("\\?\\[\\?", "")
    result = result.replaceAll("\\?\\[", "")
    result = result.replace("←", "") // Remove standalone leftwards arrows

    // Sixth pass: Remove any remaining isolated control characters
    // Unicode control characters (0x00-0x1F except newline, tab, carriage return)
    result = result.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "")

    // Seventh pass: Clean up special Unicode characters
    result = result.replace("�", "") // Unicode replacement character

    result

  /** Asserts that stdout contains the given substring, ignoring ANSI color codes.
    *
    * @param result
    *   The CLIResult to check
    * @param substring
    *   The substring to search for (will be matched against text with ANSI codes stripped)
    * @param clue
    *   Optional message to include in assertion failure
    */
  protected def assertStdoutContainsIgnoringAnsi(result: CLIResult, substring: String, clue: String = ""): Unit =
    val strippedStdout = stripAnsiCodes(result.stdout)
    if !strippedStdout.contains(substring) then
      val message = if clue.nonEmpty then s"$clue\n" else ""
      fail(
        s"${message}Expected stdout to contain '$substring' (ignoring ANSI codes), but it didn't.\n" +
          s"STDOUT (with ANSI codes):\n${result.stdout}\n" +
          s"STDOUT (stripped):\n$strippedStdout"
      )

  /** Asserts that stderr contains the given substring, ignoring ANSI color codes.
    *
    * @param result
    *   The CLIResult to check
    * @param substring
    *   The substring to search for (will be matched against text with ANSI codes stripped)
    * @param clue
    *   Optional message to include in assertion failure
    */
  protected def assertStderrContainsIgnoringAnsi(result: CLIResult, substring: String, clue: String = ""): Unit =
    val strippedStderr = stripAnsiCodes(result.stderr)
    if !strippedStderr.contains(substring) then
      val message = if clue.nonEmpty then s"$clue\n" else ""
      fail(
        s"${message}Expected stderr to contain '$substring' (ignoring ANSI codes), but it didn't.\n" +
          s"STDERR (with ANSI codes):\n${result.stderr}\n" +
          s"STDERR (stripped):\n$strippedStderr"
      )
