# Level 6: Watching a run

**Goal:** see what the help desk is doing while it works, instead of waiting for the final result.

**New moves:** `stream`, `GraphEvent`, `states`.

A run with real AI models can take many seconds. A person looking at an app wants to see "asking
the kitchen...", not a frozen screen. This level uses the graph from level 5 and changes only how
you run it.

## The code

[`level6/Level6.kt`](../samples/src/main/kotlin/org/langgraphkt/samples/tutorial/level6/Level6.kt)

```kotlin
fun describe(event: GraphEvent<Ticket>): String =
    when (event) {
        is GraphEvent.NodeStarted -> "step ${event.step}: ${event.node} started"
        is GraphEvent.NodeCompleted -> "step ${event.step}: ${event.node} finished"
        is GraphEvent.StepCompleted -> "step ${event.step} done, facts so far: ${event.state.facts.size}"
        is GraphEvent.Completed -> "finished: ${event.state.reply}"
        is GraphEvent.Interrupted -> "paused before ${event.nextNodes}"
    }

suspend fun main() {
    helpDesk().stream(Ticket(customer = "Ana", message = "Where is my pizza?")).collect { event ->
        println(describe(event))
    }
}
```

## Run it

```bash
./gradlew :samples:runLevel6
```

```
step 1: kitchen started
step 1: driver started
step 1: kitchen finished
step 1: driver finished
step 1 done, facts so far: 2
step 2: answer started
step 2: answer finished
step 2 done, facts so far: 2
finished: Hi Ana, your pizza left the oven and the driver is 5 minutes away.
```

The lines appear one by one as things happen. `kitchen finished` and `driver finished` may swap
places, because the two nodes finish at almost the same moment.

## What happened

`invoke` plays the whole run and tells you the result. `stream` plays the same run and tells you
about everything on the way. It returns a Kotlin `Flow`, which is a sequence of values that arrive
over time. `collect { ... }` runs your code for each one.

The values are **events**. There are five kinds:

| Event | When | What it carries |
|---|---|---|
| `NodeStarted` | A node is about to run | `step`, `node`, the state the node receives |
| `NodeCompleted` | A node finished | `step`, `node`, the state the node returned |
| `StepCompleted` | All nodes of a step finished | `step`, `nodes`, the state after the step (after the reducer, if several nodes ran) |
| `Completed` | The run reached `END` | The final state. Always the last event. |
| `Interrupted` | The run paused | The state and the nodes that are next. Level 7 explains pausing. |

Every stream ends with exactly one `Completed` or `Interrupted`.

In the output you can see level 5 at work: both lookups start before either finishes, and they
share step 1.

Because `when` covers all five kinds, the Kotlin compiler would complain if you forgot one. That is
why `describe` handles `Interrupted` even though this graph never pauses.

### Only the state, please

Often a screen just wants to show the newest state. `states()` turns the events into the state
after each step:

```kotlin
helpDesk().stream(ticket).states().collect { ticket -> println(ticket.facts) }
```

It needs `import org.langgraphkt.states`.

### Nothing runs until you collect

Calling `stream(...)` does not start anything. The run starts when you `collect`, and collecting
twice runs the graph twice.

## Your turn

1. Change `main` to use `states()` and print the ticket's facts after every step. You should see
   two lines.
2. Print the `StepCompleted` line with the names of the nodes that ran. They are in `event.nodes`.

## Level complete

You can now:

- follow a run live with `stream`,
- name the five events and what each one tells you,
- get just the states with `states()`.

[Back to level 5](05-parallel.md) · [All levels](README.md) · Next: [Level 7, save points](07-save-points.md)
