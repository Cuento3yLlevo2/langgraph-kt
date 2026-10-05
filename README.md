# langgraph-kt

[![CI](https://github.com/Cuento3yLlevo2/langgraph-kt/actions/workflows/ci.yml/badge.svg)](https://github.com/Cuento3yLlevo2/langgraph-kt/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.cuento3yllevo2/langgraph-kt-core?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.cuento3yllevo2/langgraph-kt-core)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/kotlin-2.4-7F52FF.svg?logo=kotlin)](https://kotlinlang.org)

langgraph-kt is a Kotlin Multiplatform library for building AI agents and other multi-step
workflows. You describe the work as a **graph**: a few small steps, and arrows that say which step
comes next. The library runs it, and takes care of loops, steps that run at the same time, live
progress, and pausing until a person approves. A model that calls your functions as tools is one
such graph, and it comes [ready-made](#agents-with-tools) for every platform.

**[Try it in your browser](https://cuento3yllevo2.github.io/langgraph-kt-demo/):** Pixel Pizza is a
small game in which every stage runs a langgraph-kt graph, from two nodes in a row to a full agent
workflow. No account and no API key needed.

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
    // `targets` lists every node it may pick, so compile() can check that no node is left out.
    conditionalEdge(classify, targets = setOf(refund, technical, escalate)) { email ->
        when (email.category) {
            Category.REFUND -> refund        // return the node to run next
            Category.TECHNICAL -> technical
            else -> escalate
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
    implementation("io.github.cuento3yllevo2:langgraph-kt-core:0.1.0-alpha04")

    // Optional modules. Add only the ones you use.
    implementation("io.github.cuento3yllevo2:langgraph-kt-serialization:0.1.0-alpha04")   // save @Serializable states
    implementation("io.github.cuento3yllevo2:langgraph-kt-checkpoint-file:0.1.0-alpha04") // save runs as JSON files
    implementation("io.github.cuento3yllevo2:langgraph-kt-agent:0.1.0-alpha04")           // chat models, tools and the tool-calling agent
    implementation("io.github.cuento3yllevo2:langgraph-kt-anthropic:0.1.0-alpha04")       // call Claude, on every platform
    implementation("io.github.cuento3yllevo2:langgraph-kt-langchain4j:0.1.0-alpha04")     // call AI models through LangChain4j (JVM)
}
```

| Module | Targets | Purpose |
|---|---|---|
| `langgraph-kt-core` | JVM/Android, iOS, macOS, Linux, Windows, JS, Wasm | Graph builder, execution engine, checkpointing interfaces |
| `langgraph-kt-serialization` | same as core | `KotlinxStateSerializer` for `@Serializable` states, `CheckpointCodec` for custom checkpointers |
| `langgraph-kt-checkpoint-file` | same as core (Node.js only for JS/Wasm) | `FileCheckpointer`, one JSON file per thread |
| `langgraph-kt-agent` | same as core | `ChatModel`, `Tool`, and the tool-calling agent: `toolAgent` / `toolLoop` |
| `langgraph-kt-anthropic` | same as core | `AnthropicChatModel`, Claude through Ktor |
| `langgraph-kt-langchain4j` | JVM (Java 17+) | `LangChain4jChatModel` and `chatNode` / `chatMessagesNode` for LangChain4j 1.x models |

Requires Kotlin 2.x. JVM artifacts target Java 11, except `langgraph-kt-langchain4j`, which needs
Java 17 because LangChain4j does.

### Status

Alpha. `0.1.0-alpha04` is the latest release, and it is on Maven Central. The API may still change
before `1.0`; the [changelog](CHANGELOG.md) lists what changes in each version.

## Tutorial

New to agent workflows, or to graphs? The [tutorial](docs/README.md) starts from zero and adds one
idea per level, like a game: a node, a choice, a loop, parallel work, pausing for a human, an agent
with tools. Every level is a small program you can run, for example `./gradlew :samples:runLevel1`.
The [cheat sheet](docs/cheat-sheet.md) has every term and every call on one page.

## Guides

Each guide is the short version of one feature. The tutorial explains the same features slowly.

| I want to | Guide |
|---|---|
| Show progress while a graph runs | [Streaming](#streaming) |
| Wait for a person to approve something | [Human-in-the-loop](#human-in-the-loop) |
| Run several steps at the same time | [Parallel branches](#parallel-branches) |
| Repeat a step until the result is good | [Loops](#loops) |
| Let an AI model do the work of a node | [AI models](#ai-models) |
| Let an AI model call my functions | [Agents with tools](#agents-with-tools) |
| Draw a graph or test its shape | [Inspecting a graph](#inspecting-a-graph) |

### Streaming

`invoke()` returns when the run is over. `stream()` runs the same graph and returns a `Flow` that
reports what happens while it runs. With the `graph` of the quick start:

```kotlin
graph.stream(SupportEmail(sender = "Ana", body = "I would like a refund.")).collect { event ->
    when (event) {
        // A node is about to run. A UI can show a spinner next to it.
        is GraphEvent.NodeStarted -> println("${event.node} started")
        // A running node reported something with reportProgress(). See below.
        is GraphEvent.NodeProgress -> println("${event.node} reports ${event.value}")
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

A node can report what it is doing while it runs. `reportProgress(value)` sends any value to the
stream, where it arrives as a `GraphEvent.NodeProgress` before the node finishes:

```kotlin
val download = node("download", work = { order ->
    order.files.forEachIndexed { index, file ->
        fetch(file)
        reportProgress("${index + 1} of ${order.files.size}") // event.value in the stream
    }
}) { order, _ -> order.copy(downloaded = true) }
```

- The call returns when the collector has handled the event, so a node cannot run ahead of a slow
  screen.
- With `invoke()` and `resume()` nobody collects, and the call does nothing.
- Progress is not part of the state and is not saved in a checkpoint.

The agent of `langgraph-kt-agent` uses this to show a model's answer while the model writes it; see
[Agents with tools](#agents-with-tools).

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
    conditionalEdge(review, targets = setOf(publish, draft)) { state ->
        if (state.approved) publish else draft
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

When several edges leave the same place, their target nodes run at the same time. Write such a node
in two parts: `work` is the slow part, for example a call to a model, and returns a result. The
block after it is the node's `update`, which writes that result into the state.

```kotlin
data class ResearchState(
    val question: String,
    val findings: List<String> = emptyList(),
    val summary: String = "",
)

val graph = StateGraph<ResearchState> {
    // The `work` of both nodes runs at the same time. Their updates are then applied one after the other.
    val web = node("web", work = { searchWeb(it.question) }) { state, found ->
        state.copy(findings = state.findings + found)
    }
    val docs = node("docs", work = { searchDocs(it.question) }) { state, found ->
        state.copy(findings = state.findings + found)
    }
    val summarize = node("summarize") { it.copy(summary = summarize(it.findings)) }

    // Two edges leave START, so "web" and "docs" run at the same time.
    START then web then summarize
    START then docs then summarize
    // "summarize" runs once, after both have finished, with the findings of both.
    summarize then END
}.compile()
```

Runnable version: [`ParallelResearch`](samples/src/main/kotlin/org/langgraphkt/samples/ParallelResearch.kt).

- The updates are applied in the order the nodes were added to the graph (`web`, then `docs`), each
  to the state that the previous one produced. If two nodes write the same property, the one added
  later wins.
- `update` only builds the new state, and the engine may call it more than once. Keep slow calls
  and side effects in `work`.
- If one branch fails, the others are cancelled and the error is rethrown as
  `NodeExecutionException` with the name of the failing node.

#### Merging whole states

A node written as `node(name) { ... }` returns a whole state. One such node can share a step with
the nodes above, but when two of them run in the same step there are two states and the run can
continue with only one. `compile()` then asks for a `Reducer`, which makes one state out of them:

```kotlin
data class Pitch(val product: String, val text: String = "")

// `current` is the state before the step. `updates` has one state per node that returned a whole state.
// Two writers each return a complete pitch, and the shorter one is kept.
val keepShortest = Reducer<Pitch> { current, updates -> updates.minBy { it.text.length } }

val graph = StateGraph<Pitch> {
    val formal = node("formal") { it.copy(text = writeFormally(it.product)) }
    val casual = node("casual") { it.copy(text = writeCasually(it.product)) }

    START then formal then END
    START then casual then END
}.compile(reducer = keepShortest) // without a reducer, compile() rejects this graph
```

A reducer has to carry over everything it wants to keep from `updates`, so prefer `work` and
`update` when nodes add to the state, and use a reducer when the step has to choose or compare.

### Loops

A loop is a conditional edge that can point back to an earlier node. Keep a counter in the state so
the loop always ends:

```kotlin
data class Draft(val text: String = "", val attempts: Int = 0)

val graph = StateGraph<Draft> {
    val write = node("write") { it.copy(text = improve(it.text), attempts = it.attempts + 1) }

    START then write
    // Good enough, or tried three times: finish. Otherwise run "write" again.
    conditionalEdge(write, targets = setOf(write, NodeRef.END)) { draft ->
        if (isGood(draft.text) || draft.attempts >= 3) NodeRef.END else write
    }
}.compile()
```

`NodeRef.END` is `END` as a node reference, which is what a conditional edge returns. A conditional
edge can also work with node names, for a target that is computed from data:
`conditionalEdge("write", targets = setOf("write", END)) { ... }`.

As a safety net, a run that takes more than `GraphConfig.maxIterations` steps (25 by default) stops
with `MaxIterationsExceededException`.

### AI models

A node is a `suspend` function, so it can call any AI model with any client library:

```kotlin
val classify = node("classify") { email -> email.copy(category = askMyModel(email.body)) }
```

`langgraph-kt-agent` has a small interface for the model, `ChatModel`, so that the same graph works
with any provider and on every platform. Pick an implementation:

```kotlin
// Claude, on every platform (langgraph-kt-anthropic). HttpClient is the Ktor client.
val model: ChatModel = AnthropicChatModel(HttpClient(), apiKey = key, model = "claude-opus-5-5")

// Any LangChain4j model, on the JVM (langgraph-kt-langchain4j): OpenAI, Gemini, Ollama, ...
val model: ChatModel = LangChain4jChatModel(OpenAiChatModel.builder().apiKey(key).modelName("gpt-5").build())

// In a test, a lambda.
val model = ChatModel { request -> ChatResponse(ChatMessage.Assistant("Thanks for your email!")) }
```

A node that needs one piece of text from the model asks for it with `chat`:

```kotlin
val answer = node(
    "answer",
    // The slow call. It can run next to other nodes, see "Parallel branches".
    work = { email -> model.chat("Write a short, friendly reply to this support email: ${email.body}") },
) { email, reply -> email.copy(reply = reply) } // Puts the model's answer into the state.
```

`ChatModel` has one function, `chat(ChatRequest): ChatResponse`, so a model of your own is a few
lines. A failed call throws `ChatModelException`. When the call is made in a node, the run fails
with a `NodeExecutionException` that names the node and has the `ChatModelException` as its `cause`.

To show an answer while the model writes it, `model.stream(request)` returns a `Flow` with a
`ChatEvent.TextDelta` for each piece of text and a final `ChatEvent.Completed` with the whole
answer. In a node, call `chatWithProgress` in place of `chat`, and the pieces arrive in the stream
of the run:

```kotlin
val answer = node(
    "answer",
    work = { email -> model.chatWithProgress("Write a short, friendly reply to this support email: ${email.body}") },
) { email, reply -> email.copy(reply = reply) }

graph.stream(email).collect { event ->
    event.textDelta?.let { piece -> print(piece) } // null for every other event
}
```

`AnthropicChatModel` streams on every platform. `LangChain4jChatModel` streams when you give it a
LangChain4j streaming model as well: `LangChain4jChatModel(openAi, streamingModel)`. A model that
cannot stream delivers its text in one piece, so the same code works with every model.

On the JVM, `langgraph-kt-langchain4j` also builds a node straight from a
[LangChain4j](https://docs.langchain4j.dev) model with `chatNode` (one text in, one text out) and
`chatMessagesNode` (a list of LangChain4j messages). Both run the blocking call on `Dispatchers.IO`.
The [`ChatAgent`](samples/src/main/kotlin/org/langgraphkt/samples/ChatAgent.kt) sample uses them.

### Agents with tools

An agent is a model that decides by itself which of your functions to call, and how often, before
it answers. In a graph that is a loop of two nodes: the model answers or asks for tools, the tools
run, and their results go back to the model. `langgraph-kt-agent` has this loop ready-made.

A **tool** is a function with a name and a description that the model reads. Its input is a
`@Serializable` class, from which the library builds the schema the model needs:

```kotlin
@Serializable
data class MenuLookup(
    @Description("The item, for example \"margherita\"") val item: String,
)

val menuPrice = Tool<MenuLookup>("menu_price", "Returns the price of one item on the menu.") { lookup ->
    // Whatever your app does: a database, an HTTP call, a calculation. The model gets the text you return.
    val price = menu[lookup.item] ?: throw IllegalArgumentException("We do not sell ${lookup.item}.")
    "One ${lookup.item} costs $price euros."
}
```

`toolAgent` returns a graph like any other, so `invoke`, `stream`, checkpoints and pauses all work:

```kotlin
val agent = toolAgent(model, tools = listOf(menuPrice, orderStatus), system = "You work at the help desk of a pizzeria.")

val first = agent.invoke(AgentState("How much is a margherita?")).state
println(first.answer) // A margherita costs 9 euros.

// The state holds the conversation. Add the next message to continue it.
val second = agent.invoke(first.withUserMessage("And a cola?")).state
```

`invoke` starts a new run each time, so a chat passes the conversation so far as its input. With a
checkpointer, `lastResult` gives the state the previous turn ended with:

```kotlin
suspend fun send(question: String): AgentState {
    // The conversation of the previous turns, or an empty one on the first turn.
    val history = agent.lastResult(config)?.state ?: AgentState()
    return agent.invoke(history.withUserMessage(question), config).state
}
```

To show the answer while the model writes it, collect the run with `stream`. The model node
reports each piece of text, and `textDelta` reads it from the event:

```kotlin
agent.stream(AgentState("How much is a margherita?")).collect { event ->
    event.textDelta?.let { piece -> print(piece) }                // A, margherita, costs, ...
    if (event is GraphEvent.Completed) println()                  // event.state.answer is the whole text
}
```

What to know:

- **The model streams only when someone watches.** A run started with `stream` asks the model with
  `ChatModel.stream`; a run started with `invoke` asks for the whole answer at once.
- **Text before a failure is not an answer.** When a model call fails or the model declines in the
  middle of its answer, the run fails after the pieces that already arrived. Clear them.
- **Tools of one answer run at the same time.** If a tool throws, or the model sends input that does
  not fit, the run goes on: the model gets the error as the result and can try again.
- **Every round of tools is two steps.** Raise `GraphConfig.maxIterations` (25 by default) for an
  agent that needs more than twelve rounds.
- **An answer can be cut off.** When the model reaches its output limit in the text of its answer,
  the run ends with what it wrote, and `state.answerTruncated` is `true` (`truncated` on the
  `ChatMessage.Assistant`). When it reaches the limit in a tool call, the run fails.
- **`AgentState` is `@Serializable`**, so `KotlinxStateSerializer` and `FileCheckpointer` can save it.

To let a person approve the tool calls, pause before the node that runs them. It is named `tools`:

```kotlin
val config = GraphConfig(threadId = "ticket-42", checkpointer = MemoryCheckpointer<AgentState>(), interruptBefore = setOf("tools"))

val paused = agent.invoke(AgentState("Refund my last order"), config)
// The calls that wait for a yes: their names and inputs.
val waiting = paused.state.messages.pendingToolCalls()

// Yes: run them.
agent.resume(config)
// No: answer the call yourself. A call that already has a result is not run.
agent.resume(config) { state ->
    state.copy(messages = state.messages + waiting.map { ChatMessage.ToolResult(it.id, it.name, "The reviewer said no.", isError = true) })
}
```

`toolAgent` is a whole graph. To make the agent one part of a larger graph, with the conversation
in a state of your own, add the same loop with `toolLoop`:

```kotlin
data class Ticket(val messages: List<ChatMessage>, val reply: String = "")

val graph = StateGraph<Ticket> {
    val send = node("send") { it.copy(reply = it.messages.last().text) }
    val agent = toolLoop(
        model = model,
        tools = listOf(menuPrice, orderStatus),
        // Where the conversation is in your state, and how to add messages to it.
        messages = { it.messages },
        append = { ticket, new -> ticket.copy(messages = ticket.messages + new) },
        // Where the graph goes when the model has its answer. The default is END.
        then = send,
    )

    START then agent
    send then END
}.compile()
```

`messages` must return what `append` stored. When the conversation starts from other fields of your
state, give the first message to `firstMessage` instead of building it in `messages`. The loop uses
it while the conversation is empty and stores it with the model's first answer:

```kotlin
data class Order(val customer: String, val question: String, val messages: List<ChatMessage> = emptyList())

toolLoop(
    model = model,
    tools = listOf(menuPrice, orderStatus),
    messages = { it.messages },
    append = { order, new -> order.copy(messages = order.messages + new) },
    firstMessage = { "${it.customer} writes: ${it.question}" },
)
```

Runnable version: [`ToolAgent`](samples/src/main/kotlin/org/langgraphkt/samples/ToolAgent.kt). It
runs without an API key, and with Claude when `ANTHROPIC_API_KEY` is set.
[Level 9 of the tutorial](docs/09-an-agent-with-tools.md) explains the same agent step by step.

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

Mistakes in the graph itself (an unknown node name, a node nothing leads to, two nodes that return
a whole state in the same step without a reducer) are reported by `compile()`, before anything runs. Everything the library throws
extends `LangGraphException`:

| Exception | When |
|---|---|
| `GraphValidationException` | The graph or `GraphConfig` is invalid. Thrown by `compile()` or when a run starts. |
| `NodeExecutionException` | A node threw, or a `withTimeout` inside it expired. `nodeName` and the original `cause` are available. Every exception is wrapped, also one of this table that a graph inside the node threw. |
| `EdgeConditionException` | The function of a conditional edge threw. `from` and the original `cause` are available. |
| `ReducerException` | The reducer threw. `nodes` (the nodes whose states it was merging) and the original `cause` are available. |
| `InvalidRouteException` | A conditional edge returned a node that does not exist or is not a declared target. |
| `MaxIterationsExceededException` | The run took more steps than `GraphConfig.maxIterations` (default 25). |
| `CheckpointNotFoundException`, `GraphAlreadyCompletedException` | `resume` had nothing to continue. |
| `CheckpointCorruptedException` | A stored checkpoint could not be read. |
| `ChatModelException` | A call to a `ChatModel` failed, or the model declined to answer. From `langgraph-kt-agent`. A run reports it as the `cause` of a `NodeExecutionException`. |

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
./gradlew :samples:runToolAgent         # an agent that calls tools, with or without an API key
./gradlew :samples:runLevel1            # ... runLevel11, the levels of the tutorial
```

For a complete app, see [langgraph-kt-demo](https://github.com/Cuento3yLlevo2/langgraph-kt-demo),
the source of the [Pixel Pizza game](https://cuento3yllevo2.github.io/langgraph-kt-demo/). It is a
Compose Multiplatform app for the browser (Kotlin/Wasm), the desktop and Android, and it uses this
library from Maven Central.

## Contributing

Contributions are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for the development setup and the
three design rules (immutable state, coroutines only, type-safe DSL).

## License

[Apache License 2.0](LICENSE)
