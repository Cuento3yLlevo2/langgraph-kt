package dev.deeptelar.telar.samples

import dev.deeptelar.telar.CompiledGraph
import dev.deeptelar.telar.END
import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.GraphResult
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import dev.deeptelar.telar.checkpoint.file.FileCheckpointer
import dev.deeptelar.telar.interrupt
import dev.deeptelar.telar.serialization.KotlinxStateSerializer
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.Serializable

@Serializable
data class Payout(
    val customer: String,
    // The prices of the items the customer sent back.
    val items: List<Int>,
    // What the node asks a person. Null when it asks nothing.
    val question: String? = null,
    // The person's answer. Null while nobody has answered.
    val approved: Boolean? = null,
    val log: List<String> = emptyList(),
)

const val PAY = "pay"

/** A payout up to this amount needs no approval. */
const val PAYOUT_LIMIT = 100

/** Pays a small amount at once. For a large one, the node stops in the middle of its work and asks. */
fun payoutGraph(): CompiledGraph<Payout> =
    StateGraph<Payout> {
        val pay =
            node(PAY) { payout ->
                val total = payout.items.sum()
                // Only this node knows the total, so only it can tell whether a person is needed.
                if (total > PAYOUT_LIMIT && payout.approved == null) {
                    // Saves the question and ends the run here. resume() runs this node again from its first line.
                    interrupt(payout.copy(question = "Pay $total to ${payout.customer}? That is over the limit of $PAYOUT_LIMIT."))
                }
                val line = if (payout.approved == false) "Payout of $total rejected" else "Paid $total to ${payout.customer}"
                payout.copy(question = null, log = payout.log + line)
            }

        START then pay then END
    }.compile()

/** A node can only pause a run that has a checkpointer. There is no `interruptBefore`: the node decides. */
fun payoutConfig(directory: Path, threadId: String): GraphConfig<Payout> =
    GraphConfig(threadId = threadId, checkpointer = FileCheckpointer(directory, KotlinxStateSerializer<Payout>()))

suspend fun main() {
    val graph = payoutGraph()
    val directory = Path(SystemTemporaryDirectory, "telar-payouts")

    // 40 is under the limit: the run does not pause.
    val small = graph.invoke(Payout("Ana", items = listOf(15, 25)), payoutConfig(directory, "payout-1"))
    println(small.state.log.last())

    // 250 is over it: the node asks.
    val config = payoutConfig(directory, "payout-2")
    when (val paused = graph.invoke(Payout("Ben", items = listOf(200, 50)), config)) {
        is GraphResult.Interrupted -> print("${paused.state.question} [y/N] ")
        is GraphResult.Completed -> error("Expected the node to ask before it pays")
    }
    val approved = readlnOrNull()?.trim().equals("y", ignoreCase = true)

    val finished = graph.resume(config) { it.copy(approved = approved) }
    println(finished.state.log.last())
}
