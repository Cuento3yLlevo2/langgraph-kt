package org.langgraphkt

import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Reports [value] from a running node to whoever collects the run: it arrives as a
 * [GraphEvent.NodeProgress] in the flow of [CompiledGraph.stream] or [CompiledGraph.streamResume],
 * before the node's [GraphEvent.NodeCompleted]. Use it for what a node produces while it works,
 * such as the text a model writes or how far a download is.
 *
 * ```kotlin
 * val download = node("download", work = { order ->
 *     files.forEachIndexed { index, file ->
 *         fetch(file)
 *         reportProgress("${index + 1} of ${files.size}")
 *     }
 * }) { order, _ -> order.copy(downloaded = true) }
 * ```
 *
 * Call it from the action of a node, or from the `work` of a node with a `work` and an `update`. The
 * call returns when the collector has handled the event, so a slow collector slows the node down
 * instead of filling memory. It does nothing when the run was started with [CompiledGraph.invoke]
 * or [CompiledGraph.resume], because nobody collects those, and nothing outside a running node.
 *
 * Progress is not part of the state: it is not saved in a checkpoint, and a step that runs again
 * after a failure reports again.
 */
public suspend fun reportProgress(value: Any) {
    currentCoroutineContext()[ProgressReporter]?.report(value)
}

/**
 * Returns `true` when this code runs in a node of a run that is collected with
 * [CompiledGraph.stream] or [CompiledGraph.streamResume], so that what it passes to [reportProgress]
 * reaches a collector. A node can use it to skip work that only produces progress.
 */
public suspend fun isProgressCollected(): Boolean = currentCoroutineContext()[ProgressReporter] != null

/** Carries the progress of one running node to the coroutine that emits the events of its run. */
internal class ProgressReporter(
    private val send: suspend (Any) -> Unit,
) : AbstractCoroutineContextElement(Key) {
    suspend fun report(value: Any) {
        try {
            send(value)
        } catch (_: ClosedSendChannelException) {
            // The step is over: a coroutine that outlived its node has nobody left to report to.
        }
    }

    companion object Key : CoroutineContext.Key<ProgressReporter>
}
