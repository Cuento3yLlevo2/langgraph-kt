# Cheat sheet

Every word and every move of the [tutorial](README.md) on one page.

## Words

| Word | Meaning | Level |
|---|---|---|
| Workflow | A job done in several pieces of work, in an order that can depend on the results | [0](00-the-idea.md) |
| Graph | The map of a workflow: its nodes and edges | [0](00-the-idea.md) |
| State | The data of one job, a `data class` you design. It is passed from node to node. | [0](00-the-idea.md) |
| Node | A named function that receives the state and returns an updated copy | [1](01-first-graph.md) |
| Edge | An arrow that says what runs after a node | [1](01-first-graph.md) |
| `START`, `END` | Where every run begins and finishes. They are not nodes you write. | [1](01-first-graph.md) |
| Compile | Check the map and turn it into a graph you can run | [1](01-first-graph.md) |
| Run | One trip through the graph, from `START` until `END` or a pause | [1](01-first-graph.md) |
| Conditional edge | An arrow that picks the next node by looking at the state | [3](03-choices.md) |
| Router | The function of a conditional edge. It returns the name of the next node, or `END`. | [3](03-choices.md) |
| Step | One round of the engine: run the current nodes, then follow their arrows | [4](04-loops.md) |
| Fan-out | Several ordinary arrows from one place. Their nodes run at the same time, in one step. | [5](05-parallel.md) |
| Reducer | The function that merges the states returned by nodes that ran at the same time | [5](05-parallel.md) |
| Event | A message about a run in progress: a node started, a step finished, ... | [6](06-watching-a-run.md) |
| Checkpoint | A save: the state, and which nodes come next | [7](07-save-points.md) |
| Checkpointer | Where checkpoints are stored (memory, files, your own storage) | [7](07-save-points.md) |
| Thread | One job with its own saves, named by a `threadId` | [7](07-save-points.md) |
| Interrupt | A pause before or after a node, usually to wait for a human | [7](07-save-points.md) |
| Human-in-the-loop | A workflow in which a person decides something before it continues | [7](07-save-points.md) |
| Prompt | The text sent to an AI model | [8](08-a-real-ai-model.md) |

## Building a graph

```kotlin
val graph = StateGraph<Ticket> {
    // A node: a name and a function from state to state.
    val read = node("read") { ticket -> ticket.copy(topic = topicOf(ticket.message)) }
    val answer = node("answer") { ticket -> ticket.copy(reply = "...") }
    val refund = node("refund") { ticket -> ticket.copy(reply = "...") }

    // Arrows that are always followed.
    START then read
    answer then END

    // An arrow that decides. The router returns the next node, or NodeRef.END to finish.
    conditionalEdge(read, targets = setOf(answer, refund)) { ticket ->
        if (ticket.topic == "refund") refund else answer
    }
}.compile()
```

| You want | You write |
|---|---|
| A line | `START then a then b then END` |
| A choice | `conditionalEdge(a, targets = setOf(b, c)) { state -> ... }` |
| A loop | A conditional edge whose router can return an earlier node, with a limit |
| Parallel nodes | Several arrows from one place, and `compile(reducer = ...)` |
| An arrow by name | `edge("a", "b")` |
| A choice by name | `conditionalEdge("a", targets = setOf("b", END)) { state -> "b" }` |

## Running a graph

| Call | What it does |
|---|---|
| `graph.invoke(input)` | A new run. Returns when the run finishes or pauses. |
| `graph.invoke(input, config)` | The same, with settings |
| `graph.stream(input, config)` | A new run as a `Flow` of events |
| `graph.stream(input).states()` | Only the state after each step |
| `graph.resume(config) { state -> ... }` | Continue the saved run of a thread, after editing its state |
| `graph.streamResume(config) { ... }` | `resume` as a `Flow` of events |
| `graph.lastResult(config)` | Where a thread stopped, without running anything. `null` if it never ran. |

`invoke`, `resume` and `lastResult` return a `GraphResult`:

```kotlin
when (result) {
    is GraphResult.Completed -> result.state                // reached END
    is GraphResult.Interrupted -> result.nextNodes          // paused; resume() continues
}
```

## Settings of a run

```kotlin
val config = GraphConfig(
    threadId = "ticket-42",                        // the save slot; one per job
    checkpointer = MemoryCheckpointer<Ticket>(),   // where saves are stored
    interruptBefore = setOf("pay"),                // pause before these nodes
    interruptAfter = setOf("draft"),               // pause after these nodes
    maxIterations = 25,                            // step limit of one invoke or resume
)
```

Pauses need a checkpointer.

## Events

| Event | When |
|---|---|
| `GraphEvent.NodeStarted` | A node is about to run |
| `GraphEvent.NodeCompleted` | A node finished |
| `GraphEvent.StepCompleted` | All nodes of a step finished |
| `GraphEvent.Interrupted` | The run paused. Last event. |
| `GraphEvent.Completed` | The run reached `END`. Last event. |

## Rules

1. State is a `data class` with `val` fields. Nodes return `state.copy(...)` and never change the
   state they receive.
2. Nodes do not call each other. They communicate through the state.
3. A router decides and does not change the state.
4. A node has either ordinary arrows or one conditional edge.
5. Every loop needs a limit.
6. A graph that runs nodes in parallel needs a reducer, and the reducer must merge every field
   those nodes write.
7. `invoke` starts over. `resume` continues.

## Errors

All are `LangGraphException`s. [Level 9](09-game-over-screens.md) explains each one.

| Exception | Meaning |
|---|---|
| `GraphValidationException` | The graph or the settings are wrong. Found before anything runs. |
| `NodeExecutionException` | A node threw an exception |
| `EdgeConditionException` | A router threw an exception |
| `ReducerException` | The reducer threw an exception |
| `InvalidRouteException` | A router returned a name that is not allowed |
| `MaxIterationsExceededException` | The run took more steps than `maxIterations` |
| `CheckpointNotFoundException` | `resume` found no save |
| `GraphAlreadyCompletedException` | `resume` found a finished run |
| `CheckpointCorruptedException` | A save cannot be read |
