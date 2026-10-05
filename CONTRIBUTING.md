# Contributing to langgraph-kt

Thank you for considering a contribution. This guide covers the setup, the checks a change must
pass, and the design rules the project follows.

By participating you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## Getting started

Requirements: JDK 17 or newer. Everything else (Gradle, Kotlin, Kotlin/Native toolchains, Node.js for
JS tests) is downloaded by the Gradle wrapper.

```bash
git clone git@github.com:Cuento3yLlevo2/langgraph-kt.git
cd langgraph-kt
./gradlew check
```

Useful commands:

| Command | What it does |
|---|---|
| `./gradlew check` | Compiles every target, runs all tests that can run on your OS, ktlint, and the coverage gate |
| `./gradlew :langgraph-kt-core:jvmTest` | Fast feedback: core tests on the JVM only |
| `./gradlew :langgraph-kt-core:jvmTest --tests "org.langgraphkt.InterruptTest"` | A single test class |
| `./gradlew ktlintFormat` | Fixes formatting |
| `./gradlew apiCheck` / `apiDump` | Checks / updates the public API dumps (see below) |
| `./gradlew :samples:runQuickStart` | Runs a sample |
| `./gradlew dokkaGenerate` | Builds the API reference into `build/dokka/html` |

Apple targets (iOS, macOS) only build and test on macOS, and the Windows target only tests on
Windows. CI covers all of them, so you do not need every OS locally.

The tests of `langgraph-kt-checkpoint-browser` need a real browser, so `check` runs them in headless
Chrome. Install Chrome or Chromium, and set `CHROME_BIN` to its path if it is not found.

## Project layout

| Path | Contents |
|---|---|
| `langgraph-kt-core` | Graph builder, execution engine, checkpoint interfaces (multiplatform, depends only on kotlinx-coroutines) |
| `langgraph-kt-serialization` | `KotlinxStateSerializer` (multiplatform) |
| `langgraph-kt-checkpoint-file` | `FileCheckpointer` on kotlinx-io (multiplatform) |
| `langgraph-kt-checkpoint-browser` | `LocalStorageCheckpointer` for web apps (JS and Wasm in a browser) |
| `langgraph-kt-agent` | `ChatModel`, tools and the tool-calling agent loop (multiplatform) |
| `langgraph-kt-anthropic` | `AnthropicChatModel`, Claude through Ktor (multiplatform) |
| `langgraph-kt-langchain4j` | LangChain4j integration (JVM) |
| `samples` | Runnable examples, not published. `samples/.../tutorial` holds the code of the tutorial |
| `docs` | The tutorial, one Markdown page per level |
| `build-logic` | Gradle convention plugins shared by all modules |
| `gradle/libs.versions.toml` | Every dependency version |

## Design rules

### 1. State is immutable

- State types are `data class`es with `val` properties and read-only collections.
- Nodes return a new state with `.copy()`. They never mutate the state they receive.
- Parallel branches depend on this: each branch gets the same input, and their results are combined
  after the step.

### 2. Coroutines only

- Node actions, routers and reducers are `suspend` functions.
- Parallel work uses structured concurrency (`coroutineScope` / `async`), so cancellation and
  failures propagate.
- Do not block a thread (`Thread.sleep`, `runBlocking`, blocking I/O). Wrap calls to blocking
  libraries in `withContext(Dispatchers.IO)`, as the LangChain4j module does.
- Never catch `CancellationException` without rethrowing it.

### 3. A small, type-safe public API

- All modules use Kotlin's explicit API mode: every public declaration needs `public`, an explicit
  type, and KDoc.
- Keep implementation details `internal`. Graphs are only created through `StateGraph`.
- Prefer checks that fail in `compile()` over checks that fail in the middle of a run, and throw a
  `LangGraphException` subclass rather than a generic exception.
- Core must stay in `commonMain` and depend only on kotlinx-coroutines. Platform- or
  library-specific code belongs in its own module.

## Making a change

1. For anything larger than a small fix, open an issue first so the approach can be discussed. The
   [roadmap](ROADMAP.md) lists what is planned before `1.0`.
2. Branch from `main` (for example `feature/room-checkpointer` or `fix/resume-after-crash`).
3. Write the code and its tests. Core tests go in `commonTest` and use `runTest { }` from
   `kotlinx-coroutines-test`, so they run on every platform. Use `MemoryCheckpointer` unless the
   test is about files.
4. Run `./gradlew check apiCheck`.
5. If you changed the public API on purpose, run `./gradlew apiDump` and commit the updated files in
   `*/api/`. Reviewers use that diff to see exactly what changed for users.
6. If you change a tutorial level in `samples/.../tutorial`, update its page in `docs/` so the code
   and the output shown there stay the same as the program. `TutorialTest` pins the output.
7. Add a line to the "Unreleased" section of [CHANGELOG.md](CHANGELOG.md) when users will notice
   the change.
8. Open a pull request against `main` and link the issue.

`main` is the only long-lived branch. It always holds the next version, and releases are tags on it.

### Commit messages

Use a short area prefix and an imperative summary, as the history does:

```
Feature: Add RoomCheckpointer
Fix: Keep step counter when resuming
Docs: Explain reducers
Build: Upgrade Kotlin to 2.5
```

Mention breaking changes in the body with a line starting with `BREAKING:`.

### Dependencies

Core depends only on kotlinx-coroutines, and that is deliberate. New third-party dependencies need a
good reason and usually belong in a separate module. Add versions to `gradle/libs.versions.toml`.

### AI coding agents

Changes made with AI assistance are welcome and are held to the same standard: you are responsible
for understanding and testing what you submit. Agent-specific instructions live in
[CLAUDE.md](CLAUDE.md).

## Releasing (maintainers)

One-time setup:

- Register and verify the `io.github.cuento3yllevo2` namespace at <https://central.sonatype.com>.
- Create a GPG key and publish its public part to a key server.
- Add the repository secrets `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD` (a Central Portal
  user token), `SIGNING_IN_MEMORY_KEY` (the ASCII-armored private key) and
  `SIGNING_IN_MEMORY_KEY_PASSWORD`.
- In the repository settings, set Pages to deploy from GitHub Actions and enable private
  vulnerability reporting and Discussions.

For each release:

1. Open a pull request that moves the "Unreleased" entries in `CHANGELOG.md` under the new version
   and date, and sets `VERSION_NAME` in `gradle.properties` to the release version. Merge it.
2. Tag that commit on `main` as `vX.Y.Z` and push the tag. The release workflow publishes to Maven
   Central and creates the GitHub release.
3. Open a pull request that sets `VERSION_NAME` to the next `-SNAPSHOT`.

## License

By contributing, you agree that your contributions are licensed under the
[Apache License 2.0](LICENSE).
