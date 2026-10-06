package dev.deeptelar.telar.samples.tutorial.level8

import dev.deeptelar.telar.CompiledGraph
import dev.deeptelar.telar.END
import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.GraphResult
import dev.deeptelar.telar.MemoryCheckpointer
import dev.deeptelar.telar.NodeRef
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.agent.ChatModel
import dev.deeptelar.telar.agent.toolLoop
import dev.deeptelar.telar.samples.tutorial.level6.menuPrice
import dev.deeptelar.telar.samples.tutorial.level6.pretendModel
import kotlinx.coroutines.delay

data class Ticket(
    val customer: String,
    val message: String,
    val topic: String = "",
    val facts: List<String> = emptyList(),
    val conversation: List<ChatMessage> = emptyList(),
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
        "sell" in message.lowercase() || "cost" in message.lowercase() -> "menu"
        else -> "other"
    }

/** Pretends to be an AI writer that forgets the customer's name on its first attempt. */
fun writeReply(ticket: Ticket): String {
    val body = if (ticket.facts.isEmpty()) "a colleague will reply soon" else ticket.facts.joinToString(" and ")
    return if (ticket.attempts == 0) "$body." else "Hi ${ticket.customer}, $body."
}

fun problemWith(ticket: Ticket): String = if (ticket.customer in ticket.reply) "" else "use the customer's name"

/** A slow call to the kitchen. `delay` stands in for the time a real call to another system takes. */
suspend fun askKitchen(millis: Long): String {
    delay(millis)
    return "your pizza left the oven"
}

/** A slow call to the driver. */
suspend fun askDriver(millis: Long): String {
    delay(millis)
    return "the driver is 5 minutes away"
}

/** Level 8 of the tutorial in `docs/`: every move of the earlier levels in one graph. */
fun helpDesk(model: ChatModel, lookupMillis: Long = 1_000): CompiledGraph<Ticket> =
    StateGraph<Ticket> {
        val read = node("read") { ticket -> ticket.copy(topic = topicOf(ticket.message)) }

        // Delivery questions: two lookups at the same time.
        val lookUp = node("look_up") { ticket -> ticket }
        val kitchen = node("kitchen", work = { askKitchen(lookupMillis) }) { ticket, fact -> ticket.copy(facts = ticket.facts + fact) }
        val driver = node("driver", work = { askDriver(lookupMillis) }) { ticket, fact -> ticket.copy(facts = ticket.facts + fact) }

        // Questions about the menu: a model looks up the prices with the tool of level 6.
        val send = node("send") { ticket -> ticket.copy(reply = "Hi ${ticket.customer}! ${ticket.conversation.last().text}") }
        val agent =
            toolLoop(
                model = model,
                tools = listOf(menuPrice),
                messages = { ticket -> ticket.conversation },
                append = { ticket, new -> ticket.copy(conversation = ticket.conversation + new) },
                firstMessage = { ticket -> "I'm ${ticket.customer}. ${ticket.message}" },
                then = send,
            )

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
        conditionalEdge(read, targets = setOf(lookUp, prepare, agent, write)) { ticket ->
            when (ticket.topic) {
                "delivery" -> lookUp
                "refund" -> prepare
                "menu" -> agent
                else -> write
            }
        }

        lookUp then kitchen then write
        lookUp then driver then write
        write then check
        conditionalEdge(check, targets = setOf(write, NodeRef.END)) { ticket ->
            if (ticket.problem.isEmpty() || ticket.attempts >= MAX_ATTEMPTS) NodeRef.END else write
        }

        prepare then pay then END
        send then END
    }.compile()

suspend fun main() {
    // The pretend model of level 6. Pass a real one to let it answer the questions about the menu.
    val graph = helpDesk(pretendModel)
    val checkpointer = MemoryCheckpointer<Ticket>()
    val messages = listOf("Where is my pizza?", "My pizza arrived cold. I want a refund.", "Do you sell salad?", "Thanks for the pizza!")

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
