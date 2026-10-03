package org.langgraphkt.samples.tutorial.level10

import kotlinx.coroutines.delay
import org.langgraphkt.CompiledGraph
import org.langgraphkt.END
import org.langgraphkt.GraphConfig
import org.langgraphkt.GraphResult
import org.langgraphkt.MemoryCheckpointer
import org.langgraphkt.Reducer
import org.langgraphkt.START
import org.langgraphkt.StateGraph

data class Ticket(
    val customer: String,
    val message: String,
    val topic: String = "",
    val facts: List<String> = emptyList(),
    val refund: Int = 0,
    val approved: Boolean = false,
    val reply: String = "",
    val attempts: Int = 0,
    val problem: String = "",
)

const val PAY = "pay"
const val MAX_ATTEMPTS = 3

fun topicOf(message: String): String =
    when {
        "refund" in message.lowercase() -> "refund"
        "where" in message.lowercase() -> "delivery"
        else -> "other"
    }

/** Pretends to be an AI writer that forgets the customer's name on its first attempt. */
fun writeReply(ticket: Ticket): String {
    val body = if (ticket.facts.isEmpty()) "a colleague will reply soon" else ticket.facts.joinToString(" and ")
    return if (ticket.attempts == 0) "$body." else "Hi ${ticket.customer}, $body."
}

fun problemWith(ticket: Ticket): String = if (ticket.customer in ticket.reply) "" else "use the customer's name"

val collectFacts = Reducer<Ticket> { current, updates -> current.copy(facts = updates.flatMap { it.facts }.distinct()) }

/** Level 10 of the tutorial in `docs/`: every move of the earlier levels in one graph. */
fun helpDesk(lookupMillis: Long = 1_000): CompiledGraph<Ticket> =
    StateGraph<Ticket> {
        val read = node("read") { ticket -> ticket.copy(topic = topicOf(ticket.message)) }

        // Delivery questions: two lookups at the same time.
        val lookUp = node("look_up") { ticket -> ticket }
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

        // Every reply is written and checked, and rewritten if the check finds a problem.
        val write = node("write") { ticket -> ticket.copy(reply = writeReply(ticket), attempts = ticket.attempts + 1) }
        val check = node("check") { ticket -> ticket.copy(problem = problemWith(ticket)) }

        // Refunds: a human decides before PAY runs (see interruptBefore in main).
        val prepare = node("prepare") { ticket -> ticket.copy(refund = 12) }
        val pay =
            node(PAY) { ticket ->
                if (ticket.approved) {
                    ticket.copy(reply = "Sorry ${ticket.customer}! We sent you ${ticket.refund} euros.")
                } else {
                    ticket.copy(reply = "Sorry ${ticket.customer}, we cannot refund this order.")
                }
            }

        START then read
        conditionalEdge(read, targets = setOf(lookUp.name, prepare.name, write.name)) { ticket ->
            when (ticket.topic) {
                "delivery" -> lookUp.name
                "refund" -> prepare.name
                else -> write.name
            }
        }

        lookUp then kitchen then write
        lookUp then driver then write
        write then check
        conditionalEdge(check, targets = setOf(write.name, END)) { ticket ->
            if (ticket.problem.isEmpty() || ticket.attempts >= MAX_ATTEMPTS) END else write.name
        }

        prepare then pay then END
    }.compile(reducer = collectFacts)

suspend fun main() {
    val graph = helpDesk()
    val checkpointer = MemoryCheckpointer<Ticket>()
    val messages = listOf("Where is my pizza?", "My pizza arrived cold. I want a refund.", "Do you sell salad?")

    messages.forEachIndexed { index, message ->
        val config = GraphConfig(threadId = "ticket-${index + 1}", checkpointer = checkpointer, interruptBefore = setOf(PAY))

        var result = graph.invoke(Ticket(customer = "Ana", message = message), config)
        if (result is GraphResult.Interrupted) {
            println("${config.threadId}: a manager approves the refund of ${result.state.refund} euros")
            result = graph.resume(config) { ticket -> ticket.copy(approved = true) }
        }
        println("${config.threadId}: ${result.state.reply}")
    }
}
