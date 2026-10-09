package dev.deeptelar.telar.samples.tutorial.level2

import dev.deeptelar.telar.CompiledGraph
import dev.deeptelar.telar.END
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph

data class Ticket(
    val customer: String,
    val message: String,
    val topic: String = "",
    val reply: String = "",
)

fun topicOf(message: String): String =
    when {
        "refund" in message.lowercase() -> "refund"
        "where" in message.lowercase() -> "delivery"
        else -> "other"
    }

/** Level 2 of the tutorial in `docs/tutorial/`: a conditional edge picks one of three paths. */
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
