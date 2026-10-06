package dev.deeptelar.telar.samples.tutorial.level1

import dev.deeptelar.telar.CompiledGraph
import dev.deeptelar.telar.END
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph

/** The state: everything the help desk knows about one customer message. */
data class Ticket(
    val customer: String,
    val message: String,
    val topic: String = "",
    val reply: String = "",
)

/** The smallest graph that does something: a single node. */
fun greeter(): CompiledGraph<Ticket> =
    StateGraph<Ticket> {
        val greet = node("greet") { ticket -> ticket.copy(reply = "Hi ${ticket.customer}, thanks for writing to Pixel Pizza!") }

        START then greet then END
    }.compile()

/** A real help desk would ask an AI model. Looking for keywords is enough to learn the moves. */
fun topicOf(message: String): String =
    when {
        "refund" in message.lowercase() -> "refund"
        "where" in message.lowercase() -> "delivery"
        else -> "other"
    }

/** Level 1 of the tutorial in `docs/`: two nodes in a row, each adding to the state. */
fun helpDesk(): CompiledGraph<Ticket> =
    StateGraph<Ticket> {
        val read = node("read") { ticket -> ticket.copy(topic = topicOf(ticket.message)) }
        val answer = node("answer") { ticket -> ticket.copy(reply = "Hi ${ticket.customer}, we got your ${ticket.topic} question.") }

        START then read then answer then END
    }.compile()

suspend fun main() {
    val greeted = greeter().invoke(Ticket(customer = "Ana", message = "Where is my pizza?"))
    println(greeted.state.reply)
    println()

    val result = helpDesk().invoke(Ticket(customer = "Ana", message = "Where is my pizza?"))
    println("topic: ${result.state.topic}")
    println("reply: ${result.state.reply}")
}
