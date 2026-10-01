# langgraph-kt

[![CI](https://github.com/Cuento3yLlevo2/langgraph-kt/actions/workflows/ci.yml/badge.svg)](https://github.com/Cuento3yLlevo2/langgraph-kt/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/kotlin-2.4-7F52FF.svg?logo=kotlin)](https://kotlinlang.org)

A Kotlin Multiplatform engine for stateful AI agent workflows. You describe an agent as a graph: nodes
transform an immutable state, edges decide what runs next, and the engine handles cycles, parallel
branches, streaming, and pausing for human approval.

- **Coroutines all the way down.** Nodes and routers are `suspend` functions, parallel branches use
  structured concurrency, and cancellation works as you expect.
- **Immutable, typed state.** State is your own `data class`. Nodes return copies, so parallel
  branches cannot race.
- **Human-in-the-loop.** Pause before or after any node, persist the run, and resume later, even in
  another process.
- **Streaming.** `stream()` returns a `Flow` of events, ready for Compose or any reactive UI.
- **Mistakes caught early.** Unknown nodes, unreachable nodes and missing reducers fail when the
  graph is compiled, not in the middle of a run.
- **Multiplatform.** JVM, Android, iOS, macOS, Linux, Windows, JS and Wasm.

> langgraph-kt is an independent project inspired by [LangGraph](https://github.com/langchain-ai/langgraph).
> It is not affiliated with or endorsed by LangChain, Inc.

## Status

Pre-release. The API described here is what `0.1.0` will ship, and it may still change before `1.0`.
Artifacts are not on Maven Central yet; until the first release, build from source with
`./gradlew publishToMavenLocal`.

## Installation

```kotlin
dependencies {
    implementation("io.github.cuento3yllevo2:langgraph-kt-core:0.1.0")

    // Optional modules
    implementation("io.github.cuento3yllevo2:langgraph-kt-serialization:0.1.0")   // kotlinx.serialization state serializer
    implementation("io.github.cuento3yllevo2:langgraph-kt-checkpoint-file:0.1.0") // persist runs as JSON files
    implementation("io.github.cuento3yllevo2:langgraph-kt-langchain4j:0.1.0")     // LangChain4j chat model nodes (JVM)
}
```

| Module | Targets | Purpose |
|---|---|---|
| `langgraph-kt-core` | JVM/Android, iOS, macOS, Linux, Windows, JS, Wasm | Graph builder, execution engine, checkpointing interfaces |
| `langgraph-kt-serialization` | same as core | `KotlinxStateSerializer` for `@Serializable` states |
| `langgraph-kt-checkpoint-file` | same as core (Node.js only for JS/Wasm) | `FileCheckpointer`, one JSON file per thread |
| `langgraph-kt-langchain4j` | JVM/Android | `chatNode` / `chatMessagesNode` for LangChain4j 1.x `ChatModel` |

Requires Kotlin 2.x. JVM artifacts target Java 11.

## Quick start

```kotlin
import org.langgraphkt.*

data class ArticleState(
    val topic: String,
    val notes: List<String> = emptyList(),
    val draft: String = "",
    val revisions: Int = 0,
)

val graph = StateGraph<ArticleState> {
    val research = node("research") { state ->
        state.copy(notes = listOf("${state.topic} is fast", "${state.topic} is safe"))
    }
    val write = node("write") { state ->
        state.copy(
            draft = state.notes.take(state.revisions + 1).joinToString(". "),
            revisions = state.revisions + 1,
        )
    }

    START then research then write
    // Loop on "write" until every note is used, then finish.
    conditionalEdge(write, targets = setOf(write.name, END)) { state ->
        if (state.revisions < state.notes.size) write.name else END
    }
}.compile()

suspend fun main() {
    val result = graph.invoke(ArticleState(topic = "Kotlin"))
    println(result.state.draft) // Kotlin is fast. Kotlin is safe
}
```

`node()` returns a reference you can connect with `then`. If you prefer plain names,
`edge("research", "write")` does the same thing.

## Guides

### Streaming

`stream()` emits an event after every step and ends with `Completed` or `Interrupted`:

```kotlin
graph.stream(ArticleState(topic = "Kotlin")).collect { event ->
    when (event) {
        is GraphEvent.StepCompleted -> println("step ${event.step} ran ${event.nodes}")
        is GraphEvent.Completed -> println("done: ${event.state.draft}")
        is GraphEvent.Interrupted -> println("paused before ${event.nextNodes}")
    }
}
```

For a UI that only renders the latest state, use `graph.stream(input).states()`, which is a `Flow<State>`.

### Human-in-the-loop

Give the run a checkpointer and say where to pause. `invoke` returns `Interrupted`; call `resume`
when the human has decided.

```kotlin
val config = GraphConfig(
    threadId = "order-1001",
    checkpointer = FileCheckpointer(Path("checkpoints"), KotlinxStateSerializer<RefundState>()),
    interruptBefore = setOf("issue_refund"),
)

when (val result = graph.invoke(RefundState(orderId = "1001", amount = 250), config)) {
    is GraphResult.Interrupted -> showApprovalDialog(result.state)
    is GraphResult.Completed -> showResult(result.state)
}

// Later, possibly after an app restart:
val finished = graph.resume(config) { state -> state.copy(approved = true) }
```

- `invoke` always starts a new run for the thread. `resume` continues the saved one, optionally
  editing the state first.
- A checkpoint is saved after every step, so a run can also be resumed after a crash.
- `MemoryCheckpointer` is available for tests. To store checkpoints elsewhere (Room, SQLDelight, a
  server), implement the three-method `Checkpointer` interface.

### Parallel branches

Several edges from one node run their targets in parallel. Each branch returns its own copy of the
state, and a `Reducer` merges them:

```kotlin
val mergeFindings = Reducer<ResearchState> { current, updates ->
    current.copy(findings = current.findings + updates.flatMap { it.findings - current.findings.toSet() })
}

val graph = StateGraph<ResearchState> {
    val web = node("web") { it.copy(findings = it.findings + searchWeb(it.question)) }
    val docs = node("docs") { it.copy(findings = it.findings + searchDocs(it.question)) }
    val summarize = node("summarize") { it.copy(summary = summarize(it.findings)) }

    START then web then summarize
    START then docs then summarize
    summarize then END
}.compile(reducer = mergeFindings)
```

If one branch fails, the others are cancelled and the error is rethrown as `NodeExecutionException`
with the name of the failing node.

### LangChain4j

`langgraph-kt-langchain4j` turns any LangChain4j `ChatModel` into a node. The blocking model call
runs on `Dispatchers.IO`.

```kotlin
val assistant = node("assistant", chatMessagesNode(
    model = OpenAiChatModel.builder().apiKey(apiKey).modelName("gpt-4o-mini").build(),
    messages = { state -> state.messages },
    update = { state, response -> state.copy(messages = state.messages + response.aiMessage()) },
))
```

`chatNode` is the shorter form for a single prompt string and a text reply.

### Error handling

Everything the library throws extends `LangGraphException`:

| Exception | When |
|---|---|
| `GraphValidationException` | The graph or `GraphConfig` is invalid. Thrown by `compile()` or when a run starts. |
| `NodeExecutionException` | A node threw. `nodeName` and the original `cause` are available. |
| `InvalidRouteException` | A conditional edge returned a node that does not exist or is not a declared target. |
| `MaxIterationsExceededException` | The run took more steps than `GraphConfig.maxIterations` (default 25). |
| `CheckpointNotFoundException`, `GraphAlreadyCompletedException` | `resume` had nothing to continue. |
| `CheckpointCorruptedException` | A stored checkpoint could not be read. |

## Samples

Runnable examples live in [`samples/`](samples/src/main/kotlin/org/langgraphkt/samples):

```bash
./gradlew :samples:runQuickStart
./gradlew :samples:runHumanInTheLoop
./gradlew :samples:runParallelResearch
./gradlew :samples:runChatAgent
```

## Contributing

Contributions are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for the development setup and the
three design rules (immutable state, coroutines only, type-safe DSL).

## License

[Apache License 2.0](LICENSE)
