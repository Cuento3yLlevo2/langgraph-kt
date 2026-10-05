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
./gradlew :langgraph-kt-agent:jvmTest        # Agent module on the JVM (`allTests` for every target)
./gradlew :langgraph-kt-checkpoint-browser:allTests  # Browser module, in headless Chrome
./gradlew kotlinUpgradeYarnLock kotlinWasmUpgradeYarnLock  # After a dependency change that touches JS or Wasm
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
- **`langgraph-kt-checkpoint-browser`** (JS and Wasm, browser only, code in `webMain`):
  `LocalStorageCheckpointer` on `localStorage`. Its tests run in headless Chrome
- **`langgraph-kt-agent`** (KMP): `ChatModel` (the provider-neutral model interface), `ChatMessage`,
  `Tool` (JSON Schema built from a `@Serializable` input class in `ToolSchema.kt`), and the
  tool-calling loop: `toolLoop` adds a model node and a tools node to any graph, `toolAgent` is that
  loop as a graph over `AgentState`
- **`langgraph-kt-anthropic`** (KMP): `AnthropicChatModel`, the Claude Messages API on Ktor client
  core. The app supplies the `HttpClient` and its engine
- **`langgraph-kt-langchain4j`** (JVM): `LangChain4jChatModel` adapts a LangChain4j 1.x model to
  `ChatModel`; `chatNode` / `chatMessagesNode` build a node straight from a LangChain4j model
- **`samples`**: runnable examples with tests; not published. `samples/.../tutorial/levelN` is the
  code of the tutorial in `docs/`; a page shows its level's code and output, so change both together
- **`build-logic`**: convention plugins `langgraph.kmp-library`, `langgraph.web-library`,
  `langgraph.jvm-library`, `langgraph.quality` (ktlint, Kover, Dokka), `langgraph.publishing`,
  `langgraph.root`

### Execution Flow

1. **Define** with the `StateGraph<State> { ... }` DSL. `node(name) { ... }` returns a whole state;
   `node(name, work) { state, result -> ... }` splits a node into slow work and a state update, for
   nodes that run in parallel. Both return a `NodeRef`; connect nodes with
   `START then a then b then END`, `edge(from, to)`, or `conditionalEdge(from, targets) { ... }`.
2. **Compile** with `.compile()`, which validates the graph and returns a `CompiledGraph<State>`. It
   needs a `reducer` only when two nodes that return a whole state can run in the same step.
3. **Execute** with `invoke(input, config)` (returns `GraphResult.Completed` or `.Interrupted`) or
   `stream(input, config)` (a `Flow<GraphEvent<State>>`). A node calls `reportProgress(value)` to
   send a `GraphEvent.NodeProgress` to a stream while it runs; `toolLoop` reports the model's text
   that way (`ChatModel.stream`, `chatWithProgress`, `GraphEvent.textDelta`). Continue a paused run
   with `resume(config) { state -> ... }` / `streamResume`. `lastResult(config)` reads where a
   thread stopped without running it.

The engine (`CompiledGraph.kt`) runs in steps: all active nodes run in parallel on the same input
state, their results are combined (the `Reducer` merges whole states, then the updates of
work/update nodes are applied in the order the nodes were added), and outgoing edges select the
next active nodes. A checkpoint is saved after every step when a checkpointer is configured.

### Core Abstractions

| Type | Purpose |
|------|---------|
| `NodeAction<State>` | `suspend (State) -> State`, a node's transformation |
| `EdgeCondition<State>` | `suspend (State) -> String`, routes to the next node name or `END` |
| `Reducer<State>` | `fun interface` with a suspend `reduce`; merges whole states of parallel nodes; not needed for work/update nodes |
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
- `check` enforces 90% line coverage per module (Kover). Kover measures the JVM only, so the
  browser-only module has no gate: cover its behavior with tests in `webTest`.
- After an intentional public API change, run `./gradlew apiDump` and commit `*/api/*`.
