package org.langgraphkt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last

/**
 * An executable graph produced by [StateGraph.compile]. It is immutable and can be shared and run
 * concurrently; each run is isolated by [GraphConfig.threadId].
 *
 * ## Execution model
 *
 * A run advances in steps. In each step all currently active nodes run in parallel on the same
 * input state, and their results are merged by the [Reducer] when there is more than one. The
 * outgoing edges of those nodes then decide which nodes are active in the next step. A branch that
 * routes to [END] simply stops; the run completes when no nodes are active.
 *
 * A node reached by several branches in the same step runs once. If the branches have different
 * lengths, the node runs once for each step in which a branch reaches it.
 *
 * Nodes run in the coroutine context of the caller. If a node fails, the other nodes of that step
 * are cancelled and the failure is rethrown as [NodeExecutionException].
 */
public class CompiledGraph<State> internal constructor(
    internal val nodes: Map<String, Node<State>>,
    internal val edges: Map<String, List<String>>,
    internal val conditionalEdges: Map<String, ConditionalEdge<State>>,
    internal val reducer: Reducer<State>? = null,
) {
    /**
     * Runs the graph from [START] with [input] until it completes or reaches an interrupt.
     *
     * A call always starts a new run: if [config] has a checkpointer, any earlier checkpoint of the
     * thread is replaced. Use [resume] to continue a paused run.
     *
     * @throws GraphValidationException if [config] names interrupt nodes that are not in the graph.
     * @throws MaxIterationsExceededException if the run needs more than [GraphConfig.maxIterations] steps.
     * @throws NodeExecutionException if a node throws.
     * @throws InvalidRouteException if a conditional edge returns an invalid target.
     */
    public suspend fun invoke(input: State, config: GraphConfig<State> = GraphConfig()): GraphResult<State> =
        stream(input, config).last().toResult()

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
        streamResume(config, update).last().toResult()

    /**
     * Like [invoke], but returns a cold [Flow] that emits a [GraphEvent] after every step and ends
     * with [GraphEvent.Completed] or [GraphEvent.Interrupted]. Nothing runs until the flow is
     * collected, and each collection is a new run.
     */
    public fun stream(input: State, config: GraphConfig<State> = GraphConfig()): Flow<GraphEvent<State>> =
        flow {
            validateInterrupts(config)
            run(config, RunStart(input, resolveNextNodes(listOf(START), input), step = 0, resumed = false))
        }

    /** Like [resume], but returns a cold [Flow] of [GraphEvent]s. See [stream]. */
    public fun streamResume(config: GraphConfig<State>, update: suspend (State) -> State = { it }): Flow<GraphEvent<State>> =
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
            run(config, RunStart(update(checkpoint.state), checkpoint.nextNodes, checkpoint.step, resumed = true))
        }

    private class RunStart<State>(
        val state: State,
        val activeNodes: List<String>,
        val step: Int,
        val resumed: Boolean,
    )

    private suspend fun FlowCollector<GraphEvent<State>>.run(config: GraphConfig<State>, start: RunStart<State>) {
        var state = start.state
        var activeNodes = start.activeNodes
        var step = start.step
        // Resuming continues past the boundary the run paused at, so that boundary must not pause again.
        var skipInterruptBefore = start.resumed
        var executedSteps = 0

        suspend fun checkpoint(nextNodes: List<String>) {
            config.checkpointer?.save(config.threadId, Checkpoint(state, nextNodes, step))
        }

        while (activeNodes.isNotEmpty()) {
            if (!skipInterruptBefore && activeNodes.any { it in config.interruptBefore }) {
                checkpoint(activeNodes)
                emit(GraphEvent.Interrupted(state, activeNodes))
                return
            }
            skipInterruptBefore = false

            if (executedSteps >= config.maxIterations) throw MaxIterationsExceededException(config.maxIterations)
            executedSteps++
            step++

            state = runStep(activeNodes, state)
            val nextNodes = resolveNextNodes(activeNodes, state)
            checkpoint(nextNodes)
            emit(GraphEvent.StepCompleted(step, activeNodes, state))

            if (nextNodes.isNotEmpty() && activeNodes.any { it in config.interruptAfter }) {
                emit(GraphEvent.Interrupted(state, nextNodes))
                return
            }
            activeNodes = nextNodes
        }

        if (executedSteps == 0) checkpoint(emptyList())
        emit(GraphEvent.Completed(state))
    }

    private suspend fun runStep(activeNodes: List<String>, state: State): State {
        if (activeNodes.size == 1) return runNode(nodes.getValue(activeNodes.single()), state)

        val updates =
            coroutineScope {
                activeNodes.map { name -> async { runNode(nodes.getValue(name), state) } }.awaitAll()
            }
        return checkNotNull(reducer) { "compile() guarantees a reducer for graphs that fan out" }.reduce(state, updates)
    }

    private suspend fun runNode(node: Node<State>, state: State): State =
        try {
            node.action(state)
        } catch (e: CancellationException) {
            // Propagate a real cancellation of the run. If the run is still active, the node cancelled
            // only itself (for example its own withTimeout expired), which is a failure of the node.
            currentCoroutineContext().ensureActive()
            throw NodeExecutionException(node.name, e)
        } catch (e: LangGraphException) {
            throw e
        } catch (e: Exception) {
            throw NodeExecutionException(node.name, e)
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
        val target = edge.condition(state)
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
            is GraphEvent.StepCompleted -> error("A graph stream always ends with Completed or Interrupted")
        }
}
