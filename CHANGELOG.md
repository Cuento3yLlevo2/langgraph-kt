# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). Until 1.0, minor versions may contain
breaking changes.

## [Unreleased]

### Added

- A [roadmap](ROADMAP.md): what is planned before `1.0`, in which order, and what is not planned.

### Changed

- **The project is now called Telar.** It was langgraph-kt. To update:
  - Dependencies: `io.github.cuento3yllevo2:langgraph-kt-<module>` becomes
    `dev.deeptelar:telar-<module>`, for example `dev.deeptelar:telar-core`.
  - Imports: the package `org.langgraphkt` becomes `dev.deeptelar.telar`.
  - `LangGraphException` is now `TelarException`.
  - `LocalStorageCheckpointer` stores its entries under `telar.checkpoint.` by default, where it
    was `langgraph.checkpoint.`. Pass `keyPrefix = "langgraph.checkpoint."` to read the runs an
    earlier version saved.

## [0.1.0-alpha05] - 2026-10-05

### Added

- `langgraph-kt-checkpoint-browser`, a new module for Kotlin/JS and Kotlin/Wasm in a browser.
  `LocalStorageCheckpointer` keeps the checkpoints in the page's `localStorage`, so a paused run is
  still there after a reload. A full or blocked storage is reported as a `LocalStorageException`.
- The tutorial teaches agents with tools. Level 6, "The agent", has a node that asks a model, a
  tool, `toolAgent`, and the same loop in a graph of your own with `toolLoop`. Its program runs
  without an API key (`./gradlew :samples:runLevel6`).

### Changed

- The tutorial has eight levels, one for each stage of the Pixel Pizza game, in place of eleven:
  1. A line of nodes (was "Your first graph" and "A line of nodes")
  2. Choices
  3. Loops
  4. Two things at once (was "Doing two things at once" and "Watching a run")
  5. Save points
  6. The agent (was "A real AI model", now on the `ChatModel` of `langgraph-kt-agent` in place of
     LangChain4j's `chatNode`, and with tools)
  7. Game over screens
  8. Your own workflow, whose complete help desk now has a tool agent

  The pages in `docs/`, the run tasks (`runLevel1` to `runLevel8`) and the sample packages are
  renumbered to match. "The idea" is an introduction without a number.

## [0.1.0-alpha04] - 2026-10-04

### Added

- Streaming of a model's answer. A `toolAgent` or `toolLoop` whose run is collected with `stream`
  delivers the text while the model writes it, and `event.textDelta` reads each piece.
  - `ChatModel.stream(request)` returns a `Flow` of `ChatEvent.TextDelta` and a final
    `ChatEvent.Completed`. A model that only has `chat` delivers its text in one piece.
  - `chatWithProgress` does the same for a model call in a node of your own. It takes a
    `ChatRequest` or a prompt, like `chat`.
  - `AnthropicChatModel` streams on every target, over server-sent events.
  - `LangChain4jChatModel` takes a LangChain4j `StreamingChatModel` as a second argument and streams
    through it.
- `reportProgress(value)` in `langgraph-kt-core`: a node reports what it is doing while it runs, and
  the value arrives in the stream of the run as the new `GraphEvent.NodeProgress`.
  `isProgressCollected()` tells a node whether anyone collects it.
- `AnthropicChatModel` keeps only the text of the first model when another model took over an answer
  through the `fallbacks` parameter, as the API requires for the next request.

### Changed

- `GraphEvent` has a sixth kind, `NodeProgress`. A `when` over the events without an `else` branch
  needs a branch for it.

## [0.1.0-alpha03] - 2026-10-04

### Added

- `toolLoop` takes a `firstMessage`: the user message that starts the conversation, built from other
  fields of the state. The loop stores it with the model's first answer, so `messages` and `append`
  only read and write one list. Before, the first message had to be built in `messages`, and an
  `append` that added to the stored list lost it without an error. The parameter comes before
  `system`, so pass `system` by name.

### Changed

- `ChatResponse.truncated` moved to the message: read `ChatMessage.Assistant.truncated`, and set it
  there in a `ChatModel` of your own. Before, an answer that was cut off at the model's output limit
  could not be told from a complete one once it was in the state, because `toolLoop` only passes
  messages to `append`. `AgentState.answerTruncated` reports it for a `toolAgent`.
- An exception of the library that a node throws is now wrapped in `NodeExecutionException` like any
  other exception. Before, it reached the caller as it was, so a failed model call in a `toolAgent`
  did not say which node failed. Where you caught `ChatModelException` from `invoke`, `resume` or
  `stream`, catch `NodeExecutionException` and read its `cause`. The same holds for the function of a
  conditional edge (`EdgeConditionException`) and for the reducer (`ReducerException`), and for a
  node that runs another graph: the failure of the inner graph is the `cause`.

## [0.1.0-alpha02] - 2026-10-04

### Added

- `langgraph-kt-agent`, a new module for every target, with what a graph needs to work with a
  language model:
  - `ChatModel`, a one-function interface to a model provider, with `ChatRequest`, `ChatResponse`
    and `ChatMessage` (`User`, `Assistant`, `ToolResult`). Messages are `@Serializable`.
  - `Tool`, a function the model may call. `Tool<Input>(name, description) { input -> ... }` builds
    the JSON Schema of the tool from a `@Serializable` input class, and `@Description` describes a
    property to the model.
  - `toolAgent(model, tools, system)`, a ready-made tool-calling agent graph over `AgentState`, and
    `toolLoop`, which adds the same loop to a graph with a state of your own.
  - `pendingToolCalls()`, the tool calls that wait when a run is paused before its tools.
  - `ChatModelException`.
- `langgraph-kt-anthropic`, a new module for every target: `AnthropicChatModel` calls Claude through
  Ktor.
- `LangChain4jChatModel` in `langgraph-kt-langchain4j`, which makes any LangChain4j model a
  `ChatModel`.
- The `ToolAgent` sample.

## [0.1.0-alpha01] - 2026-10-04

First public release. Everything below is new compared with the unpublished beta.

### Added

- Kotlin Multiplatform support: JVM/Android, iOS, macOS, Linux, Windows, JS and Wasm.
- `GraphResult` (`Completed` / `Interrupted`) as the return type of `invoke` and `resume`.
- `CompiledGraph.resume()` and `streamResume()` to continue a paused run, optionally editing the state.
- `CompiledGraph.lastResult()` to read where a thread stopped without running it.
- `GraphEvent` stream (`NodeStarted`, `NodeCompleted`, `StepCompleted`, `Interrupted`, `Completed`)
  and `Flow<GraphEvent>.states()`.
- `CompiledGraph.topology` (`GraphTopology`, `GraphEdge`) to inspect or draw a compiled graph.
- Graph validation in `compile()`: unknown, unreachable and duplicate nodes or edges, conflicting
  edge kinds, and two nodes that return a whole state in the same step without a reducer.
- `conditionalEdge(from, targets)` with declared targets, checked at compile time and at run time.
- Nodes with a `work` and an `update`, `node(name, work) { state, result -> ... }`, for parallel
  branches. Their work runs at the same time and their updates are applied one after another, so
  they need no `Reducer`.
- `NodeRef` and the infix `then` for type-safe edges: `START then a then b then END`.
- Conditional edges that route between node references instead of names:
  `conditionalEdge(a, targets = setOf(b, c)) { b }`, with `NodeRef.END` to finish.
- `LangGraphException` hierarchy: `GraphValidationException`, `NodeExecutionException`,
  `EdgeConditionException`, `ReducerException`, `InvalidRouteException`,
  `MaxIterationsExceededException`, `CheckpointNotFoundException`,
  `GraphAlreadyCompletedException`, `CheckpointCorruptedException`.
- `Checkpointer.delete()`, and a checkpoint after every step with a step counter.
- `langgraph-kt-serialization` module with `KotlinxStateSerializer`, and `CheckpointCodec` to build
  a checkpointer for any storage.
- `langgraph-kt-checkpoint-file` module: `FileCheckpointer` with atomic writes and a versioned format.
- `chatNode` and `chatMessagesNode` for LangChain4j 1.x `ChatModel`.
- Runnable samples in `samples/`.
- A step-by-step tutorial in `docs/`, with a runnable program for every level.

### Changed

- `invoke` always starts a new run; it no longer loads an existing checkpoint.
- `GraphConfig.interruptBefore` / `interruptAfter` are sets, and need a checkpointer.
- `Reducer.reduce` is a suspend function.
- `FileCheckpointer` takes a `kotlinx.io.files.Path` and lives in `org.langgraphkt.checkpoint.file`.
- `MemoryCheckpointer` is safe for concurrent use.
- Requires Kotlin 2.x; JVM artifacts target Java 11, except `langgraph-kt-langchain4j`, which needs
  Java 17 because LangChain4j does.

### Removed

- The `resume: Boolean` parameter of `invoke` and `stream`.
- `generateNode` and `generateSuspending` (replaced by `chatNode` and `chatSuspending`).
- `Checkpoint.nextNode` and the single-node constructor.
- Public access to `Node`, `Edge`, `ConditionalEdge` and the `CompiledGraph` constructor.

### Fixed

- A completed thread could not be run again.
- `invoke` ignored its input when the thread had a checkpoint.
- A `withTimeout` that expired inside a node cancelled the caller instead of raising
  `NodeExecutionException`.
- `resume` ran an `interruptBefore` node without pausing when the run had paused through
  `interruptAfter` or was resumed after a crash. `Checkpoint.interruptedBefore` records the pause.
- An exception thrown by the function of a conditional edge or by the reducer reached the caller
  unwrapped. It is now an `EdgeConditionException` or a `ReducerException`.
- A run that failed in its first step left no checkpoint, so `resume` could not retry it. The
  run's input is now saved before the first node runs.
- Different thread ids could map to the same checkpoint file.
- `START` counted toward `maxIterations`.
- `FileCheckpointer` did not write the format version into its files.

[Unreleased]: https://github.com/deeptelar/telar/compare/v0.1.0-alpha05...HEAD
[0.1.0-alpha05]: https://github.com/deeptelar/telar/compare/v0.1.0-alpha04...v0.1.0-alpha05
[0.1.0-alpha04]: https://github.com/deeptelar/telar/compare/v0.1.0-alpha03...v0.1.0-alpha04
[0.1.0-alpha03]: https://github.com/deeptelar/telar/compare/v0.1.0-alpha02...v0.1.0-alpha03
[0.1.0-alpha02]: https://github.com/deeptelar/telar/compare/v0.1.0-alpha01...v0.1.0-alpha02
[0.1.0-alpha01]: https://github.com/deeptelar/telar/releases/tag/v0.1.0-alpha01
