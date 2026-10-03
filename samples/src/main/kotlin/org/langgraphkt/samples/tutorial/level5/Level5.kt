package org.langgraphkt.samples.tutorial.level5

import kotlinx.coroutines.delay
import org.langgraphkt.CompiledGraph
import org.langgraphkt.END
import org.langgraphkt.START
import org.langgraphkt.StateGraph
import kotlin.time.measureTimedValue

data class Ticket(
    val customer: String,
    val message: String,
    val facts: List<String> = emptyList(),
    val reply: String = "",
)

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

/** Level 5 of the tutorial in `docs/`: two slow lookups run at the same time. */
fun helpDesk(lookupMillis: Long = 1_000): CompiledGraph<Ticket> =
    StateGraph<Ticket> {
        // `work` asks and returns what it found. The block after it writes that fact into the ticket.
        val kitchen = node("kitchen", work = { askKitchen(lookupMillis) }) { ticket, fact -> ticket.copy(facts = ticket.facts + fact) }
        val driver = node("driver", work = { askDriver(lookupMillis) }) { ticket, fact -> ticket.copy(facts = ticket.facts + fact) }
        val answer = node("answer") { ticket -> ticket.copy(reply = "Hi ${ticket.customer}, ${ticket.facts.joinToString(" and ")}.") }

        START then kitchen then answer
        START then driver then answer
        answer then END
    }.compile()

suspend fun main() {
    val (result, time) = measureTimedValue { helpDesk().invoke(Ticket(customer = "Ana", message = "Where is my pizza?")) }
    println(result.state.reply)
    println("Two lookups of one second each took ${time.inWholeMilliseconds} ms.")
}
