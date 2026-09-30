package org.langgraphkt

class CompiledGraph<State>(
    val nodes: Map<String, Node<State>>,
    val edges: Map<String, List<Edge>>,
    val conditionalEdges: Map<String, List<ConditionalEdge<State>>>
) {
    /**
     * Executes the compiled graph from START to END.
     */
    suspend fun invoke(initialState: State): State {
        var currentState = initialState
        var currentNode = START

        while (currentNode != END) {
            val condEdges = conditionalEdges[currentNode]
            val standardEdges = edges[currentNode]

            val nextNode = if (!condEdges.isNullOrEmpty()) {
                require(condEdges.size == 1) { "Multiple conditional edges from a single node are not supported" }
                condEdges.first().condition(currentState)
            } else if (!standardEdges.isNullOrEmpty()) {
                require(standardEdges.size == 1) { "Multiple standard edges from a single node require a conditional edge" }
                standardEdges.first().to
            } else {
                END
            }

            if (nextNode == END) {
                break
            }

            val node = nodes[nextNode] ?: throw IllegalStateException("Node '$nextNode' not found")
            currentState = node.action(currentState)
            currentNode = nextNode
        }

        return currentState
    }
}
