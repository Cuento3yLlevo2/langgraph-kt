package org.langgraphkt.samples.tutorial.level2

import org.langgraphkt.CompiledGraph
import org.langgraphkt.END
import org.langgraphkt.START
import org.langgraphkt.StateGraph

data class Ticket(
    val customer: String,
    val message: String,
    val topic: String = "",
    val reply: String = "",
)

/** A real help desk would ask an AI model. Looking for keywords is enough to learn the moves. */
fun topicOf(message: String): String =
    when {
        "refund" in message.lowercase() -> "refund"
        "where" in message.lowercase() -> "delivery"
        else -> "other"
    }

/** Level 2 of the tutorial in `docs/`: two nodes in a row, each adding to the state. */
fun helpDesk(): CompiledGraph<Ticket> =
    StateGraph<Ticket> {
        val read = node("read") { ticket -> ticket.copy(topic = topicOf(ticket.message)) }
        val answer = node("answer") { ticket -> ticket.copy(reply = "Hi ${ticket.customer}, we got your ${ticket.topic} question.") }

        START then read then answer then END
    }.compile()

suspend fun main() {
    val result = helpDesk().invoke(Ticket(customer = "Ana", message = "Where is my pizza?"))
    println("topic: ${result.state.topic}")
    println("reply: ${result.state.reply}")
}
