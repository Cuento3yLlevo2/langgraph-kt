# Level 5: Doing two things at once

**Goal:** to answer "Where is my pizza?", the help desk asks the kitchen and the driver at the same
time instead of one after the other.

**New moves:** fan-out (several arrows from one place), `Reducer`.

## The map

```mermaid
flowchart LR
    S([START]) --> kitchen --> answer
    S --> driver --> answer
    answer --> E([END])
```

Two arrows leave `START`. Both are ordinary arrows, so both are followed.

## The code

[`level5/Level5.kt`](../samples/src/main/kotlin/org/langgraphkt/samples/tutorial/level5/Level5.kt)

```kotlin
data class Ticket(
    val customer: String,
    val message: String,
    val facts: List<String> = emptyList(),
    val reply: String = "",
)

/** Merges the copies returned by nodes that ran at the same time: keep every fact, once. */
val collectFacts = Reducer<Ticket> { current, updates -> current.copy(facts = updates.flatMap { it.facts }.distinct()) }

fun helpDesk(lookupMillis: Long = 1_000): CompiledGraph<Ticket> =
    StateGraph<Ticket> {
        val kitchen =
            node("kitchen") { ticket ->
                delay(lookupMillis)
                ticket.copy(facts = ticket.facts + "your pizza left the oven")
            }
        val driver =
            node("driver") { ticket ->
                delay(lookupMillis)
                ticket.copy(facts = ticket.facts + "the driver is 5 minutes away")
            }
        val answer = node("answer") { ticket -> ticket.copy(reply = "Hi ${ticket.customer}, ${ticket.facts.joinToString(" and ")}.") }

        START then kitchen then answer
        START then driver then answer
        answer then END
    }.compile(reducer = collectFacts)

suspend fun main() {
    val (result, time) = measureTimedValue { helpDesk().invoke(Ticket(customer = "Ana", message = "Where is my pizza?")) }
    println(result.state.reply)
    println("Two lookups of one second each took ${time.inWholeMilliseconds} ms.")
}
```

`delay(lookupMillis)` makes each lookup wait one second, the way a real call to another system
would.

## Run it

```bash
./gradlew :samples:runLevel5
```

```
Hi Ana, your pizza left the oven and the driver is 5 minutes away.
Two lookups of one second each took 1009 ms.
```

The number of milliseconds varies a little. What matters is that it is about one second, not two.

## What happened

When several ordinary arrows leave the same place, the engine runs all the nodes they point to **in
the same step, at the same time**. This is called *fan-out*.

That raises a question that did not exist before. Both nodes received the same ticket, with no
facts, and each returned its own copy:

- `kitchen` returned a ticket with the fact "your pizza left the oven".
- `driver` returned a ticket with the fact "the driver is 5 minutes away".

There are now two tickets and the run can only continue with one. The library cannot guess how to
combine them, so you tell it with a **`Reducer`**: a function that receives the state before the
step (`current`) and the list of copies the nodes returned (`updates`), and returns the one state
to continue with.

```kotlin
val collectFacts = Reducer<Ticket> { current, updates ->
    current.copy(facts = updates.flatMap { it.facts }.distinct())
}
```

In words: take the ticket as it was, and set its facts to all the facts from all the copies,
without repeats. You pass the reducer to `compile(reducer = ...)`.

Three things to know:

- **A reducer keeps only what you tell it to keep.** `collectFacts` merges `facts` and nothing
  else. If `kitchen` also changed `reply`, that change would be lost, because the reducer starts
  from `current`. Merge every field that parallel nodes write.
- **A graph that fans out needs a reducer.** Without one, `compile()` refuses the graph. You will
  see that message in level 9.
- **The reducer is only used when a step ran more than one node.** In all the other steps there is
  one result and nothing to merge.

And `answer`? Two arrows lead to it, but they arrive in the same step, so it runs once, with the
merged ticket.

## Your turn

1. Add a third lookup, `weather`, that adds the fact "it is raining". Connect it like the other
   two. The reply now has three facts, and the time is still about one second.
2. Make `driver` wait three times as long (`delay(lookupMillis * 3)`). How long does the run take
   now? A step is finished when its slowest node is finished.

## Level complete

You can now:

- run nodes at the same time by drawing several arrows from one place,
- write a reducer that merges their results,
- explain why parallel nodes need immutable state (each gets its own copy, level 2).

[Back to level 4](04-loops.md) · [All levels](README.md) · Next: [Level 6, watching a run](06-watching-a-run.md)
