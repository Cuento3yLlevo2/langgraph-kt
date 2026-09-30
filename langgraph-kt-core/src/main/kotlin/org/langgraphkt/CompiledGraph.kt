package org.langgraphkt

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class CompiledGraph<State>(
    val nodes: Map<String, Node<State>>,
    val edges: Map<String, List<Edge>>,
    val conditionalEdges: Map<String, List<ConditionalEdge<State>>>,
    val reducer: Reducer<State>? = null
) {
    /**
     * Executes the compiled graph from START to END.
     */
    suspend fun invoke(initialState: State, config: GraphConfig<State>? = null, resume: Boolean = false): State {
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

        while (currentNodesToExecute.isNotEmpty() && !currentNodesToExecute.contains(END)) {
            if (config != null && currentNodesToExecute.any { config.interruptBefore.contains(it) } && !justResumed) {
                config.checkpointer?.save(config.threadId, Checkpoint(currentState, currentNodesToExecute))
                return currentState
            }

            val nodesToRun = currentNodesToExecute.filter { it != START }
            if (nodesToRun.isNotEmpty()) {
                val updates = coroutineScope {
                    nodesToRun.map { nodeName ->
                        async {
                            val node = nodes[nodeName] ?: throw IllegalStateException("Node '$nodeName' not found")
                            node.action(currentState)
                        }
                    }.awaitAll()
                }

                currentState = if (updates.size > 1) {
                    requireNotNull(reducer) { "Reducer is required when executing parallel nodes" }
                    reducer.reduce(currentState, updates)
                } else if (updates.size == 1) {
                    updates.first()
                } else {
                    currentState
                }
            }
            justResumed = false

            if (config != null && currentNodesToExecute.any { config.interruptAfter.contains(it) }) {
                val nextNodes = resolveNextNodes(currentNodesToExecute, currentState)
                config.checkpointer?.save(config.threadId, Checkpoint(currentState, nextNodes))
                return currentState
            }

            currentNodesToExecute = resolveNextNodes(currentNodesToExecute, currentState)
        }

        config?.checkpointer?.save(config.threadId, Checkpoint(currentState, listOf(END)))
        return currentState
    }

    private suspend fun resolveNextNodes(currentNodes: List<String>, currentState: State): List<String> {
        val nextNodes = mutableListOf<String>()
        for (node in currentNodes) {
            val condEdges = conditionalEdges[node]
            val standardEdges = edges[node]

            if (!condEdges.isNullOrEmpty()) {
                require(condEdges.size == 1) { "Multiple conditional edges from a single node are not supported" }
                nextNodes.add(condEdges.first().condition(currentState))
            } else if (!standardEdges.isNullOrEmpty()) {
                nextNodes.addAll(standardEdges.map { it.to })
            } else {
                nextNodes.add(END)
            }
        }
        return nextNodes.distinct()
    }
}
