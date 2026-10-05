package org.langgraphkt.samples.tutorial.level5

import org.langgraphkt.CompiledGraph
import org.langgraphkt.END
import org.langgraphkt.GraphConfig
import org.langgraphkt.GraphResult
import org.langgraphkt.MemoryCheckpointer
import org.langgraphkt.START
import org.langgraphkt.StateGraph

data class Ticket(
    val customer: String,
    val message: String,
    val refund: Int = 0,
    val approved: Boolean = false,
    val reply: String = "",
)

const val PAY = "pay"

/** Level 5 of the tutorial in `docs/`: the run stops before money moves and waits for a human. */
fun helpDesk(): CompiledGraph<Ticket> =
    StateGraph<Ticket> {
        val prepare = node("prepare") { ticket -> ticket.copy(refund = 12) }
        val pay =
            node(PAY) { ticket ->
                if (ticket.approved) {
                    ticket.copy(reply = "Sorry ${ticket.customer}! We sent you ${ticket.refund} euros.")
                } else {
                    ticket.copy(reply = "Sorry ${ticket.customer}, we cannot refund this order.")
                }
            }

        START then prepare then pay then END
    }.compile()

suspend fun main() {
    val graph = helpDesk()
    val config =
        GraphConfig(
            threadId = "ticket-42",
            checkpointer = MemoryCheckpointer<Ticket>(),
            interruptBefore = setOf(PAY),
        )

    when (val paused = graph.invoke(Ticket(customer = "Ana", message = "My pizza arrived cold. I want a refund."), config)) {
        is GraphResult.Interrupted -> println("Paused before ${paused.nextNodes}. Refund: ${paused.state.refund} euros.")
        is GraphResult.Completed -> error("Expected the run to pause before paying")
    }

    print("Approve the refund? [y/N] ")
    val approved = readlnOrNull()?.trim().equals("y", ignoreCase = true)

    val finished = graph.resume(config) { ticket -> ticket.copy(approved = approved) }
    println(finished.state.reply)
}
