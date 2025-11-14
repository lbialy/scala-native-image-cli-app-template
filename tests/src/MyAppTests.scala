import munit.FunSuite

class MyAppTests extends CLITestBase:

  test("hello command with --who Scala and stdin input selects great") {
    // Windows may need \r\n for proper line ending handling
    val stdin = if System.getProperty("os.name").toLowerCase.contains("win") then "\r\n" else "\n"
    val result = runCliWithStdin(stdin = stdin)(
      "hello",
      "--who",
      "Scala"
    )

    assertCliSuccess(result)
    // Use assertStdoutContainsIgnoringAnsi to handle ANSI color codes
    assertStdoutContainsIgnoringAnsi(result, "Hello, Scala!")
    assertStdoutContainsIgnoringAnsi(result, "✔ What's the weather like?  … great")
    assertStdoutContainsIgnoringAnsi(result, "That's awesome, take a bike ride!")
  }

  test("hello command interactive test - wait for prompt then respond") {
    val session = startInteractiveCli("hello", "--who", "Scala")

    val greeting = session.readUntil("Hello, Scala!", timeoutMs = 10000, ignoreAnsi = true)
    assertStdoutContainsIgnoringAnsi(CLIResult(greeting, "", 0), "Hello, Scala!")

    // Wait for the weather prompt (use default timeout from CLI_TEST_TIMEOUT_MS or 5000ms)
    val prompt = session.readUntil("weather", ignoreAnsi = true)

    // Respond to the prompt
    session.sendDownArrow()
    session.sendDownArrow()
    session.sendEnter()

    // Wait for the response
    val response = session.readUntil("not good", ignoreAnsi = true)

    // Close and get final result
    val result = session.close()
    assertCliSuccess(result)
    assertStdoutContainsIgnoringAnsi(result, "That's not good, stay home and read a book.")
  }
