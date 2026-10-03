# langgraph-kt

[![CI](https://github.com/Cuento3yLlevo2/langgraph-kt/actions/workflows/ci.yml/badge.svg)](https://github.com/Cuento3yLlevo2/langgraph-kt/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/kotlin-2.4-7F52FF.svg?logo=kotlin)](https://kotlinlang.org)

langgraph-kt is a Kotlin Multiplatform library for building AI agents and other multi-step
workflows. You describe the work as a **graph**: a few small steps, and arrows that say which step
comes next. The library runs it, and takes care of loops, steps that run at the same time, live
progress, and pausing until a person approves.

> langgraph-kt is an independent project inspired by [LangGraph](https://github.com/langchain-ai/langgraph).
> It is not affiliated with or endorsed by LangChain, Inc.

**Contents:** [The idea](#the-idea) · [Quick start](#quick-start) · [Installation](#installation) ·
[Tutorial](#tutorial) · [Guides](#guides) · [Errors](#errors) · [Samples](#samples)

## The idea

There are three words to learn:

| Word | What it is | In the quick start below |
|---|---|---|
| **State** | The data of one job. A `data class` that you design. | A support email: who sent it, what it says, its category, the reply |
| **Node** | One step of the work. A function that receives the state and returns an updated copy. | `classify`, `refund`, `technical`, `escalate` |
| **Edge** | An arrow from one node to the next. A *conditional* edge chooses the next node by looking at the state. | After `classify`, go to the node of the email's category |

Every run begins at `START` and finishes at `END`.

## Quick start

A customer support agent for email. It reads an email, decides which of three categories it belongs
to, and writes the reply for that category:

- **refund**: the customer wants money back,
- **technical**: something does not work,
- **escalation**: the customer is angry, or the email is too complex or unclear for an automatic
  reply, so a person takes over.

```mermaid
flowchart LR
    S([START]) --> classify
    classify -.refund.-> refund --> E([END])
    classify -.technical.-> technical --> E
    classify -.angry or complex.-> escalate --> E
```

```kotlin
import org.langgraphkt.*

enum class Category { REFUND, TECHNICAL, ESCALATION }

// The state: everything the graph knows about one email.
// It is immutable (only `val`): a node never changes it, it returns an updated copy.
data class SupportEmail(
    val sender: String,              // input
    val body: String,                // input
    val category: Category? = null,  // filled in by the "classify" node
    val reply: String = "",          // filled in by one of the reply nodes
)

// Decides what an email is about. A real agent would ask an AI model here (see "AI models" below).
// Plain Kotlin keeps this example runnable without an API key.
fun categoryOf(body: String): Category {
    val text = body.lowercase()
    val angry = listOf("unacceptable", "furious", "worst", "!!").any { it in text }
    val wantsRefund = listOf("refund", "money back").any { it in text }
    val hasProblem = listOf("error", "crash", "not working").any { it in text }

    return when {
        angry -> Category.ESCALATION                      // an upset customer gets a person
        wantsRefund && hasProblem -> Category.ESCALATION  // two requests in one email: too complex
        wantsRefund -> Category.REFUND
        hasProblem -> Category.TECHNICAL
        else -> Category.ESCALATION                       // not sure what this is, so a person reads it
    }
}

// StateGraph<SupportEmail> { ... } describes the graph. The type says which state it works on.
val graph = StateGraph<SupportEmail> {
    // A node is a named step: it receives the state and returns an updated copy.
    // This one fills in `category` and leaves the rest of the email as it is.
    val classify = node("classify") { email -> email.copy(category = categoryOf(email.body)) }

    // One node per category. Each writes the reply; only one of them runs for a given email.
    val refund = node("refund") { email ->
        email.copy(reply = "Hi ${email.sender}, your refund is on its way. It takes 3 to 5 days.")
    }
    val technical = node("technical") { email ->
        email.copy(reply = "Hi ${email.sender}, please update the app and try again. Here is our guide.")
    }
    val escalate = node("escalate") { email ->
        email.copy(reply = "Hi ${email.sender}, a colleague from our team will reply to you personally today.")
    }

    // Every run begins at START. This edge says: run `classify` first.
    START then classify

    // A conditional edge picks the next node by looking at the state.
    // `targets` lists every node it may pick, so a wrong name is found by compile(), not during a run.
    conditionalEdge(classify, targets = setOf(refund.name, technical.name, escalate.name)) { email ->
        when (email.category) {
            Category.REFUND -> refund.name        // return the name of the node to run next
            Category.TECHNICAL -> technical.name
            else -> escalate.name
        }
    }

    // After the reply is written there is nothing left to do, so each path goes to END.
    refund then END
    technical then END
    escalate then END
}.compile() // Checks the graph (unknown names, nodes nothing leads to) and makes it runnable.

suspend fun main() {
    // invoke() runs the graph from START to END with this email as the starting state.
    val result = graph.invoke(SupportEmail(sender = "Ana", body = "I was charged twice, I would like a refund."))

    // result.state is the final state: the email, with `category` and `reply` filled in.
    println("${result.state.category}: ${result.state.reply}")
    // REFUND: Hi Ana, your refund is on its way. It takes 3 to 5 days.
}
```

This is the [`QuickStart`](samples/src/main/kotlin/org/langgraphkt/samples/QuickStart.kt) sample. To
run it from a clone of this repository:

```bash
./gradlew :samples:runQuickStart
```

```
REFUND: Hi Ana, your refund is on its way. It takes 3 to 5 days.
TECHNICAL: Hi Ben, please update the app and try again. Here is our guide.
ESCALATION: Hi Cleo, a colleague from our team will reply to you personally today.
```

## Installation

```kotlin
dependencies {
    // The graph builder and the engine. This is all the quick start needs.
    implementation("io.github.cuento3yllevo2:langgraph-kt-core:0.1.0")

    // Optional modules. Add only the ones you use.
    implementation("io.github.cuento3yllevo2:langgraph-kt-serialization:0.1.0")   // save @Serializable states
    implementation("io.github.cuento3yllevo2:langgraph-kt-checkpoint-file:0.1.0") // save runs as JSON files
    implementation("io.github.cuento3yllevo2:langgraph-kt-langchain4j:0.1.0")     // call AI models through LangChain4j (JVM)
}
```

| Module | Targets | Purpose |
|---|---|---|
| `langgraph-kt-core` | JVM/Android, iOS, macOS, Linux, Windows, JS, Wasm | Graph builder, execution engine, checkpointing interfaces |
| `langgraph-kt-serialization` | same as core | `KotlinxStateSerializer` for `@Serializable` states, `CheckpointCodec` for custom checkpointers |
| `langgraph-kt-checkpoint-file` | same as core (Node.js only for JS/Wasm) | `FileCheckpointer`, one JSON file per thread |
| `langgraph-kt-langchain4j` | JVM (Java 17+) | `chatNode` / `chatMessagesNode` for LangChain4j 1.x `ChatModel` |

Requires Kotlin 2.x. JVM artifacts target Java 11, except `langgraph-kt-langchain4j`, which needs
Java 17 because LangChain4j does.

### Status

Pre-release. The API described here is what `0.1.0` will ship, and it may still change before `1.0`.
Artifacts are not on Maven Central yet; until the first release, build from source with
`./gradlew publishToMavenLocal`.

## Tutorial

New to agent workflows, or to graphs? The [tutorial](docs/README.md) starts from zero and adds one
idea per level, like a game: a node, a choice, a loop, parallel work, pausing for a human. Every
level is a small program you can run, for example `./gradlew :samples:runLevel1`. The
[cheat sheet](docs/cheat-sheet.md) has every term and every call on one page.

## Guides

Each guide is the short version of one feature. The tutorial explains the same features slowly.

| I want to | Guide |
|---|---|
| Show progress while a graph runs | [Streaming](#streaming) |
| Wait for a person to approve something | [Human-in-the-loop](#human-in-the-loop) |
| Run several steps at the same time | [Parallel branches](#parallel-branches) |
| Repeat a step until the result is good | [Loops](#loops) |
| Let an AI model do the work of a node | [AI models](#ai-models) |
| Draw a graph or test its shape | [Inspecting a graph](#inspecting-a-graph) |

### Streaming

`invoke()` returns when the run is over. `stream()` runs the same graph and returns a `Flow` that
reports what happens while it runs. With the `graph` of the quick start:

```kotlin
graph.stream(SupportEmail(sender = "Ana", body = "I would like a refund.")).collect { event ->
    when (event) {
        // A node is about to run. A UI can show a spinner next to it.
        is GraphEvent.NodeStarted -> println("${event.node} started")
        // A node has returned its updated state.
        is GraphEvent.NodeCompleted -> println("${event.node} finished")
        // A step is over: every node that ran at the same time has finished.
        is GraphEvent.StepCompleted -> println("step ${event.step} done, ran ${event.nodes}")
        // Last event of a run that reached END. event.state is the final state.
        is GraphEvent.Completed -> println("done: ${event.state.reply}")
        // Last event of a run that paused for a person (see the next guide).
        is GraphEvent.Interrupted -> println("paused before ${event.nextNodes}")
    }
}
```

```
classify started
classify finished
step 1 done, ran [classify]
refund started
refund finished
step 2 done, ran [refund]
done: Hi Ana, your refund is on its way. It takes 3 to 5 days.
```

For a UI that only renders the latest state, `graph.stream(input).states()` is a `Flow` with just
the state after each step.

### Human-in-the-loop

Some steps should not run until a person has said yes, for example sending money. Tell the run where
to pause and where to save its progress. `invoke` then stops before that node, and `resume`
continues later, even after the app was restarted.

Two terms: a **checkpoint** is a saved run (the state, and which node comes next), and a **thread**
is one job with its own checkpoints, named by its `threadId`.

```kotlin
@Serializable // lets the state be written to a file
data class RefundState(
    val orderId: String,
    val amount: Int,
    val approved: Boolean = false,           // written by the human reviewer
    val log: List<String> = emptyList(),
)

val graph = StateGraph<RefundState> {
    val prepare = node("prepare") { it.copy(log = it.log + "Prepared refund of ${it.amount}") }
    val issue = node("issue_refund") {
        it.copy(log = it.log + if (it.approved) "Refund issued" else "Refund rejected by reviewer")
    }

    START then prepare then issue then END
}.compile()

val config = GraphConfig(
    // Names this job. Each order gets its own saved run.
    threadId = "order-1001",
    // Where the run is saved: one JSON file per thread in the "checkpoints" directory.
    checkpointer = FileCheckpointer(Path("checkpoints"), KotlinxStateSerializer<RefundState>()),
    // Stop before this node runs.
    interruptBefore = setOf("issue_refund"),
)

// Runs "prepare", then stops before "issue_refund" and returns Interrupted.
when (val result = graph.invoke(RefundState(orderId = "1001", amount = 250), config)) {
    is GraphResult.Interrupted -> showApprovalDialog(result.state) // your UI: ask the reviewer
    is GraphResult.Completed -> showResult(result.state)           // not reached here: the run pauses first
}

// Later, possibly after an app restart: write the decision into the state and continue.
// resume() loads the saved run of "order-1001" and runs "issue_refund".
val finished = graph.resume(config) { state -> state.copy(approved = true) }
```

Runnable version: [`HumanInTheLoop`](samples/src/main/kotlin/org/langgraphkt/samples/HumanInTheLoop.kt).

- `invoke` always starts a new run for the thread. `resume` continues the saved one, optionally
  editing the state first.
- A checkpoint is saved after every step, so a run can also be resumed after a crash. Such a
  `resume` still pauses before an `interruptBefore` node; only a run that already paused there
  continues past it.
- A step that fails is not saved, so `resume` runs all of its nodes again, including the ones that
  had already finished. Make side effects such as sending an email safe to repeat.
- `interruptAfter` pauses after a node instead of before it.
- `MemoryCheckpointer` keeps checkpoints in memory, which is what tests want.

#### Approve or send back

To let the reviewer ask for changes, pause before a node that does nothing and put a conditional
edge after it. `resume` writes the decision into the state, and the conditional edge reads it:

```kotlin
data class AnnouncementState(
    val topic: String,
    val draft: String = "",
    val approved: Boolean = false,   // written by the reviewer
    val feedback: String = "",       // written by the reviewer: what to change
    val published: Boolean = false,
)

val graph = StateGraph<AnnouncementState> {
    // Writes a draft, using the reviewer's feedback if there is any, then clears the feedback.
    val draft = node("draft") { it.copy(draft = write(it.topic, it.feedback), feedback = "") }
    // Does nothing. It is the place where the run waits for the reviewer.
    val review = node("review") { it }
    val publish = node("publish") { it.copy(published = true) }

    START then draft then review
    // Approved: publish. Not approved: back to "draft", which makes this a loop.
    conditionalEdge(review, targets = setOf(publish.name, draft.name)) { state ->
        if (state.approved) publish.name else draft.name
    }
    publish then END
}.compile()

// Pause every time the run is about to enter "review", that is, whenever a new draft is ready.
val config = GraphConfig(checkpointer = MemoryCheckpointer<AnnouncementState>(), interruptBefore = setOf("review"))

var result = graph.invoke(AnnouncementState(topic = "the 1.0 release"), config)
// Interrupted means a draft is waiting. Completed means it was published.
while (result is GraphResult.Interrupted) {
    val feedback = askReviewer(result.state.draft)   // your UI; returns "" when the reviewer approves
    result = graph.resume(config) { it.copy(approved = feedback.isEmpty(), feedback = feedback) }
}
```

Runnable version: [`ReviewLoop`](samples/src/main/kotlin/org/langgraphkt/samples/ReviewLoop.kt).

#### Where a thread stands

`lastResult` reads the thread's checkpoint without running anything. It returns the same
`GraphResult` that `invoke` or `resume` returned, so a screen can be restored after a restart with
the code that already handles a result:

```kotlin
when (val result = graph.lastResult(config)) {
    is GraphResult.Interrupted -> showApprovalDialog(result.state) // the run is waiting for a person
    is GraphResult.Completed -> showResult(result.state)           // the run reached END
    null -> showEmptyForm()                                        // this thread has never run
}
```

A run that stopped because a node failed is reported as `Interrupted` as well. The run's input is
saved when it starts and its state after every finished step, so `resume(config)` retries from the
step that failed, even when that was the first one.

#### Storing checkpoints somewhere else

To store checkpoints in a database, in browser `localStorage` or on a server, implement the
three-method `Checkpointer` interface. `CheckpointCodec` turns a checkpoint into a string and back,
so only the storage calls are left to write:

```kotlin
class LocalStorageCheckpointer<State>(private val codec: CheckpointCodec<State>) : Checkpointer<State> {
    // Called after every step. encode() turns the checkpoint into a JSON string.
    override suspend fun save(threadId: String, checkpoint: Checkpoint<State>) =
        localStorage.setItem(threadId, codec.encode(checkpoint))

    // Called by resume() and lastResult(). Returns null if this thread has no saved run.
    override suspend fun load(threadId: String): Checkpoint<State>? =
        localStorage.getItem(threadId)?.let { codec.decode(threadId, it) }

    override suspend fun delete(threadId: String) = localStorage.removeItem(threadId)
}

val checkpointer = LocalStorageCheckpointer(CheckpointCodec<RefundState>())
```

### Parallel branches

When several edges leave the same place, their target nodes run at the same time. Each one returns
its own copy of the state, so the graph needs a `Reducer`: a function that merges those copies into
one.

```kotlin
data class ResearchState(
    val question: String,
    val findings: List<String> = emptyList(),
    val summary: String = "",
)

// `current` is the state before the step. `updates` has one state per node that ran in the step.
// The merged state keeps the old findings and adds what each node found.
val mergeFindings = Reducer<ResearchState> { current, updates ->
    current.copy(findings = current.findings + updates.flatMap { it.findings - current.findings.toSet() })
}

val graph = StateGraph<ResearchState> {
    val web = node("web") { it.copy(findings = it.findings + searchWeb(it.question)) }
    val docs = node("docs") { it.copy(findings = it.findings + searchDocs(it.question)) }
    val summarize = node("summarize") { it.copy(summary = summarize(it.findings)) }

    // Two edges leave START, so "web" and "docs" run at the same time.
    START then web then summarize
    START then docs then summarize
    // "summarize" runs once, after both have finished and the reducer has merged their results.
    summarize then END
}.compile(reducer = mergeFindings) // without a reducer, compile() rejects this graph
```

Runnable version: [`ParallelResearch`](samples/src/main/kotlin/org/langgraphkt/samples/ParallelResearch.kt).

If one branch fails, the others are cancelled and the error is rethrown as `NodeExecutionException`
with the name of the failing node.

### Loops

A loop is a conditional edge that can point back to an earlier node. Keep a counter in the state so
the loop always ends:

```kotlin
data class Draft(val text: String = "", val attempts: Int = 0)

val graph = StateGraph<Draft> {
    val write = node("write") { it.copy(text = improve(it.text), attempts = it.attempts + 1) }

    START then write
    // Good enough, or tried three times: finish. Otherwise run "write" again.
    conditionalEdge(write, targets = setOf(write.name, END)) { draft ->
        if (isGood(draft.text) || draft.attempts >= 3) END else write.name
    }
}.compile()
```

As a safety net, a run that takes more than `GraphConfig.maxIterations` steps (25 by default) stops
with `MaxIterationsExceededException`.

### AI models

A node is a `suspend` function, so it can call any AI model with any client library:

```kotlin
val classify = node("classify") { email -> email.copy(category = askMyModel(email.body)) }
```

On the JVM, `langgraph-kt-langchain4j` builds such a node from any
[LangChain4j](https://docs.langchain4j.dev) `ChatModel`, which covers most model providers:

```kotlin
val answer = node("answer", chatNode(
    // Any LangChain4j ChatModel: Anthropic, OpenAI, Ollama, ...
    model = model,
    // Turns the state into the text sent to the model.
    prompt = { email -> "Write a short, friendly reply to this support email: ${email.body}" },
    // Puts the model's answer into the state.
    update = { email, reply -> email.copy(reply = reply) },
))
```

`chatNode` sends one text and gets one text back. For a conversation, `chatMessagesNode` sends a
list of messages:

```kotlin
data class ChatState(val messages: List<ChatMessage>)

val assistant = node("assistant", chatMessagesNode(
    model = model,
    // The whole conversation so far goes to the model.
    messages = { state -> state.messages },
    // The model's reply is added to the end of the conversation.
    update = { state, response -> state.copy(messages = state.messages + response.aiMessage()) },
))
```

Both run the blocking model call on `Dispatchers.IO`.

`invoke` starts a new run each time, so a chat passes the conversation so far as its input.
`lastResult` gives the state the previous turn ended with:

```kotlin
suspend fun send(question: String): ChatState {
    // The messages of the previous turns, or an empty list on the first turn.
    val history = agent.lastResult(config)?.state?.messages.orEmpty()
    // Run the graph on the history plus the new question.
    return agent.invoke(ChatState(history + UserMessage.from(question)), config).state
}
```

Runnable version: [`ChatAgent`](samples/src/main/kotlin/org/langgraphkt/samples/ChatAgent.kt).

### Inspecting a graph

`CompiledGraph.topology` describes the compiled graph, which is enough to draw it or to check its
shape in a test. For the graph of [Parallel branches](#parallel-branches):

```kotlin
val topology = graph.topology
topology.nodes                 // [web, docs, summarize]
topology.successors(START)     // [web, docs]: what can run after START
topology.edges                 // GraphEdge(from, to, isConditional) for every known arrow
topology.dynamicRoutes         // nodes whose conditional edge declares no targets
```

## Errors

Mistakes in the graph itself (an unknown node name, a node nothing leads to, parallel branches
without a reducer) are reported by `compile()`, before anything runs. Everything the library throws
extends `LangGraphException`:

| Exception | When |
|---|---|
| `GraphValidationException` | The graph or `GraphConfig` is invalid. Thrown by `compile()` or when a run starts. |
| `NodeExecutionException` | A node threw, or a `withTimeout` inside it expired. `nodeName` and the original `cause` are available. |
| `EdgeConditionException` | The function of a conditional edge threw. `from` and the original `cause` are available. |
| `ReducerException` | The reducer threw. `nodes` (the nodes it was merging) and the original `cause` are available. |
| `InvalidRouteException` | A conditional edge returned a node that does not exist or is not a declared target. |
| `MaxIterationsExceededException` | The run took more steps than `GraphConfig.maxIterations` (default 25). |
| `CheckpointNotFoundException`, `GraphAlreadyCompletedException` | `resume` had nothing to continue. |
| `CheckpointCorruptedException` | A stored checkpoint could not be read. |

## Design

- **Coroutines only.** Nodes and routers are `suspend` functions, parallel branches use structured
  concurrency, and cancellation works as you expect.
- **Immutable, typed state.** State is your own `data class`. Nodes return copies, so parallel
  branches cannot race.
- **Multiplatform.** The core depends only on kotlinx-coroutines and runs on JVM, Android, iOS,
  macOS, Linux, Windows, JS and Wasm.

## API reference

The generated API documentation is published at <https://cuento3yllevo2.github.io/langgraph-kt/>.
To build it locally, run `./gradlew dokkaGenerate` and open `build/dokka/html/index.html`.

## Samples

Runnable examples live in [`samples/`](samples/src/main/kotlin/org/langgraphkt/samples):

```bash
./gradlew :samples:runQuickStart        # the email support agent of the quick start
./gradlew :samples:runHumanInTheLoop    # a refund that waits for approval, saved to disk
./gradlew :samples:runReviewLoop        # a reviewer approves a draft or sends it back
./gradlew :samples:runParallelResearch  # three lookups at the same time
./gradlew :samples:runChatAgent         # a chat agent built on a LangChain4j model
./gradlew :samples:runLevel1            # ... runLevel10, the levels of the tutorial
```

## Contributing

Contributions are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for the development setup and the
three design rules (immutable state, coroutines only, type-safe DSL).

## License

[Apache License 2.0](LICENSE)
