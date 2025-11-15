import munit.FunSuite

class MyAppTests extends E2ETestBase:

  test("hello command with --who Scala and stdin input selects great") {
    // Using PTY, stdin should work consistently across all platforms
    val result = runCliWithStdin(stdin = "\n")(
      "hello",
      "--who",
      "Scala"
    )

    assertCliSuccess(result)
    // Use assertStdoutContainsIgnoringAnsi to handle ANSI color codes
    assertStdoutContainsIgnoringAnsi(result, "Hello, Scala!")
    assertStdoutContainsIgnoringAnsi(result, "What's the weather like?")
    assertStdoutContainsIgnoringAnsi(result, "great")
    assertStdoutContainsIgnoringAnsi(result, "That's awesome, take a bike ride!")
  }

  test("hello command interactive test - wait for prompt then respond") {
    val session = startInteractiveCli("hello", "--who", "Scala")

    val greeting = session.readUntil("Hello, Scala!", timeoutMs = 5000, ignoreAnsi = true)
    assertStringContainsIgnoringAnsi(greeting, "Hello, Scala!")

    // Wait for the weather prompt
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
    assertStringContainsIgnoringAnsi(result.stdout, "That's not good, stay home and read a book.")
  }
