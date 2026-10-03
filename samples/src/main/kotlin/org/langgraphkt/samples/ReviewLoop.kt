package org.langgraphkt.samples

import org.langgraphkt.CompiledGraph
import org.langgraphkt.END
import org.langgraphkt.GraphConfig
import org.langgraphkt.GraphResult
import org.langgraphkt.MemoryCheckpointer
import org.langgraphkt.START
import org.langgraphkt.StateGraph

/** [approved] and [feedback] are written by the human reviewer when the run is resumed. */
data class AnnouncementState(
    val topic: String,
    val draft: String = "",
    val revisions: Int = 0,
    val approved: Boolean = false,
    val feedback: String = "",
    val published: Boolean = false,
)

const val REVIEW = "review"

/**
 * Drafts an announcement and waits for a reviewer, who either approves it or sends it back with a
 * change request. The run pauses before [REVIEW] every time a draft is ready.
 *
 * `review` does no work of its own. It is the place where the run waits, and the conditional edge
 * after it routes on the decision that `resume` wrote into the state.
 */
fun announcementGraph(): CompiledGraph<AnnouncementState> =
    StateGraph<AnnouncementState> {
        val draft =
            node("draft") { state ->
                val change = if (state.feedback.isBlank()) "" else " (${state.feedback})"
                state.copy(draft = "Announcing ${state.topic}$change", revisions = state.revisions + 1, feedback = "")
            }
        val review = node(REVIEW) { it }
        val publish = node("publish") { it.copy(published = true) }

        START then draft then review
        conditionalEdge(review, targets = setOf(publish, draft)) { state ->
            if (state.approved) publish else draft
        }
        publish then END
    }.compile()

suspend fun main() {
    val graph = announcementGraph()
    val config = GraphConfig(checkpointer = MemoryCheckpointer<AnnouncementState>(), interruptBefore = setOf(REVIEW))

    var result = graph.invoke(AnnouncementState(topic = "the 1.0 release"), config)
    while (result is GraphResult.Interrupted) {
        println("Draft ${result.state.revisions}: ${result.state.draft}")
        print("Press Enter to approve, or type what to change: ")
        val feedback = readlnOrNull().orEmpty().trim()
        result = graph.resume(config) { it.copy(approved = feedback.isEmpty(), feedback = feedback) }
    }
    println("Published: ${result.state.draft}")
}
