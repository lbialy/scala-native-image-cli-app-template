# Scala 3 → GraalVM Native Template — Implementation Plan

This document outlines the step-by-step implementation plan for creating a GitHub template repository that enables rapid development and distribution of Scala 3 CLI applications with GraalVM Native Image support.

## Overview

The template will provide:
- Complete Scala 3 CLI development environment with scala-cli
- Automated GraalVM Native Image compilation for 6 target platforms
- Multi-channel distribution (Homebrew, Scoop, Chocolatey, Linux packages, AUR)
- CI/CD workflows for testing and releases
- Native image agent orchestration for reflection metadata

---

## Phase 1: Core Repository Structure

### 1.1 Directory Layout Setup
**Priority: High | Estimated Time: 2-3 hours**

Create the complete directory structure as specified in the outline:

```
.
├─ app/                              # Scala-CLI project (single-module)
│  ├─ src/
│  │  └─ Main.scala
│  ├─ test/
│  │  └─ MainSpec.scala
│  ├─ resources/
│  │  └─ META-INF/native-image/      # merged agent output
│  └─ project.scala                  # scala-cli project config
├─ packaging/
│  ├─ brew/
│  │  └─ Formula.rb.tmpl
│  ├─ scoop/
│  │  └─ manifest.json.tmpl
│  ├─ choco/
│  ├─ nfpm/
│  └─ aur/
├─ scripts/
│  ├─ merge-native-image-meta.sh
│  ├─ strip-test-deps.jar
│  └─ ci-env.sh
├─ .github/
│  └─ workflows/
│     ├─ ci.yml
│     └─ release.yml
├─ Justfile
├─ README.md
├─ SETUP.md
├─ LICENSE
└─ .gitignore
```

**Tasks:**
- [ ] Create all directories
- [ ] Initialize empty files with proper structure
- [ ] Set up `.gitignore` with appropriate exclusions
- [ ] Create MIT LICENSE file

### 1.2 Example CLI Application
**Priority: High | Estimated Time: 3-4 hours**

Implement a minimal but functional CLI using case-app:

**Tasks:**
- [ ] Create `app/src/Main.scala` with case-app integration
- [ ] Implement basic CLI structure with help, version, and example commands
- [ ] Add shell completion support (bash, zsh, fish, powershell) (read https://alexarchambault.github.io/case-app/completion/)
- [ ] Create `app/test/MainSpec.scala` with basic tests
- [ ] Configure `app/project.scala` with pinned Scala version
- [ ] Add dependencies to `app/build.sc` if needed

**Example CLI structure:**
```scala
// Main.scala - Basic CLI with case-app
import caseapp.*

case class Options(
  who: String = "World"
)

object HelloCmd extends Command[Options]:
  def run(options: Options, remaining: RemainingArgs): Unit = 
    println(s"Hello, ${options.who}!")

object MyApp extends CommandsEntryPoint:
  def progName = "my-app"
  def commands = Seq()
  override def defaultCommand = Some(HelloCmd)
  override def enableCompleteCommand = true
  override def enableCompletionsCommand = true
```

---

## Phase 2: Build System & Task Orchestration

### 2.1 Justfile Implementation
**Priority: High | Estimated Time: 4-5 hours**

Create the authoritative task runner with all specified targets:

**Tasks:**
- [ ] Implement basic build/test/run targets
- [ ] Add agent-assisted runs (agent-run, agent-test)
- [ ] Implement native build target with configurable options
- [ ] Add checksums generation
- [ ] Create completions target for shell scripts
- [ ] Add variables for project name, paths, etc.

**Key targets to implement:**
```just
# Variables
BINARY_NAME := myapp
MAIN_DIR := app
RES_META := app/resources/META-INF/native-image
AGENT_OUT := .out/native-image-agent

# Core targets
build, test, run, native, checksums
# Agent targets  
agent-run, agent-test
# Utility targets
completions
```

### 2.2 Native Image Agent Scripts
**Priority: High | Estimated Time: 6-8 hours**

Implement the agent orchestration system:

**Tasks:**
- [ ] Create `scripts/merge-native-image-meta.sh`
  - Merge agent JSON outputs into resources
  - Implement JSON deduplication logic
  - Handle additive merging (never delete existing metadata)
- [ ] Create `scripts/strip-test-deps.jar`
  - Implement classloader diffing logic
  - Strip test dependencies from metadata
  - Handle reflection, resource, and config metadata
- [ ] Create `scripts/ci-env.sh`
  - Common CI environment setup
  - Path and naming helpers
  - Cross-platform compatibility

**Agent workflow:**
1. Run app/tests with native-image-agent
2. Merge outputs into `resources/META-INF/native-image`
3. Strip test dependencies from merged metadata
4. Preserve existing metadata (additive only)

---

## Phase 3: CI/CD Workflows

### 3.1 CI Workflow (ci.yml)
**Priority: High | Estimated Time: 4-5 hours**

Implement the continuous integration workflow:

**Tasks:**
- [ ] Set up matrix strategy for core 4 targets:
  - linux/amd64, linux/arm64, macos/arm64, windows/amd64
- [ ] Add optional targets (macos/amd64, windows/arm64) with input guards
- [ ] Implement caching for Coursier and scala-cli
- [ ] Add timeout configuration (60 minutes per job)
- [ ] Pin all actions by commit SHA
- [ ] Configure permissions (least privilege)

**Matrix configuration:**
```yaml
strategy:
  matrix:
    include:
      - os: ubuntu-24.04
        os_tag: linux
        arch: x86_64
      - os: ubuntu-24.04-arm
        os_tag: linux
        arch: aarch64
      - os: macos-14
        os_tag: macos
        arch: aarch64
      - os: windows-2022
        os_tag: windows
        arch: x86_64
```

### 3.2 Release Workflow (release.yml)
**Priority: High | Estimated Time: 5-6 hours**

Implement the tag-driven release workflow:

**Tasks:**
- [ ] Set up tag-based triggers (`v*` tags)
- [ ] Add workflow_dispatch with inputs:
  - version override
  - enable_optional_targets
  - scala_cli_version, graal_version, jdk_dist
- [ ] Implement native binary building per target
- [ ] Add artifact naming and checksums
- [ ] Configure GitHub Release creation/updates
- [ ] Add draft vs published release logic

**Release artifacts:**
- `{app}-{os}-{arch}` (with .exe on Windows)
- Individual `.sha256` files
- Combined `checksums.txt`

---

## Phase 4: Distribution Channels

### 4.1 Homebrew Tap
**Priority: Medium | Estimated Time: 3-4 hours**

**Tasks:**
- [ ] Create `packaging/brew/Formula.rb.tmpl`
- [ ] Implement formula generation logic
- [ ] Add tap repository management
- [ ] Configure bot token integration

### 4.2 Scoop Bucket (Default Windows)
**Priority: High | Estimated Time: 3-4 hours**

**Tasks:**
- [ ] Create `packaging/scoop/manifest.json.tmpl`
- [ ] Implement manifest generation
- [ ] Add bucket repository management
- [ ] Configure automated PR creation

### 4.3 Optional Channels
**Priority: Low | Estimated Time: 6-8 hours**

**Tasks:**
- [ ] Chocolatey: nuspec + PowerShell scripts
- [ ] Linux packages: nfpm configuration
- [ ] Arch AUR: PKGBUILD template
- [ ] Version-bump automation for all channels

---

## Phase 5: Documentation & Configuration

### 5.1 README.md
**Priority: High | Estimated Time: 2-3 hours**

**Tasks:**
- [ ] Quickstart guide
- [ ] Usage examples (`just run -- --help`)
- [ ] Release process documentation
- [ ] Installation instructions for each channel
- [ ] Links to SETUP.md for advanced configuration

### 5.2 SETUP.md
**Priority: High | Estimated Time: 4-5 hours**

**Tasks:**
- [ ] GraalVM/JDK distribution selection
- [ ] Native Image options documentation:
  - glibc vs musl
  - GC settings
  - CPU tuning (`-march`)
  - Initialize-at-build-time options
- [ ] Matrix target configuration
- [ ] Binary name overrides
- [ ] Channel setup instructions
- [ ] Required secrets documentation
- [ ] Native image agent workflow guide

### 5.3 Example Configuration
**Priority: Medium | Estimated Time: 2-3 hours**

**Tasks:**
- [ ] Provide example `project.scala`
- [ ] Example native-image options
- [ ] Sample CI environment variables
- [ ] Example distribution channel configurations

---

## Phase 6: Testing & Validation

### 6.1 Local Testing
**Priority: High | Estimated Time: 3-4 hours**

**Tasks:**
- [ ] Test all Justfile targets locally
- [ ] Validate agent workflow
- [ ] Test native compilation
- [ ] Verify shell completions
- [ ] Test on multiple platforms if possible

### 6.2 CI Testing
**Priority: High | Estimated Time: 2-3 hours**

**Tasks:**
- [ ] Test CI workflows on PR
- [ ] Validate matrix builds
- [ ] Test caching behavior
- [ ] Verify artifact generation

### 6.3 Release Testing
**Priority: Medium | Estimated Time: 2-3 hours**

**Tasks:**
- [ ] Test release workflow with test tags
- [ ] Validate artifact naming
- [ ] Test GitHub Release creation
- [ ] Verify distribution channel integration

---

## Phase 7: Advanced Features & Polish

### 7.1 Security Enhancements
**Priority: Medium | Estimated Time: 3-4 hours**

**Tasks:**
- [ ] Implement code signing options (macOS/Windows)
- [ ] Add notarization support for macOS
- [ ] Configure Authenticode for Windows
- [ ] Document security best practices

### 7.2 Performance Optimizations
**Priority: Low | Estimated Time: 2-3 hours**

**Tasks:**
- [ ] Optimize native-image compilation flags
- [ ] Add build performance monitoring
- [ ] Implement parallel builds where possible
- [ ] Cache optimization

### 7.3 Future Work Preparation
**Priority: Low | Estimated Time: 2-3 hours**

**Tasks:**
- [ ] Add stub for native smoke tests
- [ ] Document SBOM strategy
- [ ] Prepare for self-hosted runner support
- [ ] Add extensibility points for advanced users

---

## Implementation Timeline

### Week 1: Foundation
- Phase 1: Core Repository Structure
- Phase 2.1: Justfile Implementation
- Basic CLI application

### Week 2: Build System
- Phase 2.2: Native Image Agent Scripts
- Phase 3.1: CI Workflow
- Testing and validation

### Week 3: Distribution
- Phase 3.2: Release Workflow
- Phase 4.1-4.2: Homebrew and Scoop
- Phase 5: Documentation

### Week 4: Polish & Testing
- Phase 4.3: Optional Channels
- Phase 6: Testing & Validation
- Phase 7: Advanced Features

---

## Success Criteria

### Must Have (v1.0)
- [ ] Complete repository structure
- [ ] Working Justfile with all core targets
- [ ] Functional CLI example with case-app
- [ ] Native image agent orchestration
- [ ] CI workflow for 4 core targets
- [ ] Release workflow with GitHub Releases
- [ ] Homebrew Tap and Scoop integration
- [ ] Comprehensive documentation

### Should Have (v1.1)
- [ ] Optional distribution channels (Chocolatey, Linux packages, AUR)
- [ ] Advanced native-image configuration options
- [ ] Code signing support
- [ ] Performance optimizations

### Could Have (Future)
- [ ] Native smoke tests
- [ ] SBOM generation
- [ ] Self-hosted runner support
- [ ] Advanced security features

---

## Risk Mitigation

### Technical Risks
- **GraalVM compatibility**: Test with multiple GraalVM distributions
- **Cross-platform issues**: Validate on all target platforms
- **Agent metadata complexity**: Implement robust merging logic
- **CI timeout issues**: Monitor build times, add fallback strategies

### Process Risks
- **Distribution channel complexity**: Start with core channels, add optional ones later
- **Documentation maintenance**: Keep examples up-to-date with template changes
- **User adoption**: Provide clear migration path from existing solutions

---

## Notes

- All implementation should follow the technical outline precisely
- Maintain backward compatibility where possible
- Document all configuration options thoroughly
- Test on multiple platforms before release
- Keep the template simple but powerful
- Focus on user experience and ease of adoption
