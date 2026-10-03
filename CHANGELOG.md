# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). Until 1.0, minor versions may contain
breaking changes.

## [Unreleased]

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

[Unreleased]: https://github.com/Cuento3yLlevo2/langgraph-kt/commits/main
