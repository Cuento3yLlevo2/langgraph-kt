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
data class RefundState(
    val orderId: String,
    val amount: Int,
    val approved: Boolean = false,
    val log: List<String> = emptyList(),
)

const val ISSUE_REFUND = "issue_refund"

/** Prepares a refund, then pauses for a human before any money moves. */
fun refundGraph(): CompiledGraph<RefundState> =
    StateGraph<RefundState> {
        val prepare = node("prepare") { it.copy(log = it.log + "Prepared refund of ${it.amount} for order ${it.orderId}") }
        val issue =
            node(ISSUE_REFUND) {
                it.copy(log = it.log + if (it.approved) "Refund issued" else "Refund rejected by reviewer")
            }

        START then prepare then issue then END
    }.compile()

/** Checkpoints are stored on disk, so the approval can arrive minutes or days later, in another process. */
fun refundConfig(directory: Path, threadId: String): GraphConfig<RefundState> =
    GraphConfig(
        threadId = threadId,
        checkpointer = FileCheckpointer(directory, KotlinxStateSerializer<RefundState>()),
        interruptBefore = setOf(ISSUE_REFUND),
    )

suspend fun main() {
    val graph = refundGraph()
    val config = refundConfig(Path(SystemTemporaryDirectory, "langgraph-kt-refunds"), threadId = "order-1001")

    when (val paused = graph.invoke(RefundState(orderId = "1001", amount = 250), config)) {
        is GraphResult.Interrupted -> println("${paused.state.log.last()}. Waiting for approval before ${paused.nextNodes}.")
        is GraphResult.Completed -> error("Expected the run to pause for approval")
    }

    print("Approve refund? [y/N] ")
    val approved = readlnOrNull()?.trim().equals("y", ignoreCase = true)

    val finished = graph.resume(config) { it.copy(approved = approved) }
    println(finished.state.log.last())
}
