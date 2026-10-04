# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). Until 1.0, minor versions may contain
breaking changes.

## [Unreleased]

### Changed

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

[Unreleased]: https://github.com/Cuento3yLlevo2/langgraph-kt/compare/v0.1.0-alpha02...HEAD
[0.1.0-alpha02]: https://github.com/Cuento3yLlevo2/langgraph-kt/compare/v0.1.0-alpha01...v0.1.0-alpha02
[0.1.0-alpha01]: https://github.com/Cuento3yLlevo2/langgraph-kt/releases/tag/v0.1.0-alpha01
