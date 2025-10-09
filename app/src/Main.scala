import caseapp.*

// Hello command
case class HelloOptions(
    who: String = "World",
    verbose: Boolean = false
)

object HelloCmd extends Command[HelloOptions]:
  override def name = "hello"
  def run(options: HelloOptions, remaining: RemainingArgs): Unit =
    if options.verbose then println(s"Hello, ${options.who}! (verbose mode)")
    else println(s"Hello, ${options.who}!")

// Version command
case class VersionOptions()

object VersionCmd extends Command[VersionOptions]:
  override def name = "version"
  def run(options: VersionOptions, remaining: RemainingArgs): Unit =
    println("MyApp v1.0.0")

// Main application entry point
object Main extends CommandsEntryPoint:
  def progName = "myapp"
  def commands = Seq(HelloCmd, VersionCmd)
  override def defaultCommand = Some(HelloCmd)
  override def enableCompleteCommand = true
  override def enableCompletionsCommand = true
