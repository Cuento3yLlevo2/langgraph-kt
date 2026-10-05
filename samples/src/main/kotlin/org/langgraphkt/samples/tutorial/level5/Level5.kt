package org.langgraphkt.samples.tutorial.level5

import kotlinx.coroutines.delay
import org.langgraphkt.CompiledGraph
import org.langgraphkt.END
import org.langgraphkt.GraphEvent
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

/** Turns an event of a run into a line of text. */
fun describe(event: GraphEvent<Ticket>): String =
    when (event) {
        is GraphEvent.NodeStarted -> "step ${event.step}: ${event.node} started"
        is GraphEvent.NodeProgress -> "step ${event.step}: ${event.node} reports ${event.value}"
        is GraphEvent.NodeCompleted -> "step ${event.step}: ${event.node} finished"
        is GraphEvent.StepCompleted -> "step ${event.step} done, facts so far: ${event.state.facts.size}"
        is GraphEvent.Completed -> "finished: ${event.state.reply}"
        is GraphEvent.Interrupted -> "paused before ${event.nextNodes}"
    }

suspend fun main() {
    val (result, time) = measureTimedValue { helpDesk().invoke(Ticket(customer = "Ana", message = "Where is my pizza?")) }
    println(result.state.reply)
    println("Two lookups of one second each took ${time.inWholeMilliseconds} ms.")
    println()

    // The same run again, this time watched while it happens.
    helpDesk().stream(Ticket(customer = "Ana", message = "Where is my pizza?")).collect { event ->
        println(describe(event))
    }
}
