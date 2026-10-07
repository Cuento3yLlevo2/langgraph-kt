package dev.deeptelar.telar.samples

import dev.deeptelar.telar.CompiledGraph
import dev.deeptelar.telar.END
import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.GraphResult
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import dev.deeptelar.telar.checkpoint.file.FileCheckpointer
import dev.deeptelar.telar.serialization.KotlinxStateSerializer
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.Serializable

@Serializable
data class ReturnCase(
    val customer: String,
    // The prices of the items the customer sent back.
    val items: List<Int>,
    // The state of the subgraph. It is null until the run enters the subgraph, and it is saved with
    // the rest, so a run that pauses inside the subgraph can continue there.
    val payout: Payout? = null,
    val reply: String = "",
)

const val PAYOUT = "payout"

/**
 * Handles a return. Paying is a graph of its own, [payoutGraph] of the AskFromANode sample, used
 * here as one node. When that graph asks a person before a large payout, this graph pauses with it.
 */
fun returnsGraph(): CompiledGraph<ReturnCase> =
    StateGraph<ReturnCase> {
        val check = node("check") { case -> case.copy(items = case.items.filter { it > 0 }) }
        val payout =
            subgraph(
                PAYOUT,
                payoutGraph(),
                // The state the subgraph works on: the saved one, or a new one on the first visit.
                state = { case -> case.payout ?: Payout(case.customer, case.items) },
                // Called when the subgraph finishes, and when it pauses.
                update = { case, payout -> case.copy(payout = payout) },
            )
        val reply = node("reply") { case -> case.copy(reply = "Dear ${case.customer}: ${case.payout?.log?.lastOrNull()}.") }

        START then check then payout then reply then END
    }.compile()

/** One checkpointer, for the state of the graph around. The subgraph needs none of its own. */
fun returnsConfig(directory: Path, threadId: String): GraphConfig<ReturnCase> =
    GraphConfig(threadId = threadId, checkpointer = FileCheckpointer(directory, KotlinxStateSerializer<ReturnCase>()))

suspend fun main() {
    val graph = returnsGraph()
    val config = returnsConfig(Path(SystemTemporaryDirectory, "telar-returns"), threadId = "return-7")

    // 250 is over the limit of the payout graph, so its node asks, and the run pauses inside the subgraph.
    when (val paused = graph.invoke(ReturnCase("Ben", items = listOf(200, 50)), config)) {
        is GraphResult.Interrupted -> print("${paused.state.payout?.question} [y/N] ")
        is GraphResult.Completed -> error("Expected the payout to ask before it pays")
    }
    val approved = readlnOrNull()?.trim().equals("y", ignoreCase = true)

    // The answer goes into the state of the subgraph. The run continues inside it, and then with "reply".
    val finished = graph.resume(config) { it.copy(payout = it.payout?.copy(approved = approved)) }
    println(finished.state.reply)
}
