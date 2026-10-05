# Level 2: Choices

**Goal:** the help desk handles a delivery question, a refund request and everything else in three
different ways.

**New moves:** `conditionalEdge`, `targets`.

## The map

```mermaid
flowchart LR
    S([START]) --> read
    read -.delivery.-> track
    read -.refund.-> refund
    read -.anything else.-> answer
    track --> E([END])
    refund --> E
    answer --> E
```

After `read` the path splits. Only one of the three dotted arrows is taken in a run.

## The code

[`level2/Level2.kt`](../samples/src/main/kotlin/org/langgraphkt/samples/tutorial/level2/Level2.kt)
(`Ticket` and `topicOf` are the same as in level 1)

```kotlin
fun helpDesk(): CompiledGraph<Ticket> =
    StateGraph<Ticket> {
        val read = node("read") { ticket -> ticket.copy(topic = topicOf(ticket.message)) }
        val track = node("track") { ticket -> ticket.copy(reply = "Your pizza left the oven and is on its way.") }
        val refund = node("refund") { ticket -> ticket.copy(reply = "We are sorry. Your money is on its way back.") }
        val answer = node("answer") { ticket -> ticket.copy(reply = "Thanks for your message. A human will reply soon.") }

        START then read
        conditionalEdge(read, targets = setOf(track, refund, answer)) { ticket ->
            when (ticket.topic) {
                "delivery" -> track
                "refund" -> refund
                else -> answer
            }
        }
        track then END
        refund then END
        answer then END
    }.compile()

suspend fun main() {
    val graph = helpDesk()
    for (message in listOf("Where is my pizza?", "I want a refund", "Do you sell salad?")) {
        val result = graph.invoke(Ticket(customer = "Ana", message = message))
        println("$message -> ${result.state.reply}")
    }
}
```

## Run it

```bash
./gradlew :samples:runLevel2
```

```
Where is my pizza? -> Your pizza left the oven and is on its way.
I want a refund -> We are sorry. Your money is on its way back.
Do you sell salad? -> Thanks for your message. A human will reply soon.
```

## What happened

`then` draws an arrow that is always followed. `conditionalEdge` draws an arrow that decides. It
has three parts:

```kotlin
conditionalEdge(read, targets = setOf(track, refund, answer)) { ticket -> ... }
//              ^     ^                                       ^
//              |     the places this arrow may lead to       the function that picks one
//              the node the arrow starts from
```

- **The function** runs after `read` has finished. It receives the state and returns the node to
  run next: one of the handles that `node(...)` gave you. This function is often called a *router*.
  Because it returns a handle and not a text, the compiler catches a misspelled node. To finish the
  run, a router returns `NodeRef.END` (level 3 does that).
- **`targets`** lists every node the router may return. With it, `compile()` checks that every node
  can be reached, and during a run the library stops with a clear error if the router returns
  something that is not on the list.

Two rules:

- The router only decides. It does not change the state. Work that changes the state belongs in a
  node, which is why `read` stores the topic and the router just looks at it.
- A node has either ordinary arrows or one conditional edge, not both.

## Your turn

1. Add a fourth path: when the message contains "menu", go to a new `menu` node that replies with
   today's pizzas. You have to touch four places: `topicOf`, a new node, the router and `targets`.
2. Now remove `menu` from `targets` and run it again. `compile()` refuses the graph with
   `Node 'menu' is not reachable from START`, because as far as the map says, no arrow leads there.
   The mistake is found before a single customer is answered.

## Level complete

You can now:

- send a run down different paths depending on the state,
- say what a router is and why it returns a name,
- explain what `targets` protects you from.

[Back to level 1](01-a-line-of-nodes.md) · [All levels](README.md) · Next: [Level 3, loops](03-loops.md)
