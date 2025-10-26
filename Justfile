# Justfile# Scripts written in Scala for cross-platform compatibility.

# Variables (read SETUP.md to learn more)
BINARY_NAME := "myapp"
NI_METADATA := "app/resources/META-INF/native-image"
AGENT_OUT := ".out/native-image-agent"
GRAALVM_ID := "graalvm-community:25.0.0"
GRAALVM_ARGS := "--no-fallback -H:+StaticExecutableWithDynamicLibC"

set unstable

set windows-shell := ["powershell.exe", "-NoLogo", "-Command"]

SCALA_CLI_BINARY_PATH := env_var_or_default("SCALA_CLI_BINARY_PATH", "scala-cli")

SCALA_SHEBANG := if os_family() == "windows" {
    "scala-cli.bat"
} else {
    "/usr/bin/env -S scala-cli shebang"
}

# Print help
default:
    @just --list --unsorted

# Print the selected GraalVM distribution coursier ID
graalvm-id:
    @echo {{GRAALVM_ID}}

# Print the name of the binary to be built
binary-name:
    @echo {{BINARY_NAME}}

# Compile the application
compile:
    @echo "Building {{BINARY_NAME}}..."
    scala-cli compile app

# Run tests
test:
    @echo "Running tests in {{BINARY_NAME}}..."
    scala-cli test app

# Run the application with optional arguments
run ARGS="":
    @echo "Running {{BINARY_NAME}} with args: {{ARGS}}"
    scala-cli --power run app -- {{ARGS}}

# Run with native-image-agent to generate metadata
[extension(".sc")]
agent-run ARGS="":
    #! {{SCALA_SHEBANG}}
    //> using toolkit default
    //> using scala 3.7.3
    //> using jvm {{GRAALVM_ID}}

    println("Running with native-image-agent to generate metadata...")
    os.makeDir.all(os.pwd / "{{AGENT_OUT}}")
    val runArgs = "{{ARGS}}".split(" ")
    val cmd = Seq(raw"{{SCALA_CLI_BINARY_PATH}}", "run", "--jvm", "{{GRAALVM_ID}}", "app",
        "--java-opt=-agentlib:native-image-agent=config-output-dir={{NI_METADATA}}",
        "--") ++ runArgs

    os.proc(cmd).call(stdout = os.Inherit, stderr = os.Inherit, stdin = os.Inherit)

    println("Stripping test dependencies from merged metadata...")
    val compileClassPath = os.proc("scala-cli", "compile", "-p", "app").call().out.text().trim
    val compileTestClassPath = os.proc("scala-cli", "compile", "-p", "app", "--test").call().out.text().trim
    os.proc(raw"{{SCALA_CLI_BINARY_PATH}}", "run",
        "--dep", "ma.chinespirit::filter-native-image-metadata:0.1.2",
        "-M", "filterNativeImageMetadata",
        "--",
        "{{NI_METADATA}}/reachability-metadata.json",
        compileClassPath,
        compileTestClassPath
    ).call(stdout = os.Inherit, stderr = os.Inherit)

# Run tests with native-image-agent to generate metadata
[extension(".sc")]
agent-test:
    #! {{SCALA_SHEBANG}}
    //> using toolkit default
    //> using scala 3.7.3
    //> using jvm {{GRAALVM_ID}}

    println("Running tests with native-image-agent to generate metadata...")
    os.makeDir.all(os.pwd / "{{AGENT_OUT}}")
    os.proc(raw"{{SCALA_CLI_BINARY_PATH}}", "test", "--jvm", "{{GRAALVM_ID}}", "app",
        "--java-opt=-agentlib:native-image-agent=config-output-dir={{NI_METADATA}}"
    ).call(stdout = os.Inherit, stderr = os.Inherit, stdin = os.Inherit)
    println("Stripping test dependencies from merged metadata...")
    val compileClassPath = os.proc(raw"{{SCALA_CLI_BINARY_PATH}}", "compile", "-p", "app").call().out.text().trim
    val compileTestClassPath = os.proc(raw"{{SCALA_CLI_BINARY_PATH}}", "compile", "-p", "app", "--test").call().out.text().trim
    val cmd = Seq(raw"{{SCALA_CLI_BINARY_PATH}}", "run", "--dep", "ma.chinespirit::filter-native-image-metadata:0.1.2", "-M", "filterNativeImageMetadata", "--", "{{NI_METADATA}}/reachability-metadata.json", compileClassPath, compileTestClassPath)
    os.proc(cmd).call(stdout = os.Inherit, stderr = os.Inherit)

# Build native image
[extension(".sc")]
native: agent-test
    #! {{SCALA_SHEBANG}}
    //> using toolkit default
    //> using scala 3.7.3
    //> using jvm {{GRAALVM_ID}}

    println(s"Building native image for {{os()}}/{{arch()}}...")
    val graalvmArgs = "{{GRAALVM_ARGS}}".split(" ")
    val flags = graalvmArgs.map(arg => s"--graalvm-args=$arg")

    os.proc(raw"{{SCALA_CLI_BINARY_PATH}}", "--power", "package", "app", "-f", "--native-image",
        s"--graalvm-jvm-id={{GRAALVM_ID}}",
        flags, "-o", "dist/{{BINARY_NAME}}"
    ).call(stdout = os.Inherit, stderr = os.Inherit)

# Generate checksums for dist/ files
[extension(".sc")]
checksums OS=os() ARCH=arch():
    #! {{SCALA_SHEBANG}}
    //> using toolkit default
    //> using scala 3.7.3
    //> using jvm {{GRAALVM_ID}}

    import java.security.MessageDigest
    import java.nio.file.Files

    println("Generating checksums...")
    os.makeDir.all(os.pwd / "dist")
    val distFiles = os.list(os.pwd / "dist").filter(os.isFile)
    
    def computeSHA256(file: os.Path): String = {
        val digest = MessageDigest.getInstance("SHA-256")
        val fileBytes = Files.readAllBytes(file.toNIO)
        val hashBytes = digest.digest(fileBytes)
        hashBytes.map(b => f"$b%02x").mkString
    }
    
    val checksumContent = distFiles.map { file =>
        val hash = computeSHA256(file)
        s"$hash  ${file.last}"
    }.mkString("\n")
    
    val checksumFileName = s"checksums-{{OS}}-{{ARCH}}.txt"
    os.write(os.pwd / "dist" / checksumFileName, checksumContent)
    println(s"Generated checksums file: $checksumFileName")

# Clean all build artifacts
[extension(".sc")]
clean: clean-agent
    #! {{SCALA_SHEBANG}}
    //> using toolkit default
    //> using scala 3.7.3
    //> using jvm {{GRAALVM_ID}}

    println("Cleaning build artifacts...")
    os.remove.all(os.pwd / "dist")
    os.proc(raw"{{SCALA_CLI_BINARY_PATH}}", "clean", "app").call(stdout = os.Inherit, stderr = os.Inherit)

# Clean agent output (preserve merged metadata)
[extension(".sc")]
clean-agent:
    #! {{SCALA_SHEBANG}}
    //> using toolkit default
    //> using scala 3.7.3
    //> using jvm {{GRAALVM_ID}}

    println("Cleaning agent output...")
    os.remove.all(os.pwd / "{{AGENT_OUT}}")
    os.list(os.pwd / "{{NI_METADATA}}").filter(_.last.startsWith("agent-pid")).foreach(os.remove.all)
    os.remove.all(os.pwd / "{{NI_METADATA}}" / ".lock")

# Clean native-image metadata
[extension(".sc")]
clean-meta:
    #! {{SCALA_SHEBANG}}
    //> using toolkit default
    //> using scala 3.7.3
    //> using jvm {{GRAALVM_ID}}

    println("Cleaning native-image metadata...")
    os.remove.all(os.pwd / "{{NI_METADATA}}")
    os.makeDir.all(os.pwd / "{{NI_METADATA}}")

# Set up development environment
dev-setup:
    @echo "Setting up development environment..."
    scala-cli setup-ide .
    @echo "Development setup complete."

# Display current native-image metadata
[extension(".sc")]
show-metadata:
    #! {{SCALA_SHEBANG}}
    //> using toolkit default
    //> using scala 3.7.3
    //> using jvm {{GRAALVM_ID}}

    println("Current native-image metadata:")
    val metadataDir = os.pwd / "{{NI_METADATA}}"
    if (os.exists(metadataDir)) {
        val jsonFiles = os.list(metadataDir).filter(_.ext == "json")
        jsonFiles.foreach { file =>
            println(s"=== $file ===")
            println(os.read(file))
            println()
        }
    } else {
        println("No metadata directory found")
    }