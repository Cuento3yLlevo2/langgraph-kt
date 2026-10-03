package org.langgraphkt.samples

import kotlinx.coroutines.delay
import org.langgraphkt.CompiledGraph
import org.langgraphkt.END
import org.langgraphkt.Reducer
import org.langgraphkt.START
import org.langgraphkt.StateGraph
import kotlin.time.measureTimedValue

data class ResearchState(
    val question: String,
    val findings: List<String> = emptyList(),
    val summary: String = "",
)

/** Parallel branches each return their own copy of the state. The reducer merges what they added. */
val mergeFindings =
    Reducer<ResearchState> { current, updates ->
        current.copy(findings = current.findings + updates.flatMap { it.findings - current.findings.toSet() })
    }

/** Three sources are queried at the same time, then one node summarizes the merged findings. */
fun researchGraph(sourceDelayMillis: Long = 300): CompiledGraph<ResearchState> =
    StateGraph<ResearchState> {
        fun source(name: String) =
            node(name) { state ->
                delay(sourceDelayMillis) // stands in for a network call
                state.copy(findings = state.findings + "$name result for '${state.question}'")
            }

        val summarize = node("summarize") { it.copy(summary = "${it.findings.size} sources agree.") }

        listOf(source("web"), source("docs"), source("papers")).forEach { START then it then summarize }
        summarize then END
    }.compile(reducer = mergeFindings)

suspend fun main() {
    val (result, elapsed) = measureTimedValue { researchGraph().invoke(ResearchState(question = "structured concurrency")) }

    result.state.findings.forEach(::println)
    println("${result.state.summary} Took ${elapsed.inWholeMilliseconds} ms for three 300 ms sources.")
}
