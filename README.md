# Scala 3 → GraalVM Native Template

This is a GitHub template repository for building Scala 3 CLI applications with GraalVM Native Image support.

## Quickstart

This template will provide:
- Complete Scala 3 CLI development environment with scala-cli
- Automated GraalVM Native Image compilation for 6 target platforms
- Multi-channel distribution (Homebrew, Scoop, Chocolatey, Linux packages, AUR)
- CI/CD workflows for testing and releases
- Native image agent orchestration for reflection metadata

## Usage

Once implemented, you'll be able to:
- `just run -- --help` - Run the CLI with help
- `just build` - Build the project
- `just test` - Run tests
- `just native` - Build native binary

## Documentation

- [SETUP.md](SETUP.md) - Configuration options and advanced setup
- [Technical Outline](context/outline.md) - Complete technical specification
- [Implementation Plan](context/plan.md) - Step-by-step implementation guide

## License

MIT License - see [LICENSE](LICENSE) for details.

