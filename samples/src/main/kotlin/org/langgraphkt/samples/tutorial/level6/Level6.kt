package org.langgraphkt.samples.tutorial.level6

import org.langgraphkt.GraphEvent
import org.langgraphkt.samples.tutorial.level5.Ticket
import org.langgraphkt.samples.tutorial.level5.helpDesk

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

/** Level 6 of the tutorial in `docs/`: watch the graph of level 5 while it runs. */
suspend fun main() {
    helpDesk().stream(Ticket(customer = "Ana", message = "Where is my pizza?")).collect { event ->
        println(describe(event))
    }
}
