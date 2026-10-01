package org.langgraphkt.samples

import org.langgraphkt.END
import org.langgraphkt.GraphEvent
import org.langgraphkt.START
import org.langgraphkt.StateGraph

/** State is an immutable data class. Nodes return updated copies. */
data class ArticleState(
    val topic: String,
    val notes: List<String> = emptyList(),
    val draft: String = "",
    val revisions: Int = 0,
)

/** A research -> write -> review loop that revises the draft until it is long enough. */
fun articleGraph() =
    StateGraph<ArticleState> {
        val research = node("research") { state -> state.copy(notes = listOf("${state.topic} is fast", "${state.topic} is safe")) }
        val write =
            node("write") { state ->
                state.copy(draft = state.notes.take(state.revisions + 1).joinToString(". "), revisions = state.revisions + 1)
            }

        START then research then write
        conditionalEdge(write, targets = setOf(write.name, END)) { state ->
            if (state.revisions < state.notes.size) write.name else END
        }
    }.compile()

suspend fun main() {
    articleGraph().stream(ArticleState(topic = "Kotlin")).collect { event ->
        when (event) {
            is GraphEvent.NodeStarted -> println("  ${event.node} started")
            is GraphEvent.NodeCompleted -> println("  ${event.node} finished")
            is GraphEvent.StepCompleted -> println("step ${event.step} ${event.nodes}: ${event.state.draft.ifEmpty { "(no draft yet)" }}")
            is GraphEvent.Completed -> println("done after ${event.state.revisions} revisions")
            is GraphEvent.Interrupted -> println("paused before ${event.nextNodes}")
        }
    }
}
