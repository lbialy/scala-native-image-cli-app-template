import caseapp.*
import cue4s.*

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
    Prompts.sync.use: prompts =>
      prompts
        .singleChoice("What's the weather like?", List("great", "okay", "bad"))
        .getOrThrow
        .match
          case "great" => println("That's awesome, take a bike ride!")
          case "okay"  => println("That's good, go for a walk!")
          case "bad"   => println("That's not good, stay home and read a book.")

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
