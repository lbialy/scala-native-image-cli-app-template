# Scala 3 → GraalVM Native Template — Technical Outline

This document specifies a GitHub template repository for building Scala 3 apps with **scala‑cli** and packaging **GraalVM Native Image** binaries for six targets (Linux/macOS/Windows × x86_64/ARM64). It defines repo layout, build tooling, CI workflows, release flow, distribution channels, and local ergonomics.

> **Goals**
>
> * Single‑repo template that a user can fork and ship a CLI quickly.
> * Deterministic CI with pinned toolchain, but overridable via CI inputs.
> * Generate prebuilt binaries for all feasible OS/arch matrices using GitHub‑hosted runners; provide switches for optional targets where runners may be constrained.
> * Ship tap/manifests for Homebrew (tap), Scoop (default), Debian/RPM, AUR (optional), and Chocolatey (optional).
> * Keep advanced native-image choices user‑configurable in **SETUP.md**.

---

## 1) Repository Layout

```
.
├─ app/                              # Scala-CLI project (single-module)
│  ├─ src/
│  │  └─ Main.scala
│  ├─ test/
│  │  └─ MainSpec.scala
│  ├─ resources/
│  │  └─ META-INF/native-image/      # merged agent output (do not clean; Just merges)
│  └─ project.scala                  # scala-cli project config (pins Scala version, etc.)
├─ packaging/
│  ├─ brew/
│  │  └─ Formula.rb.tmpl             # Template for Tap formula
│  ├─ scoop/
│  │  └─ manifest.json.tmpl          # Template for Scoop bucket
│  ├─ choco/                         # Optional Chocolatey nuspec + scripts (tmpl)
│  ├─ nfpm/                          # Deb/RPM templates (nfpm.yaml.tmpl)
│  └─ aur/                           # PKGBUILD.tmpl and helpers
├─ scripts/
│  ├─ merge-native-image-meta.sh     # merges agent JSONs into resources/META-INF/native-image
│  ├─ strip-test-deps.jar            # **provided by you** (test-dep filtering tool)
│  └─ ci-env.sh                      # common CI helpers (paths, names)
├─ .github/
│  └─ workflows/
│     ├─ ci.yml                      # build+test on PR and main (monolithic by design)
│     └─ release.yml                 # tag-driven release build + publish + channel PRs
├─ Justfile                          # local/CI task orchestration
├─ README.md                         # quickstart + example CLI usage
├─ SETUP.md                          # all configurable options (native-image, CPUs, etc.)
├─ LICENSE                           # MIT
└─ .gitignore
```

---

## 2) Toolchain & Pinning

* **Scala-CLI**: pinned in CI via action; local users may install separately.
* **JDK/Graal**: user-selectable distribution/version (documented in **SETUP.md**). Baseline template defaults to a widely available GraalVM distribution; users can switch to Liberica NIK or others.
* **Pinning Strategy**

  * Default versions in workflow `env:` (scala-cli, JDK, GraalVM), overridable via workflow_dispatch inputs.
  * All actions pinned by commit SHA to maximize supply-chain stability.

> All CPU tuning, glibc vs musl, GC, and other native-image options are **documented** and **user-configurable** in **SETUP.md**; template itself uses conservative defaults.

---

## 3) Build Matrix & Runners

**Targets** (6 desired):

* Linux: `linux-x86_64`, `linux-aarch64`
* macOS: `macos-x86_64`, `macos-aarch64`
* Windows: `windows-x86_64`, `windows-arm64`

**Runner mapping** (pragmatic defaults):

* `ubuntu-24.04` → linux-x86_64
* `ubuntu-24.04-arm` → linux-aarch64
* `macos-14` → macos-aarch64
* `windows-2022` → windows-x86_64
* `macos-x86_64` and `windows-arm64` jobs are declared but **gated** behind `inputs.enable_optional_targets` or an internal env guard, acknowledging hosted-runner availability varies over time. Users can enable/disable in **SETUP.md** and `release.yml` inputs.

**Timeouts**: Each job uses `timeout-minutes: 60`.

---

## 4) Scala-CLI Project Conventions

* Single-module layout (scala-cli native).
* Versioning: SemVer from Git tags `vX.Y.Z`. Untagged commits produce `X.Y.Z-SNAPSHOT+<shortsha>`.
* JVM tests via `scala-cli test` on all pushes/PRs.
* Optional native-binary smoke tests are captured as a **future task** (non-trivial; see §11).

---

## 5) Native Image Agent Orchestration

**Goal**: Make it trivial to generate/maintain reflection/resource/config metadata while keeping **test dependencies** out of the final metadata.

**Design**

* Provide `just` tasks that run app **and** tests under the `native-image-agent`.
* Agent outputs are **merged** (not wiped) into `app/resources/META-INF/native-image` via `scripts/merge-native-image-meta.sh`.
* After each agent run, execute a **test-dep filtering** step to strip metadata originating from test classpath. This step calls your provided tool (e.g., `scripts/strip-test-deps.jar`) which implements *classloader diffing* logic.

**Agent output directory**

* Do **not** use `--config-output-dir` pointing to the resources dir directly.
* Instead, emit to a temp dir per run (e.g., `.out/native-image-agent/<timestamp>/`) and then merge into `app/resources/META-INF/native-image` with the merge script.

**Passing JVM opts to scala-cli**

* Use `scala-cli run . --java-opt=...` and `scala-cli test . --java-opt=...` (not env vars).

---

## 6) `Justfile` (authoritative task runner)

The CI calls these same `just` targets for parity.

```
# Variables
BINARY_NAME := {{default: project name; overridable in SETUP.md}}
MAIN_DIR := app
RES_META := {{MAIN_DIR}}/resources/META-INF/native-image
AGENT_OUT := .out/native-image-agent

# 1) Vanilla build/test/run (agent-free)
build:
	scala-cli compile {{MAIN_DIR}}

test:
	scala-cli test {{MAIN_DIR}}

run ARGS=:
	scala-cli --power run {{MAIN_DIR}} -- {{ARGS}}

# 2) Agent-assisted runs (generate+merge metadata)
agent-run ARGS=:
	mkdir -p {{AGENT_OUT}}
	TMP=$$(mktemp -d {{AGENT_OUT}}/run-XXXX)
	scala-cli --power run {{MAIN_DIR}} \
	  --java-opt=-agentlib:native-image-agent=config-output-dir=$$TMP,experimental-class-define-support \
	  -- {{ARGS}}
	./scripts/merge-native-image-meta.sh $$TMP {{RES_META}}
	# strip test deps from merged JSONs
	java -jar scripts/strip-test-deps.jar {{RES_META}}

agent-test:
	mkdir -p {{AGENT_OUT}}
	TMP=$$(mktemp -d {{AGENT_OUT}}/test-XXXX)
	scala-cli test {{MAIN_DIR}} \
	  --java-opt=-agentlib:native-image-agent=config-output-dir=$$TMP,experimental-class-define-support
	./scripts/merge-native-image-meta.sh $$TMP {{RES_META}}
	java -jar scripts/strip-test-deps.jar {{RES_META}}

# 3) Native build (configurable flags live in SETUP.md)
# NOTE: Disable fallback image; other flags are user-configurable.
native OS=auto ARCH=auto:
	scala-cli --power package {{MAIN_DIR}} \
	  --native-image \
	  --native-image-option=-H:-FallbackThreshold=1 \
	  -o dist/{{BINARY_NAME}}

# 4) Checksums
checksums:
	cd dist && shasum -a 256 * > checksums.txt || sha256sum * > checksums.txt
```

> **Policy**: Never delete `resources/META-INF/native-image`. Merges are additive and idempotent (the merge script should JSON-dedupe arrays/objects by content).

---

## 7) CI — `ci.yml` (PRs & pushes)

* Triggers: PRs, pushes to default branch.
* Steps (per OS/arch job):

  1. Checkout
  2. Setup scala-cli (action)
  3. Setup JDK/Graal (user-selectable; defaults provided)
  4. Cache Coursier & scala-cli
  5. `just build` and `just test` (agent-free for speed in CI)
  6. Optional: `just agent-test` nightly to keep metadata fresh (scheduled workflow)

**Matrix** (baseline):

* linux/amd64, linux/arm64, macos/arm64, windows/amd64
* Optional: macos/amd64, windows/arm64 (guarded by input/env)

**Caching**: Enabled here; *not* used in publishing flow (per policy).

---

## 8) Releases — `release.yml` (tag-driven)

* Trigger: `push` of `v*` tags; also `workflow_dispatch` with inputs:

  * `version`: overrides tag parsing
  * `enable_optional_targets`: boolean
  * `scala_cli_version`, `graal_version`, `jdk_dist`
* Jobs per target build a native binary via `just native` with `--native-image`.
* Artifacts:

  * `{{app}}-{{os}}-{{arch}}` (no extension on UNIX; `.exe` on Windows)
  * `{{app}}-{{os}}-{{arch}}.sha256` + a combined `checksums.txt`
* GitHub Release:

  * Create or update release (draft for RCs, published for finals)
  * Upload assets

> **Signing/Notarization**: Not enabled by default. Users can add macOS codesigning/notarization and Windows Authenticode via optional steps guarded by secrets. (See §12.)

---

## 9) Distribution Channels

### 9.1 Homebrew (Tap)

* Provide a tap formula template in `packaging/brew/Formula.rb.tmpl`.
* Release job computes the formula (URL to GH Release asset + SHA256) and pushes to a tap repo (created by user) via a bot token.
* Homebrew Core is out-of-scope for the template; users may promote later.

### 9.2 Scoop (default for Windows)

* Template manifest `packaging/scoop/manifest.json.tmpl`.
* Release job updates a Scoop bucket repo (user’s) with the new version + checksums.

### 9.3 Chocolatey (optional)

* Nuspec + install/uninstall PowerShell scripts in `packaging/choco/`.
* Release job packs and pushes with `CHOCOLATEY_API_KEY` if present.

### 9.4 Linux Packages (optional)

* Use **nfpm** to emit `.deb` and `.rpm` from an nfpm template.
* Artifacts reference the prebuilt binary (no build-from-source).
* Upload deb/rpm alongside binaries in GH Release.

### 9.5 Arch AUR (optional)

* Generate `PKGBUILD` pointing to the GH Release tarball.
* Push to a user-maintained AUR repo using SSH key/secrets.

### 9.6 Version-bump Automation

* After the main release artifacts upload, a “channels” job opens PRs to:

  * Brew Tap repo
  * Scoop bucket repo
  * (Optional) Chocolatey push
  * (Optional) AUR and distro repos

---

## 10) Naming & Checksums

* Asset naming: `{{binary}}-{{OS}}-{{ARCH}}` where OS ∈ {linux, macos, windows}, ARCH ∈ {x86_64, aarch64} (Windows ARM64 → `arm64`).
* Include `checksums.txt` in the Release and individual `.sha256` files.

---

## 11) Future Work (Not in v1)

* **Native smoke tests**: non-trivial (need per-OS execution & timeouts). We’ll leave a stub job disabled by default.
* **SBOM/Provenance**: document options; scala-cli may not emit SBOMs. CycloneDX could be generated from dependency lock, or post-process.
* **macOS x86_64 & Windows ARM64**: Provide jobs gated by inputs; availability of hosted runners can vary. Users can enable or switch to self-hosted where required.

---

## 12) Security & Policies

* **Permissions**: Default to least privilege; per job: `permissions: contents: write` only where releases/channels need it; otherwise `read`.
* **Action pins**: Use commit SHAs.
* **Caches**: Allowed in CI (build/test). **No cache** in publishing/release jobs.
* **Secrets**: Read from GitHub Actions Secrets: `TAP_REPO_TOKEN`, `SCOOP_REPO_TOKEN`, `CHOCOLATEY_API_KEY`, `AUR_SSH_KEY`, `APPLE_NOTARIZE_*`, `WIN_CODESIGN_*`, etc.
* **Signing**: Not enabled by default. (Many popular CLIs ship unsigned binaries; users may opt-in.)

---

## 13) Documentation Files

### README.md (essentials)

* What this is; quickstart.
* How to run: `just run -- --help`
* Cutting a release: create tag `vX.Y.Z`.
* Where to find artifacts.
* Installing via Scoop/Homebrew Tap once configured.

### SETUP.md (config knobs)

* Selecting GraalVM/JDK distribution & version.
* Native Image options (glibc vs musl, GC, `-march`, initialize-at-build-time, etc.).
* CPU tuning policies and minimum OS baselines.
* Enabling optional matrix targets (mac x86_64, win arm64).
* Binary name overrides.
* Channel setup (creating Tap repo, Scoop bucket, nfpm, AUR, Chocolatey) and required secrets.
* Notes on `native-image-agent` workflow and how to contribute metadata safely.

---

## 14) Example CLI

* Minimal CLI using **case-app** to demonstrate flags and **auto-generated shell completions**.
* `just run -- --help` shows usage; optional target `just completions` can emit bash/zsh/fish/powershell scripts into `dist/completions/` (not installed by default).

---

## 15) Example CI Snippets

### 15.1 Common env

```yaml
env:
  APP_NAME: myapp
  SCALA_CLI_VERSION: 1.5.2           # default; overridable by inputs
  JDK_DIST: temurin                   # user-selectable; see SETUP.md
  JDK_VERSION: 23
  GRAALVM: graalvm-jdk-23            # example; user may switch to Liberica NIK
```

### 15.2 Matrix example (core four)

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

### 15.3 Steps (per job)

```yaml
steps:
  - uses: actions/checkout@<pin>

  - name: Setup Scala CLI
    uses: VirtusLab/scala-cli-setup-action@<pin>
    with:
      version: ${{ env.SCALA_CLI_VERSION }}

  - name: Setup JDK
    uses: actions/setup-java@<pin>
    with:
      distribution: ${{ env.JDK_DIST }}
      java-version: ${{ env.JDK_VERSION }}

  # If using a GraalVM distribution separate from setup-java, add custom installer here.

  - name: Cache Coursier
    uses: actions/cache@<pin>
    with:
      path: |
        ~/.cache/coursier
        ~/.scala-build
      key: ${{ runner.os }}-coursier-${{ hashFiles('**/*.scala', '**/project.scala') }}

  - name: Build
    run: just build

  - name: Test
    run: just test
```

### 15.4 Release job upload naming

```yaml
- name: Rename artifact
  shell: bash
  run: |
    mkdir -p dist
    OUT="dist/${APP_NAME}-${{ matrix.os_tag }}-${{ matrix.arch }}${{ runner.os == 'Windows' && '.exe' || '' }}"
    mv app/${APP_NAME} "$OUT"

- name: Checksums
  run: just checksums

- name: Upload Release Assets
  uses: softprops/action-gh-release@<pin>
  with:
    files: |
      dist/${{ env.APP_NAME }}-*
```

---

## 16) Open Questions captured as TODOs

* Native smoke tests pattern per OS (timeouts, temp dirs, path quoting on Windows).
* SBOM strategy compatible with scala-cli.
* Long-term plan for macOS x86_64 & Windows ARM64 on hosted runners vs self-hosted.

---

## 17) Practical Notes

* By policy, **no fallback images** (`-H:-FallbackThreshold=1`).
* Users can switch GraalVM distribution/version and pass advanced flags via **SETUP.md** config snippets; CI exposes inputs for quick overrides.
* Publishing flows avoid caches to reduce statefulness.
* Everything important is driven by **Justfile**; CI simply mirrors those tasks for parity.

---

**End of technical outline**

