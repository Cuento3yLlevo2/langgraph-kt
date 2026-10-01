# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.
Read CONTRIBUTING.md as well; its design rules apply to every change.

## Build & Test Commands

```bash
./gradlew check                              # Everything: compile all targets, tests, ktlint, coverage gate
./gradlew check apiCheck                     # What CI runs; apiCheck fails if the public API changed
./gradlew :langgraph-kt-core:jvmTest         # Fast loop: core tests on the JVM only
./gradlew :langgraph-kt-core:allTests        # Core tests on every target this OS can run (jvm, js, wasmJs, native)
./gradlew :langgraph-kt-langchain4j:test     # LangChain4j module (JVM-only module, so the task is `test`)
./gradlew ktlintFormat                       # Fix formatting
./gradlew apiDump                            # Update */api/*.api after an intentional public API change
./gradlew :samples:runQuickStart             # Run a sample
```

Single test class:

```bash
./gradlew :langgraph-kt-core:jvmTest --tests "org.langgraphkt.InterruptTest"
```

**Requirements:** JDK 17+. Use the `./gradlew` wrapper (Gradle 9.8, Kotlin 2.4). Versions live in
`gradle/libs.versions.toml`.

## Architecture Overview

langgraph-kt is a Kotlin Multiplatform execution engine for stateful AI agent workflows. Computation
is a directed graph: nodes transform an immutable state and edges decide what runs next.

### Modules

- **`langgraph-kt-core`** (KMP, `commonMain` only, depends only on kotlinx-coroutines): graph
  builder, engine, checkpoint interfaces
- **`langgraph-kt-serialization`** (KMP): `KotlinxStateSerializer` for `@Serializable` states,
  `CheckpointCodec` (the shared checkpoint format, also used by `FileCheckpointer`)
- **`langgraph-kt-checkpoint-file`** (KMP): `FileCheckpointer` on kotlinx-io
- **`langgraph-kt-langchain4j`** (JVM): `chatNode` / `chatMessagesNode` for LangChain4j 1.x `ChatModel`
- **`samples`**: runnable examples with tests; not published
- **`build-logic`**: convention plugins `langgraph.kmp-library`, `langgraph.jvm-library`,
  `langgraph.quality` (ktlint, Kover, Dokka), `langgraph.publishing`, `langgraph.root`

### Execution Flow

1. **Define** with the `StateGraph<State> { ... }` DSL. `node()` returns a `NodeRef`; connect nodes
   with `START then a then b then END`, `edge(from, to)`, or `conditionalEdge(from, targets) { ... }`.
2. **Compile** with `.compile(reducer = ...)`, which validates the graph and returns a `CompiledGraph<State>`.
3. **Execute** with `invoke(input, config)` (returns `GraphResult.Completed` or `.Interrupted`) or
   `stream(input, config)` (a `Flow<GraphEvent<State>>`). Continue a paused run with
   `resume(config) { state -> ... }` / `streamResume`.

The engine (`CompiledGraph.kt`) runs in steps: all active nodes run in parallel on the same input
state, the `Reducer` merges their results, and outgoing edges select the next active nodes. A
checkpoint is saved after every step when a checkpointer is configured.

### Core Abstractions

| Type | Purpose |
|------|---------|
| `NodeAction<State>` | `suspend (State) -> State`, a node's transformation |
| `EdgeCondition<State>` | `suspend (State) -> String`, routes to the next node name or `END` |
| `Reducer<State>` | `fun interface` with a suspend `reduce`; merges parallel updates; required for fan-out |
| `GraphConfig<State>` | `threadId`, `checkpointer`, `interruptBefore/After` (sets), `maxIterations` |
| `GraphResult<State>` / `GraphEvent<State>` | Outcome of `invoke`/`resume`, and events from `stream` (per node and per step) |
| `GraphTopology` | Nodes and edges of a compiled graph, from `CompiledGraph.topology` |
| `Checkpointer<State>` | `save` / `load` / `delete` per thread |
| `Checkpoint<State>` | `state`, `nextNodes` (empty when complete), `step`, `interruptedBefore` |
| `LangGraphException` | Base of all library exceptions (see `Exceptions.kt`) |

### Key Design Rules

1. **Immutability.** State is a `data class`; nodes return `.copy()`. No `var` or mutable
   collections in state.
2. **Coroutines only.** Everything is `suspend`. No blocking calls; wrap blocking libraries in
   `withContext(Dispatchers.IO)`. Never swallow `CancellationException`.
3. **Small, type-safe API.** Explicit API mode is on: public declarations need `public`, explicit
   types and KDoc. Keep internals `internal`. Validate in `compile()` rather than at run time, and
   throw `LangGraphException` subclasses.
4. **Core stays common.** No platform or third-party dependencies in `langgraph-kt-core`.

### Testing

- Core tests live in `commonTest` and use `runTest { ... }`, so they run on every target.
- Use `MemoryCheckpointer` unless the test is about files.
- `check` enforces 90% line coverage per module (Kover).
- After an intentional public API change, run `./gradlew apiDump` and commit `*/api/*`.
