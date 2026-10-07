package dev.deeptelar.telar.samples

import dev.deeptelar.telar.CompiledGraph
import dev.deeptelar.telar.END
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import kotlinx.coroutines.delay
import kotlin.time.measureTimedValue

data class ResearchState(
    val question: String,
    val findings: List<String> = emptyList(),
    val summary: String = "",
)

/** Three sources are queried at the same time, then one node summarizes what they found. */
fun researchGraph(sourceDelayMillis: Long = 300): CompiledGraph<ResearchState> =
    StateGraph<ResearchState> {
        // `work` is the slow part and runs for all three sources at once. The block after it adds each
        // result to the state, one source after the other.
        fun source(name: String) =
            node(
                name,
                work = { state ->
                    delay(sourceDelayMillis) // stands in for a network call
                    "$name result for '${state.question}'"
                },
            ) { state, finding -> state.copy(findings = state.findings + finding) }

        val summarize = node("summarize") { it.copy(summary = "${it.findings.size} sources agree.") }

        listOf(source("web"), source("docs"), source("papers")).forEach { START then it then summarize }
        summarize then END
    }.compile()

suspend fun main() {
    val (result, elapsed) = measureTimedValue { researchGraph().invoke(ResearchState(question = "structured concurrency")) }

    result.state.findings.forEach(::println)
    println("${result.state.summary} Took ${elapsed.inWholeMilliseconds} ms for three 300 ms sources.")
}
