package org.langgraphkt

class StateGraph<State> {
    private val nodes = mutableMapOf<String, Node<State>>()
    private val edges = mutableListOf<Edge>()
    private val conditionalEdges = mutableListOf<ConditionalEdge<State>>()

    fun node(name: String, action: NodeAction<State>) {
        require(!nodes.containsKey(name)) { "Node with name '$name' already exists." }
        nodes[name] = Node(name, action)
    }

    fun edge(from: String, to: String) {
        edges.add(Edge(from, to))
    }

    fun conditionalEdge(from: String, condition: EdgeCondition<State>) {
        conditionalEdges.add(ConditionalEdge(from, condition))
    }

    fun compile(reducer: Reducer<State>? = null): CompiledGraph<State> {
        if (edges.none { it.from == START } && conditionalEdges.none { it.from == START }) {
            throw IllegalStateException("Graph must have at least one edge originating from START")
        }

        val allNodeNames = nodes.keys + setOf(START, END)
        for (edge in edges) {
            require(edge.from in allNodeNames) { "Edge references unknown from-node: ${edge.from}" }
            require(edge.to in allNodeNames) { "Edge references unknown to-node: ${edge.to}" }
        }
        for (edge in conditionalEdges) {
            require(edge.from in allNodeNames) { "Conditional edge references unknown from-node: ${edge.from}" }
        }

        return CompiledGraph(
            nodes = nodes.toMap(),
            edges = edges.groupBy { it.from },
            conditionalEdges = conditionalEdges.groupBy { it.from },
            reducer = reducer
        )
    }
}

/**
 * DSL builder function for creating a StateGraph.
 */
fun <State> StateGraph(block: StateGraph<State>.() -> Unit): StateGraph<State> {
    return StateGraph<State>().apply(block)
}
