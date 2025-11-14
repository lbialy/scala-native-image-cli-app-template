import munit.FunSuite
import os.Path
import scala.util.{Try, Success, Failure}
import scala.util.boundary, boundary.break
import os.RelPath

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

  /** Synchronized buffer for thread-safe string accumulation.
    */
  private class SyncBuffer:
    private val buffer = StringBuilder()

    def append(str: String): Unit = synchronized:
      buffer.append(str)

    def current: String = synchronized:
      buffer.toString

    def clear(): Unit = synchronized:
      buffer.clear()

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
      private val subprocess: os.SubProcess,
      private val args: Seq[String]
  ):
    private val stdoutBuffer = SyncBuffer()
    private val stderrBuffer = SyncBuffer()
    private var stdinClosed = false
    private var processFinished = false

    // Start background threads to read stdout and stderr
    private val stdoutThread = new Thread:
      override def run(): Unit =
        try
          val reader = subprocess.stdout
          val buffer = new Array[Byte](8192)
          var bytesRead = reader.read(buffer)
          while bytesRead != -1 do
            if bytesRead > 0 then stdoutBuffer.append(String(buffer, 0, bytesRead))
            bytesRead = reader.read(buffer)
        catch case _: java.io.IOException => () // Stream closed, normal termination

    private val stderrThread = new Thread:
      override def run(): Unit =
        try
          val reader = subprocess.stderr
          val buffer = new Array[Byte](8192)
          var bytesRead = reader.read(buffer)
          while bytesRead != -1 do
            if bytesRead > 0 then stderrBuffer.append(String(buffer, 0, bytesRead))
            bytesRead = reader.read(buffer)
        catch case _: java.io.IOException => () // Stream closed, normal termination

    stdoutThread.start()
    stderrThread.start()

    /** Writes text to stdin. Can be called multiple times for interactive communication.
      */
    def write(text: String): Unit =
      if stdinClosed then throw IllegalStateException("Cannot write to stdin: it has already been closed")
      else if processFinished then throw IllegalStateException("Cannot write to stdin: process has already finished")
      else
        subprocess.stdin.write(text.getBytes)
        subprocess.stdin.flush()

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
      *   Optional timeout in milliseconds (default: 5000ms)
      * @param ignoreAnsi
      *   If true, strips ANSI codes before matching (default: true)
      */
    def readUntil(pattern: String, timeoutMs: Int = 5000, ignoreAnsi: Boolean = true): String =
      boundary[String]:
        val startTime = System.currentTimeMillis()
        while System.currentTimeMillis() - startTime < timeoutMs do
          val currentOutput = stdoutBuffer.current
          val textToMatch = if ignoreAnsi then stripAnsiCodes(currentOutput) else currentOutput
          if textToMatch.contains(pattern) then break(currentOutput)

          // Check if process finished
          if !subprocess.isAlive() then
            processFinished = true
            stdoutThread.join(1000) // Wait for reader thread to finish
            break(stdoutBuffer.current)

          Thread.sleep(50) // Small delay to avoid busy-waiting
        end while
        throw RuntimeException(
          s"Timeout waiting for pattern '$pattern' in stdout. Current output:\n${stdoutBuffer.current}"
        )

    /** Reads from stderr until the given pattern appears (blocking). Returns all stderr output read so far (including
      * the pattern).
      */
    def readStderrUntil(pattern: String, timeoutMs: Int = 5000, ignoreAnsi: Boolean = true): String =
      boundary[String]:
        val startTime = System.currentTimeMillis()
        while System.currentTimeMillis() - startTime < timeoutMs do
          val currentOutput = stderrBuffer.current
          val textToMatch = if ignoreAnsi then stripAnsiCodes(currentOutput) else currentOutput
          if textToMatch.contains(pattern) then break(currentOutput)

          if !subprocess.isAlive() then
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
    def isAlive: Boolean = subprocess.isAlive()

    /** Closes stdin and waits for the process to finish, returning the final result.
      */
    def close(): CLIResult =
      if !stdinClosed then
        subprocess.stdin.close()
        stdinClosed = true

      subprocess.waitFor()
      val exitCode = subprocess.exitCode()
      processFinished = true

      // Wait for reader threads to finish
      stdoutThread.join(2000)
      stderrThread.join(2000)

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
    val proc = os.proc(cmd)
    val subprocess = proc.spawn(
      stdin = os.Pipe,
      stdout = os.Pipe,
      stderr = os.Pipe
    )
    InteractiveCLISession(subprocess, args)

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
  protected def runCliWithStdin(stdin: String = "")(args: String*): CLIResult =
    val binary = cliBinaryPath
    val cmd = buildCommand(binary, args)
    val proc = os.proc(cmd)

    if stdin.nonEmpty then
      // Use spawn for stdin input
      val subprocess = proc.spawn(
        stdin = os.Pipe,
        stdout = os.Pipe,
        stderr = os.Pipe
      )

      // Write stdin to the process
      val stdinBytes = stdin.getBytes
      try
        subprocess.stdin.write(stdinBytes)
        subprocess.stdin.flush()
        subprocess.stdin.close()
      catch
        case _: java.io.IOException => () // stdin already closed, ignore

        // Read stdout and stderr
      val stdoutBytes = subprocess.stdout.readAllBytes()
      val stderrBytes = subprocess.stderr.readAllBytes()

      // Wait for process to complete and get exit code
      subprocess.waitFor()
      val exitCode = subprocess.exitCode()

      CLIResult(
        stdout = String(stdoutBytes),
        stderr = String(stderrBytes),
        exitCode = exitCode
      )
    else
      // No stdin, just call normally
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
    */
  protected def stripAnsiCodes(text: String): String =
    // Remove ANSI escape sequences: \u001b[...m
    text.replaceAll("\u001b\\[[0-9;]*m", "")

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
