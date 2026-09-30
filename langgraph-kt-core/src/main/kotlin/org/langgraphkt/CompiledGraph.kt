package org.langgraphkt

class CompiledGraph<State>(
    val nodes: Map<String, Node<State>>,
    val edges: Map<String, List<Edge>>,
    val conditionalEdges: Map<String, List<ConditionalEdge<State>>>
) {
    /**
     * Executes the compiled graph from START to END.
     */
    suspend fun invoke(initialState: State, config: GraphConfig<State>? = null, resume: Boolean = false): State {
        var currentState = initialState
        var nextNodeToExecute = START
        var justResumed = resume

        if (config?.checkpointer != null) {
            val checkpoint = config.checkpointer.load(config.threadId)
            if (checkpoint != null) {
                currentState = if (resume) initialState else checkpoint.state
                nextNodeToExecute = checkpoint.nextNode
            }
        }

        while (nextNodeToExecute != END) {
            if (config != null && config.interruptBefore.contains(nextNodeToExecute) && !justResumed) {
                config.checkpointer?.save(config.threadId, Checkpoint(currentState, nextNodeToExecute))
                return currentState
            }

            if (nextNodeToExecute != START) {
                val node = nodes[nextNodeToExecute] ?: throw IllegalStateException("Node '$nextNodeToExecute' not found")
                currentState = node.action(currentState)
            }
            justResumed = false

            val condEdges = conditionalEdges[nextNodeToExecute]
            val standardEdges = edges[nextNodeToExecute]

            val nextNode = if (!condEdges.isNullOrEmpty()) {
                require(condEdges.size == 1) { "Multiple conditional edges from a single node are not supported" }
                condEdges.first().condition(currentState)
            } else if (!standardEdges.isNullOrEmpty()) {
                require(standardEdges.size == 1) { "Multiple standard edges from a single node require a conditional edge" }
                standardEdges.first().to
            } else {
                END
            }

            if (config != null && config.interruptAfter.contains(nextNodeToExecute)) {
                config.checkpointer?.save(config.threadId, Checkpoint(currentState, nextNode))
                return currentState
            }

            nextNodeToExecute = nextNode
        }

        config?.checkpointer?.save(config.threadId, Checkpoint(currentState, END))
        return currentState
    }
}
