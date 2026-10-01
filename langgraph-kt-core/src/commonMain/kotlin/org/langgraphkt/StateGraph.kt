package org.langgraphkt

/**
 * Builder for a graph of nodes and edges over an immutable [State].
 */
public class StateGraph<State> {
    private val nodes = mutableMapOf<String, Node<State>>()
    private val edges = mutableListOf<Edge>()
    private val conditionalEdges = mutableListOf<ConditionalEdge<State>>()

    public fun node(name: String, action: NodeAction<State>) {
        if (name in nodes) throw GraphValidationException("Node with name '$name' already exists.")
        nodes[name] = Node(name, action)
    }

    public fun edge(from: String, to: String) {
        edges.add(Edge(from, to))
    }

    public fun conditionalEdge(from: String, condition: EdgeCondition<State>) {
        conditionalEdges.add(ConditionalEdge(from, condition))
    }

    public fun compile(reducer: Reducer<State>? = null): CompiledGraph<State> {
        if (edges.none { it.from == START } && conditionalEdges.none { it.from == START }) {
            throw GraphValidationException("Graph must have at least one edge originating from START")
        }

        val allNodeNames = nodes.keys + setOf(START, END)
        for (edge in edges) {
            if (edge.from !in allNodeNames) throw GraphValidationException("Edge references unknown from-node: ${edge.from}")
            if (edge.to !in allNodeNames) throw GraphValidationException("Edge references unknown to-node: ${edge.to}")
        }
        for (edge in conditionalEdges) {
            if (edge.from !in allNodeNames) {
                throw GraphValidationException("Conditional edge references unknown from-node: ${edge.from}")
            }
        }

        return CompiledGraph(
            nodes = nodes.toMap(),
            edges = edges.groupBy { it.from },
            conditionalEdges = conditionalEdges.groupBy { it.from },
            reducer = reducer,
        )
    }
}

/**
 * DSL builder function for creating a StateGraph.
 */
public fun <State> StateGraph(block: StateGraph<State>.() -> Unit): StateGraph<State> = StateGraph<State>().apply(block)
