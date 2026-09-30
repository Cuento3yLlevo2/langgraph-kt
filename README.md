# langgraph-kt

**A native Kotlin Coroutine execution engine for LangGraph. Build stateful, offline-first AI agent workflows with human-in-the-loop breakpoints and LangChain4j integration.**

`langgraph-kt` brings the power of cyclic, stateful LLM orchestration to the JVM and Android ecosystems. By leveraging Kotlin Coroutines and immutable data classes, it provides a type-safe, highly concurrent, and native way to run local agents without the overhead of Python interop or thread-blocking Java implementations.

## Key Features

* **Native Coroutines:** Built entirely on `suspend` functions and structured concurrency. Never block the main thread while waiting for local LLMs (`llama.cpp`, MLC LLM) to generate tokens.
* **Type-Safe State:** Uses Kotlin's `data class` and `.copy()` patterns to enforce immutable state transitions, eliminating race conditions in parallel agent execution.
* **State Streaming:** Native support for Jetpack Compose UI via `Flow<State>`, allowing you to easily observe real-time agent updates.
* **Advanced Parallelism:** Full support for fan-out execution and `Reducer` interfaces to safely merge parallel branch updates.
* **Human-in-the-Loop:** Built-in `Checkpointer` interfaces allow graphs to pause execution, persist state (to JSON files, Room DB, etc.), and wait for user approval.
* **LangChain4j Ready:** Designed to orchestrate LangChain4j `ChatLanguageModel` instances out of the box with the `generateNode` builder.

## Installation

Add the dependency to your `build.gradle.kts` file:

```kotlin
dependencies {
    // Core engine
    implementation("com.yourusername:langgraph-kt-core:0.1.0")
    
    // Optional: LangChain4j extensions
    implementation("com.yourusername:langgraph-kt-langchain4j:0.1.0")
    
    // Serialization (Required if using FileCheckpointer)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
}
```

## Quick Start

Define your state using an immutable Kotlin data class, then use the Kotlin DSL to build your graph routing logic.

```kotlin
import org.langgraphkt.*
import kotlinx.coroutines.flow.collect

data class AgentState(
    val input: String = "",
    val messages: List<String> = emptyList(),
    val requiresApproval: Boolean = false
)

suspend fun main() {
    // 1. Build the Graph via Kotlin DSL
    val workflow = StateGraph<AgentState> {
        
        node("researcher") { state ->
            val response = "Found data for: ${state.input}"
            state.copy(
                messages = state.messages + response,
                requiresApproval = true 
            )
        }
        
        node("human_approval") { state -> state } // Dummy node for interrupt
        
        node("writer") { state ->
            val draft = "Drafting summary based on: ${state.messages.last()}"
            state.copy(messages = state.messages + draft)
        }
        
        // Routing
        edge(from = START, to = "researcher")
        
        conditionalEdge(from = "researcher") { state ->
            if (state.requiresApproval) "human_approval" else "writer"
        }
        
        edge(from = "human_approval", to = "writer")
        edge(from = "writer", to = END)
    }

    val app = workflow.compile()
    
    // 2. Stream State Updates
    val config = GraphConfig(maxIterations = 10)
    app.stream(AgentState(input = "Kotlin Orchestration"), config).collect { state ->
        println("Current messages: ${state.messages}")
    }
}
```

## Advanced Usage

### Checkpointing (Human-in-the-loop)

You can pause the graph's execution, persist the state, and resume it later (e.g. after a user clicks "Approve").

```kotlin
// 1. Configure the checkpointer
val checkpointer = FileCheckpointer(File("checkpoints"), MySerializer)
val config = GraphConfig(
    threadId = "conversation-123",
    checkpointer = checkpointer,
    interruptBefore = listOf("human_approval") // Pause BEFORE this node
)

// 2. Run until interrupt
val pausedState = app.invoke(initialState, config)

// 3. User approves in the UI, resume the graph!
val finalState = app.invoke(pausedState, config, resume = true)
```

*Note: For Android developers, implementing a `RoomCheckpointer` is highly recommended for offline persistence. Refer to our integration guide.*

### Parallel Execution (Fan-out / Fan-in)

Execute nodes simultaneously and merge their results safely using a `Reducer`.

```kotlin
val workflow = StateGraph<AgentState> {
    node("branch_a") { it.copy(messages = listOf("A")) }
    node("branch_b") { it.copy(messages = listOf("B")) }
    
    edge(START, "branch_a")
    edge(START, "branch_b")
}

// Custom logic to merge parallel updates
val listReducer = Reducer<AgentState> { original, updates ->
    val newMessages = updates.flatMap { it.messages }
    original.copy(messages = original.messages + newMessages)
}

val app = workflow.compile(reducer = listReducer)
```

### LangChain4j Integration

Use the `langgraph-kt-langchain4j` module to safely wrap blocking LLM calls.

```kotlin
import org.langgraphkt.langchain4j.generateNode

node("llm_agent", generateNode(
    model = myChatLanguageModel,
    promptBuilder = { state -> "Respond to: ${state.input}" },
    stateUpdater = { state, response -> state.copy(messages = state.messages + response) }
))
```

## Contributing

Contributions are welcome. Please read our `CONTRIBUTING.md` for details on our core architectural rules:
1. Immutability is Law
2. Native Coroutines Only
3. Type-Safe DSL

## License

This project is licensed under the Apache License 2.0.