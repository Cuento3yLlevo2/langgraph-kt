package org.langgraphkt.samples.tutorial.level1

import org.langgraphkt.CompiledGraph
import org.langgraphkt.END
import org.langgraphkt.START
import org.langgraphkt.StateGraph

/** The state: everything the help desk knows about one customer message. */
data class Ticket(
    val customer: String,
    val message: String,
    val reply: String = "",
)

/** Level 1 of the tutorial in `docs/`: a graph with a single node. */
fun helpDesk(): CompiledGraph<Ticket> =
    StateGraph<Ticket> {
        val greet = node("greet") { ticket -> ticket.copy(reply = "Hi ${ticket.customer}, thanks for writing to Pixel Pizza!") }

        START then greet then END
    }.compile()

suspend fun main() {
    val result = helpDesk().invoke(Ticket(customer = "Ana", message = "Where is my pizza?"))
    println(result.state.reply)
}
