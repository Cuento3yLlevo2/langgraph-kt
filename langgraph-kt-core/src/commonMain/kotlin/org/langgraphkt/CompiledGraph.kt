package org.langgraphkt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last

/**
 * An executable graph produced by [StateGraph.compile].
 */
public class CompiledGraph<State> internal constructor(
    internal val nodes: Map<String, Node<State>>,
    internal val edges: Map<String, List<String>>,
    internal val conditionalEdges: Map<String, ConditionalEdge<State>>,
    internal val reducer: Reducer<State>? = null,
) {
    /**
     * Executes the compiled graph from START to END, returning only the final state.
     */
    public suspend fun invoke(initialState: State, config: GraphConfig<State>? = null, resume: Boolean = false): State =
        stream(initialState, config, resume).last()

    /**
     * Executes the compiled graph from START to END, emitting the state after each step.
     */
    public fun stream(initialState: State, config: GraphConfig<State>? = null, resume: Boolean = false): Flow<State> =
        flow {
            var currentState = initialState
            var currentNodesToExecute = listOf(START)
            var justResumed = resume

            if (config?.checkpointer != null) {
                val checkpoint = config.checkpointer.load(config.threadId)
                if (checkpoint != null) {
                    currentState = if (resume) initialState else checkpoint.state
                    currentNodesToExecute = checkpoint.nextNodes
                }
            }

            emit(currentState)

            var iterations = 0
            val maxIters = config?.maxIterations ?: 25

            while (currentNodesToExecute.isNotEmpty() && !currentNodesToExecute.contains(END)) {
                if (iterations >= maxIters) {
                    throw MaxIterationsExceededException(maxIters)
                }
                iterations++

                if (config != null && currentNodesToExecute.any { config.interruptBefore.contains(it) } && !justResumed) {
                    config.checkpointer?.save(config.threadId, Checkpoint(currentState, currentNodesToExecute))
                    return@flow
                }

                val nodesToRun = currentNodesToExecute.filter { it != START }
                if (nodesToRun.isNotEmpty()) {
                    val updates =
                        coroutineScope {
                            nodesToRun
                                .map { nodeName ->
                                    async {
                                        runNode(nodes.getValue(nodeName), currentState)
                                    }
                                }.awaitAll()
                        }

                    currentState =
                        if (updates.size > 1) {
                            checkNotNull(reducer) { "compile() guarantees a reducer for graphs that fan out" }
                                .reduce(currentState, updates)
                        } else if (updates.size == 1) {
                            updates.first()
                        } else {
                            currentState
                        }

                    emit(currentState)
                }
                justResumed = false

                if (config != null && currentNodesToExecute.any { config.interruptAfter.contains(it) }) {
                    val nextNodes = resolveNextNodes(currentNodesToExecute, currentState)
                    config.checkpointer?.save(config.threadId, Checkpoint(currentState, nextNodes))
                    return@flow
                }

                currentNodesToExecute = resolveNextNodes(currentNodesToExecute, currentState)
            }

            config?.checkpointer?.save(config.threadId, Checkpoint(currentState, listOf(END)))
        }

    private suspend fun runNode(node: Node<State>, state: State): State =
        try {
            node.action(state)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LangGraphException) {
            throw e
        } catch (e: Exception) {
            throw NodeExecutionException(node.name, e)
        }

    private suspend fun resolveNextNodes(currentNodes: List<String>, currentState: State): List<String> {
        val nextNodes = mutableListOf<String>()
        for (node in currentNodes) {
            val conditionalEdge = conditionalEdges[node]
            val staticTargets = edges[node]

            if (conditionalEdge != null) {
                nextNodes.add(route(conditionalEdge, currentState))
            } else if (!staticTargets.isNullOrEmpty()) {
                nextNodes.addAll(staticTargets)
            } else {
                nextNodes.add(END)
            }
        }
        return nextNodes.distinct()
    }

    private suspend fun route(edge: ConditionalEdge<State>, state: State): String {
        val target = edge.condition(state)
        val allowed = edge.targets?.contains(target) ?: (target == END || target in nodes)
        if (!allowed) throw InvalidRouteException(edge.from, target)
        return target
    }
}
