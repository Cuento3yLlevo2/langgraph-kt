# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
./gradlew build                              # Full build (compile + test)
./gradlew test                               # All tests
./gradlew :langgraph-kt-core:test            # Core module tests only
./gradlew :langgraph-kt-langchain4j:test     # LangChain4j integration tests only
./gradlew clean                              # Clean build artifacts
```

To run a single test class:
```bash
./gradlew :langgraph-kt-core:test --tests "org.langgraphkt.CompiledGraphTest"
```

**Requirements:** JDK 17+, Kotlin 1.9.22, Gradle 8.5 (use `./gradlew` wrapper).

## Architecture Overview

LangGraph-kt is a Kotlin-native execution engine for stateful AI agent workflows. It models computation as a directed graph where nodes transform immutable state and edges define routing between nodes.

### Modules

- **`langgraph-kt-core`** — Graph primitives, execution engine, checkpointing, streaming
- **`langgraph-kt-langchain4j`** — LangChain4j integration; wraps blocking LLM calls in `Dispatchers.IO`

### Execution Flow

1. **Define** a graph using `StateGraph<State> { ... }` DSL builder
2. **Compile** it with `.compile()` → produces a `CompiledGraph<State>`
3. **Execute** via `invoke()` (returns final state) or `stream()` (returns `Flow<State>`, emits after each node)

Graph execution starts at `START` (virtual node), traverses nodes via static or conditional edges, and terminates at `END`.

### Core Abstractions

| Type | Purpose |
|------|---------|
| `NodeAction<State>` | `typealias suspend (State) -> State` — a node's pure transformation |
| `EdgeCondition<State>` | `typealias suspend (State) -> String` — dynamic routing to next node name |
| `Reducer<State>` | `fun interface` — merges parallel state updates; required for fan-in/fan-out |
| `Checkpointer<State>` | Interface with `save()`/`load()` — persistence abstraction |
| `Checkpoint<State>` | `data class(state, nextNodes)` — saved execution point |
| `GraphConfig<State>` | Execution config: `threadId`, `checkpointer`, `interruptBefore/After`, `maxIterations` |

### Key Design Rules (from CONTRIBUTING.md)

1. **Immutability is Law** — state must be a `data class`; always use `.copy()`. Never use `var`, `MutableList`, or mutate state in-place.
2. **Native Coroutines Only** — all node/edge functions are `suspend`. Never use `Thread.sleep()`, `RxJava`, or `BlockingCoroutine`. Wrap blocking external calls (e.g., LLM APIs) in `withContext(Dispatchers.IO)`.
3. **Type-Safe DSL** — use infix functions, reified types, and compile-time safety. No stringly-typed APIs.

### Parallelism

Fan-out: add multiple edges from one node. Fan-in: provide a `Reducer<State>` to `CompiledGraph`. Parallel branches execute via `coroutineScope { async { ... }.awaitAll() }`.

### Checkpointing / Human-in-the-Loop

Configure `GraphConfig` with `interruptBefore = listOf("nodeName")` or `interruptAfter`. The graph saves a `Checkpoint` and halts. Resume by calling `invoke(..., resume = true)` after human review.

`FileCheckpointer` serializes state to JSON on disk. `MemoryCheckpointer` is provided for tests.

### Cycle Detection

`maxIterations` (default 25) in `GraphConfig` prevents infinite loops. Exceeding it throws `MaxIterationsExceededException`.

### Testing

All tests use `runTest { ... }` from `kotlinx.coroutines.test` for suspend functions. Use `MemoryCheckpointer` in tests instead of `FileCheckpointer`.
