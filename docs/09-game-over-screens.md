# Level 9: Game over screens

**Goal:** recognize what the library tells you when something is wrong, and continue a run after a
node has failed.

**New moves:** the library's exceptions, retry with `resume`.

Every game has a game over screen. A good one tells you what went wrong so that you can try again.
This level makes five mistakes on purpose.

## Run it

[`level9/Level9.kt`](../samples/src/main/kotlin/org/langgraphkt/samples/tutorial/level9/Level9.kt)

```bash
./gradlew :samples:runLevel9
```

```
GraphValidationException: Edge references unknown to-node: anwser
GraphValidationException: Node 'check' is not reachable from START
GraphValidationException: Node 'read' fans out to several nodes, so compile() needs a Reducer to merge their results
MaxIterationsExceededException: Graph execution exceeded max iterations (25). Possible infinite loop.
NodeExecutionException: Node 'kitchen' failed: the kitchen phone is busy
After a retry: Hi Ana! Your pizza is in the oven.
```

Each line is one mistake. There are two kinds: mistakes in the map, which are found before the game
starts, and things that go wrong during a run.

## Mistakes in the map

These are found by `compile()`. Nothing has run yet, no model was called, no customer got a strange
answer. The exception is always `GraphValidationException`, and its message says what to fix.

### 1. A misspelled name

```kotlin
StateGraph<Ticket> {
    node("answer") { ticket -> ticket.copy(reply = "Hello!") }

    edge(START, "anwser")
}.compile()
```

```
Edge references unknown to-node: anwser
```

`edge(from, to)` draws an arrow using names, and here the name has a typo. This is why the other
levels keep the handle that `node` returns and write `START then answer`: with a handle, a typo is
caught by the Kotlin compiler while you type.

### 2. A node nothing leads to

```kotlin
StateGraph<Ticket> {
    val answer = node("answer") { ticket -> ticket.copy(reply = "Hello!") }
    node("check") { ticket -> ticket }

    START then answer then END
}.compile()
```

```
Node 'check' is not reachable from START
```

You added a node and forgot its arrow. It could never run, which is never what you meant.

### 3. Parallel nodes without a reducer

```kotlin
StateGraph<Ticket> {
    val read = node("read") { ticket -> ticket }
    val kitchen = node("kitchen") { ticket -> ticket }
    val driver = node("driver") { ticket -> ticket }

    START then read
    read then kitchen then END
    read then driver then END
}.compile()
```

```
Node 'read' fans out to several nodes, so compile() needs a Reducer to merge their results
```

Two arrows leave `read`, so `kitchen` and `driver` run at the same time, and nothing says how to
merge their results. Pass a reducer to `compile` (level 5).

If a map has several mistakes, the message lists all of them at once.

## Things that go wrong during a run

### 4. A loop with no way out

```kotlin
StateGraph<Ticket> {
    val write = node("write") { ticket -> ticket.copy(reply = ticket.reply + "!") }

    START then write then write
}.compile()
```

```
Graph execution exceeded max iterations (25). Possible infinite loop.
```

`write then write` is a valid map, so it compiles. The run goes round until the step limit stops it
with `MaxIterationsExceededException`. Give the loop a way to reach `END` (level 4).

### 5. A node that fails

```kotlin
fun helpDesk(callKitchen: suspend () -> String): CompiledGraph<Ticket> =
    StateGraph<Ticket> {
        val greet = node("greet") { ticket -> ticket.copy(reply = "Hi ${ticket.customer}!") }
        val kitchen = node("kitchen") { ticket -> ticket.copy(reply = "${ticket.reply} ${callKitchen()}") }

        START then greet then kitchen then END
    }.compile()
```

```kotlin
var calls = 0
val graph = helpDesk { if (++calls == 1) error("the kitchen phone is busy") else "Your pizza is in the oven." }
val config = GraphConfig(threadId = "ticket-42", checkpointer = MemoryCheckpointer<Ticket>())
try {
    graph.invoke(ticket, config)
} catch (e: NodeExecutionException) {
    println("${e::class.simpleName}: ${e.message}")
}
println("After a retry: ${graph.resume(config).state.reply}")
```

```
NodeExecutionException: Node 'kitchen' failed: the kitchen phone is busy
After a retry: Hi Ana! Your pizza is in the oven.
```

The phone call fails the first time and works the second time, like a network call that sometimes
fails. When a node throws an exception, the run stops and you get a `NodeExecutionException`. It
tells you which node failed (`e.nodeName`) and why (`e.cause` is the original exception).

Now the save points of level 7 pay off. The run had a checkpointer, and a save is written after
every step, so the result of `greet` was already saved when `kitchen` failed. `resume(config)`
continues from that save: it runs `kitchen` again and does **not** run `greet` again. In a real
workflow, that means the three model calls that already succeeded are not paid for twice.

Without a checkpointer there is no save, and the only way to try again is `invoke`, from the start.

## All the game over screens

Every exception the library throws is a `LangGraphException`, so one `catch` can handle them all.

| Exception | Meaning | What to do |
|---|---|---|
| `GraphValidationException` | The map or the run's settings are wrong. Thrown by `compile()` or when a run starts. | Read the message and fix the graph or the `GraphConfig`. |
| `NodeExecutionException` | A node threw an exception. | Look at `nodeName` and `cause`. Retry with `resume` if the cause was temporary. |
| `InvalidRouteException` | A router returned a name that is not a node, or not in its `targets`. | Fix the router or add the name to `targets`. |
| `MaxIterationsExceededException` | The run took more steps than `maxIterations`. | Give the loop an exit, or raise the limit if the run really needs more steps. |
| `CheckpointNotFoundException` | `resume` found no save for this `threadId`. | Start the run with `invoke`. |
| `GraphAlreadyCompletedException` | `resume` found a run that already finished. | Start a new run with `invoke`. |
| `CheckpointCorruptedException` | A stored save cannot be read. | Delete the save and start again. |

## Your turn

1. Fix mistakes 1 to 4 in `Level9.kt` one at a time and watch the lines disappear from the output.
   For number 4, the smallest fix is a conditional edge that returns `END` once the reply has three
   exclamation marks.
2. In mistake 5, remove `checkpointer` from the `GraphConfig`. What does `resume` say now?

## Level complete

You can now:

- read a `GraphValidationException` and fix the map,
- tell mistakes found by `compile()` from failures during a run,
- continue a failed run from its last save.

[Back to level 8](08-a-real-ai-model.md) · [All levels](README.md) · Next: [Level 10, your own workflow](10-your-own-workflow.md)
