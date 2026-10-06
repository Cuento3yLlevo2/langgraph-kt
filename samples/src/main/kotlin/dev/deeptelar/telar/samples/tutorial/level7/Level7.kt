package dev.deeptelar.telar.samples.tutorial.level7

import dev.deeptelar.telar.CompiledGraph
import dev.deeptelar.telar.END
import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.MemoryCheckpointer
import dev.deeptelar.telar.NodeExecutionException
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import dev.deeptelar.telar.TelarException

data class Ticket(
    val customer: String,
    val message: String,
    val reply: String = "",
)

/** Mistake 1: an arrow points at "anwser", but the node is called "answer". */
fun misspelledNode(): CompiledGraph<Ticket> =
    StateGraph<Ticket> {
        node("answer") { ticket -> ticket.copy(reply = "Hello!") }

        edge(START, "anwser")
    }.compile()

/** Mistake 2: no arrow leads to "check", so it could never run. */
fun forgottenArrow(): CompiledGraph<Ticket> =
    StateGraph<Ticket> {
        val answer = node("answer") { ticket -> ticket.copy(reply = "Hello!") }
        node("check") { ticket -> ticket }

        START then answer then END
    }.compile()

/** Mistake 3: "read" starts two nodes at once, but nothing says how to merge their results. */
fun missingReducer(): CompiledGraph<Ticket> =
    StateGraph<Ticket> {
        val read = node("read") { ticket -> ticket }
        val kitchen = node("kitchen") { ticket -> ticket }
        val driver = node("driver") { ticket -> ticket }

        START then read
        read then kitchen then END
        read then driver then END
    }.compile()

/** Mistake 4: the loop has no way out. This one compiles; it fails when it runs. */
fun endlessLoop(): CompiledGraph<Ticket> =
    StateGraph<Ticket> {
        val write = node("write") { ticket -> ticket.copy(reply = ticket.reply + "!") }

        START then write then write
    }.compile()

/** A help desk whose second node depends on something that can fail: a phone call to the kitchen. */
fun helpDesk(callKitchen: suspend () -> String): CompiledGraph<Ticket> =
    StateGraph<Ticket> {
        val greet = node("greet") { ticket -> ticket.copy(reply = "Hi ${ticket.customer}!") }
        val kitchen = node("kitchen") { ticket -> ticket.copy(reply = "${ticket.reply} ${callKitchen()}") }

        START then greet then kitchen then END
    }.compile()

/** Level 7 of the tutorial in `docs/`: what the library says when something is wrong. */
suspend fun main() {
    val ticket = Ticket(customer = "Ana", message = "Where is my pizza?")

    val mistakes: List<suspend () -> Unit> =
        listOf({ misspelledNode() }, { forgottenArrow() }, { missingReducer() }, { endlessLoop().invoke(ticket) })
    for (mistake in mistakes) {
        try {
            mistake()
        } catch (e: TelarException) {
            println("${e::class.simpleName}: ${e.message}")
        }
    }

    // A node fails. The steps before it are saved, so resume() retries from there.
    var calls = 0
    val graph = helpDesk { if (++calls == 1) error("the kitchen phone is busy") else "Your pizza is in the oven." }
    val config = GraphConfig(threadId = "ticket-42", checkpointer = MemoryCheckpointer<Ticket>())
    try {
        graph.invoke(ticket, config)
    } catch (e: NodeExecutionException) {
        println("${e::class.simpleName}: ${e.message}")
    }
    println("After a retry: ${graph.resume(config).state.reply}")
}
