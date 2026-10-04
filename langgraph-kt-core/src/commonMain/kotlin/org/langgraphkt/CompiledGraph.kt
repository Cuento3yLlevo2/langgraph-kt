package org.langgraphkt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlin.coroutines.EmptyCoroutineContext

/**
 * An executable graph produced by [StateGraph.compile]. It is immutable and can be shared and run
 * concurrently; each run is isolated by [GraphConfig.threadId].
 *
 * ## Execution model
 *
 * A run advances in steps. In each step all currently active nodes run in parallel on the same
 * input state. When there is more than one, their results are combined: the [Reducer] merges the
 * nodes that returned a whole state, and the updates of the nodes that were added with a `work` and
 * an `update` are then applied one after another, in the order those nodes were added to the graph.
 * The outgoing edges of the nodes then decide which nodes are active in the next step. A branch
 * that routes to [END] simply stops; the run completes when no nodes are active.
 *
 * A node reached by several branches in the same step runs once. If the branches have different
 * lengths, the node runs once for each step in which a branch reaches it.
 *
 * Nodes run in the coroutine context of the caller. If a node fails, the other nodes of that step
 * are cancelled and the failure is rethrown as [NodeExecutionException]. A failure of the reducer or
 * of the condition of an edge is rethrown as [ReducerException] or [EdgeConditionException].
 *
 * ## Failures and retries
 *
 * With a checkpointer, a step is saved once its nodes have run, their results are merged and the
 * next nodes are chosen. A step that fails at any of these points is not saved, so [resume] runs the
 * whole step again, including the nodes of that step that had already finished. Make the side
 * effects of a node safe to repeat.
 */
public class CompiledGraph<State> internal constructor(
    internal val nodes: Map<String, Node<State>>,
    internal val edges: Map<String, List<String>>,
    internal val conditionalEdges: Map<String, ConditionalEdge<State>>,
    internal val reducer: Reducer<State>? = null,
) {
    /** The position of each node in the order the nodes were added to the graph. */
    private val nodeOrder: Map<String, Int> = nodes.keys.withIndex().associate { it.value to it.index }

    /** The nodes and edges of this graph, for drawing or inspecting it. */
    public val topology: GraphTopology =
        GraphTopology(
            nodes = nodes.keys.toList(),
            edges =
                (listOf(START) + nodes.keys).flatMap { source ->
                    val conditional = conditionalEdges[source]
                    val targets = edges[source].orEmpty()
                    when {
                        conditional != null -> conditional.targets.orEmpty().map { GraphEdge(source, it, isConditional = true) }
                        targets.isNotEmpty() -> targets.map { GraphEdge(source, it) }
                        // A node without an outgoing edge ends its branch.
                        else -> listOf(GraphEdge(source, END))
                    }
                },
            dynamicRoutes =
                conditionalEdges.values
                    .filter { it.targets == null }
                    .map { it.from }
                    .toSet(),
        )

    /**
     * Runs the graph from [START] with [input] until it completes or reaches an interrupt.
     *
     * A call always starts a new run: if [config] has a checkpointer, any earlier checkpoint of the
     * thread is replaced by one that holds [input], before the first node runs. Use [resume] to
     * continue a paused run, or to retry one that failed.
     *
     * @throws GraphValidationException if [config] names interrupt nodes that are not in the graph.
     * @throws MaxIterationsExceededException if the run needs more than [GraphConfig.maxIterations] steps.
     * @throws NodeExecutionException if a node throws.
     * @throws ReducerException if the reducer throws.
     * @throws EdgeConditionException if the condition of a conditional edge throws.
     * @throws InvalidRouteException if a conditional edge returns an invalid target.
     */
    public suspend fun invoke(input: State, config: GraphConfig<State> = GraphConfig()): GraphResult<State> =
        events(input, config, progress = false).last().toResult()

    /**
     * Continues the run of [GraphConfig.threadId] from its last checkpoint, typically after an
     * interrupt and a human decision.
     *
     * @param update edits the checkpointed state before execution continues, for example to record
     * an approval. Defaults to continuing with the state unchanged.
     * @throws GraphValidationException if [config] has no checkpointer.
     * @throws CheckpointNotFoundException if the thread has no checkpoint.
     * @throws GraphAlreadyCompletedException if the thread's last run already completed.
     */
    public suspend fun resume(config: GraphConfig<State>, update: suspend (State) -> State = { it }): GraphResult<State> =
        resumeEvents(config, update, progress = false).last().toResult()

    /**
     * Returns where the run of [GraphConfig.threadId] stopped, read from its last checkpoint, without
     * running anything. Use it to restore a screen after a restart, or to build the next input of a
     * conversation from the state the last run ended with.
     *
     * The result is [GraphResult.Completed] when the run finished, and [GraphResult.Interrupted] when
     * [resume] can continue it: the run paused at an interrupt, or stopped before or between two
     * steps because a node failed or the process ended. It is `null` when the thread has no checkpoint.
     *
     * @throws GraphValidationException if [config] has no checkpointer.
     */
    public suspend fun lastResult(config: GraphConfig<State>): GraphResult<State>? {
        val checkpointer =
            config.checkpointer ?: throw GraphValidationException("lastResult() needs a GraphConfig with a checkpointer.")
        val checkpoint = checkpointer.load(config.threadId) ?: return null
        return if (checkpoint.isComplete) {
            GraphResult.Completed(checkpoint.state)
        } else {
            GraphResult.Interrupted(checkpoint.state, checkpoint.nextNodes)
        }
    }

    /**
     * Like [invoke], but returns a cold [Flow] that emits a [GraphEvent] as each node starts and
     * finishes and after every step, and ends with [GraphEvent.Completed] or
     * [GraphEvent.Interrupted]. What a node passes to [reportProgress] while it runs arrives as a
     * [GraphEvent.NodeProgress]. Nothing runs until the flow is collected, and each collection is a
     * new run.
     */
    public fun stream(input: State, config: GraphConfig<State> = GraphConfig()): Flow<GraphEvent<State>> =
        events(input, config, progress = true)

    /** The run of [invoke] and [stream]. Only a stream has a collector for the [progress] of its nodes. */
    private fun events(input: State, config: GraphConfig<State>, progress: Boolean): Flow<GraphEvent<State>> =
        flow {
            validateInterrupts(config)
            // Drop the previous run now, so that a failure before the first save cannot be resumed into it.
            config.checkpointer?.delete(config.threadId)
            val firstNodes = resolveNextNodes(listOf(START), input)
            // Save the input before anything runs, so that a failure in the first step can be resumed.
            config.checkpointer?.save(config.threadId, Checkpoint(input, firstNodes))
            run(config, RunStart(input, firstNodes, step = 0, skipInterruptBefore = false), progress)
        }

    /** Like [resume], but returns a cold [Flow] of [GraphEvent]s. See [stream]. */
    public fun streamResume(config: GraphConfig<State>, update: suspend (State) -> State = { it }): Flow<GraphEvent<State>> =
        resumeEvents(config, update, progress = true)

    private fun resumeEvents(config: GraphConfig<State>, update: suspend (State) -> State, progress: Boolean): Flow<GraphEvent<State>> =
        flow {
            validateInterrupts(config)
            val checkpointer =
                config.checkpointer ?: throw GraphValidationException("resume() needs a GraphConfig with a checkpointer.")
            val checkpoint = checkpointer.load(config.threadId) ?: throw CheckpointNotFoundException(config.threadId)
            if (checkpoint.isComplete) throw GraphAlreadyCompletedException(config.threadId)
            (checkpoint.nextNodes - nodes.keys).firstOrNull()?.let {
                throw GraphValidationException(
                    "Checkpoint of thread '${config.threadId}' refers to node '$it', which is not in this graph.",
                )
            }
            // Only a run that paused before its next nodes continues past that pause. After any other
            // checkpoint (an interruptAfter pause, or a crash between steps) the pause is still due.
            run(config, RunStart(update(checkpoint.state), checkpoint.nextNodes, checkpoint.step, checkpoint.interruptedBefore), progress)
        }

    private class RunStart<State>(
        val state: State,
        val activeNodes: List<String>,
        val step: Int,
        val skipInterruptBefore: Boolean,
    )

    private suspend fun FlowCollector<GraphEvent<State>>.run(config: GraphConfig<State>, start: RunStart<State>, progress: Boolean) {
        var state = start.state
        var activeNodes = start.activeNodes
        var step = start.step
        var skipInterruptBefore = start.skipInterruptBefore
        var executedSteps = 0

        suspend fun checkpoint(nextNodes: List<String>, interruptedBefore: Boolean = false) {
            config.checkpointer?.save(config.threadId, Checkpoint(state, nextNodes, step, interruptedBefore))
        }

        while (activeNodes.isNotEmpty()) {
            if (!skipInterruptBefore && activeNodes.any { it in config.interruptBefore }) {
                checkpoint(activeNodes, interruptedBefore = true)
                emit(GraphEvent.Interrupted(state, activeNodes))
                return
            }
            skipInterruptBefore = false

            if (executedSteps >= config.maxIterations) throw MaxIterationsExceededException(config.maxIterations)
            executedSteps++
            step++

            state = runStep(step, activeNodes, state, progress)
            val nextNodes = resolveNextNodes(activeNodes, state)
            checkpoint(nextNodes)
            emit(GraphEvent.StepCompleted(step, activeNodes, state))

            if (nextNodes.isNotEmpty() && activeNodes.any { it in config.interruptAfter }) {
                emit(GraphEvent.Interrupted(state, nextNodes))
                return
            }
            activeNodes = nextNodes
        }

        emit(GraphEvent.Completed(state))
    }

    private suspend fun FlowCollector<GraphEvent<State>>.runStep(
        step: Int,
        activeNodes: List<String>,
        state: State,
        progress: Boolean,
    ): State {
        activeNodes.forEach { emit(GraphEvent.NodeStarted(step, it, state)) }
        if (activeNodes.size == 1 && !progress) {
            val name = activeNodes.single()
            return runNode(nodes.getValue(name), state).state.also { emit(GraphEvent.NodeCompleted(step, name, it)) }
        }

        // A flow may only emit from the coroutine that collects it, so the nodes hand what they
        // report and their results over a channel, and this coroutine emits them as they arrive.
        // A node waits until its report is emitted, so it cannot run ahead of the collector, and a
        // node that fails right after a report does not take the report with it.
        val signals = Channel<NodeSignal<State>>(Channel.BUFFERED)
        val outputs =
            try {
                coroutineScope {
                    val results =
                        activeNodes.map { name ->
                            async(if (progress) reporter(name, signals) else EmptyCoroutineContext) {
                                runNode(nodes.getValue(name), state).also { signals.send(NodeSignal.Finished(name, it.state)) }
                            }
                        }
                    var running = activeNodes.size
                    while (running > 0) {
                        when (val signal = signals.receive()) {
                            is NodeSignal.Progress -> {
                                emit(GraphEvent.NodeProgress(step, signal.node, signal.value, state))
                                signal.emitted.complete(Unit)
                            }
                            is NodeSignal.Finished -> {
                                emit(GraphEvent.NodeCompleted(step, signal.node, signal.state))
                                running--
                            }
                        }
                    }
                    results.awaitAll()
                }
            } finally {
                // A coroutine that outlives its node must not wait for a reader that is gone.
                signals.close()
                while (true) {
                    val unread = signals.tryReceive().getOrNull() ?: break
                    (unread as? NodeSignal.Progress)?.emitted?.complete(Unit)
                }
            }
        return combine(state, activeNodes.zip(outputs))
    }

    /** Sends what the node named [name] reports to [signals], and makes the node wait until it is emitted. */
    private fun reporter(name: String, signals: SendChannel<NodeSignal<State>>): ProgressReporter =
        ProgressReporter { value ->
            val emitted = CompletableDeferred<Unit>()
            signals.send(NodeSignal.Progress(name, value, emitted))
            emitted.await()
        }

    /** What a running node sends to the coroutine that emits the events of its step. */
    private sealed interface NodeSignal<out State> {
        class Progress(
            val node: String,
            val value: Any,
            val emitted: CompletableDeferred<Unit>,
        ) : NodeSignal<Nothing>

        class Finished<State>(
            val node: String,
            val state: State,
        ) : NodeSignal<State>
    }

    /**
     * Combines what the nodes of a parallel step produced, each given with the name of its node.
     *
     * The nodes that returned a whole state decide the state to start from: [state] when there is
     * none, the state of the only one, or what the reducer makes of several. The updates of the
     * other nodes are then applied to it one after another, in the order those nodes were added to
     * the graph.
     */
    private suspend fun combine(state: State, outputs: List<Pair<String, NodeOutput<State>>>): State {
        val states = outputs.filter { (_, output) -> output.update == null }
        var combined =
            when (states.size) {
                0 -> state
                1 -> states.single().second.state
                else -> {
                    val reducer =
                        checkNotNull(reducer) { "compile() guarantees a reducer when two nodes that return a state can run together" }
                    val names = states.map { it.first }
                    wrapFailure({ cause -> ReducerException(names, cause) }) { reducer.reduce(state, states.map { it.second.state }) }
                }
            }
        for ((name, output) in outputs.sortedBy { nodeOrder.getValue(it.first) }) {
            val update = output.update ?: continue
            combined = wrapFailure({ cause -> NodeExecutionException(name, cause) }) { update(combined) }
        }
        return combined
    }

    private suspend fun runNode(node: Node<State>, state: State): NodeOutput<State> =
        wrapFailure({ NodeExecutionException(node.name, it) }) { node.run(state) }

    /**
     * Runs [block], which calls code of the application (a node, the condition of an edge or the
     * reducer), and rethrows what it throws as the exception that [failure] builds. That includes a
     * [LangGraphException], such as the failure of a graph that a node runs, so the caller always
     * learns which part of this graph failed.
     */
    private suspend inline fun <T> wrapFailure(failure: (Exception) -> LangGraphException, block: () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            // Propagate a real cancellation of the run. If the run is still active, the code cancelled
            // only itself (for example its own withTimeout expired), which is a failure of that code.
            currentCoroutineContext().ensureActive()
            throw failure(e)
        } catch (e: Exception) {
            throw failure(e)
        }

    /** Returns the nodes to run after [currentNodes], without [END]. */
    private suspend fun resolveNextNodes(currentNodes: List<String>, state: State): List<String> {
        val nextNodes = mutableListOf<String>()
        for (node in currentNodes) {
            val conditionalEdge = conditionalEdges[node]
            if (conditionalEdge != null) {
                nextNodes.add(route(conditionalEdge, state))
            } else {
                nextNodes.addAll(edges[node].orEmpty())
            }
        }
        return nextNodes.distinct() - END
    }

    private suspend fun route(edge: ConditionalEdge<State>, state: State): String {
        val target = wrapFailure({ EdgeConditionException(edge.from, it) }) { edge.condition(state) }
        val allowed = edge.targets?.contains(target) ?: (target == END || target in nodes)
        if (!allowed) throw InvalidRouteException(edge.from, target)
        return target
    }

    private fun validateInterrupts(config: GraphConfig<State>) {
        val unknown = (config.interruptBefore + config.interruptAfter) - nodes.keys
        if (unknown.isNotEmpty()) {
            throw GraphValidationException("GraphConfig interrupts refer to nodes that are not in the graph: $unknown")
        }
    }

    private fun GraphEvent<State>.toResult(): GraphResult<State> =
        when (this) {
            is GraphEvent.Completed -> GraphResult.Completed(state)
            is GraphEvent.Interrupted -> GraphResult.Interrupted(state, nextNodes)
            is GraphEvent.NodeStarted, is GraphEvent.NodeProgress, is GraphEvent.NodeCompleted, is GraphEvent.StepCompleted ->
                error("A graph stream always ends with Completed or Interrupted")
        }
}
