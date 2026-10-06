package dev.deeptelar.telar.samples.tutorial.level3

import dev.deeptelar.telar.CompiledGraph
import dev.deeptelar.telar.NodeRef
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph

data class Ticket(
    val customer: String,
    val message: String,
    val reply: String = "",
    val attempts: Int = 0,
    val problem: String = "",
)

const val MAX_ATTEMPTS = 5

/** Pretends to be an AI writer whose reply gets better with every attempt. */
fun writeReply(ticket: Ticket): String =
    when (ticket.attempts) {
        0 -> "Your pizza is late."
        1 -> "Sorry, your pizza is late."
        else -> "Sorry ${ticket.customer}, your pizza is late. It arrives in 10 minutes."
    }

/** Returns what is wrong with the reply, or an empty string when it is good enough to send. */
fun problemWith(ticket: Ticket): String =
    when {
        "sorry" !in ticket.reply.lowercase() -> "say sorry"
        ticket.customer !in ticket.reply -> "use the customer's name"
        else -> ""
    }

/** Level 3 of the tutorial in `docs/`: an edge that goes back, so the reply is rewritten until it passes. */
fun helpDesk(): CompiledGraph<Ticket> =
    StateGraph<Ticket> {
        val write = node("write") { ticket -> ticket.copy(reply = writeReply(ticket), attempts = ticket.attempts + 1) }
        val check = node("check") { ticket -> ticket.copy(problem = problemWith(ticket)) }

        START then write then check
        conditionalEdge(check, targets = setOf(write, NodeRef.END)) { ticket ->
            if (ticket.problem.isEmpty() || ticket.attempts >= MAX_ATTEMPTS) NodeRef.END else write
        }
    }.compile()

suspend fun main() {
    val result = helpDesk().invoke(Ticket(customer = "Ana", message = "My pizza is late!"))
    println("attempts: ${result.state.attempts}")
    println("reply: ${result.state.reply}")
}
