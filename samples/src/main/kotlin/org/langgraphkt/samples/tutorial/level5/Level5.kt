package org.langgraphkt.samples.tutorial.level5

import kotlinx.coroutines.delay
import org.langgraphkt.CompiledGraph
import org.langgraphkt.END
import org.langgraphkt.Reducer
import org.langgraphkt.START
import org.langgraphkt.StateGraph
import kotlin.time.measureTimedValue

data class Ticket(
    val customer: String,
    val message: String,
    val facts: List<String> = emptyList(),
    val reply: String = "",
)

/** Merges the copies returned by nodes that ran at the same time: keep every fact, once. */
val collectFacts = Reducer<Ticket> { current, updates -> current.copy(facts = updates.flatMap { it.facts }.distinct()) }

/** Level 5 of the tutorial in `docs/`: two slow lookups run at the same time. */
fun helpDesk(lookupMillis: Long = 1_000): CompiledGraph<Ticket> =
    StateGraph<Ticket> {
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
        val answer = node("answer") { ticket -> ticket.copy(reply = "Hi ${ticket.customer}, ${ticket.facts.joinToString(" and ")}.") }

        START then kitchen then answer
        START then driver then answer
        answer then END
    }.compile(reducer = collectFacts)

suspend fun main() {
    val (result, time) = measureTimedValue { helpDesk().invoke(Ticket(customer = "Ana", message = "Where is my pizza?")) }
    println(result.state.reply)
    println("Two lookups of one second each took ${time.inWholeMilliseconds} ms.")
}
