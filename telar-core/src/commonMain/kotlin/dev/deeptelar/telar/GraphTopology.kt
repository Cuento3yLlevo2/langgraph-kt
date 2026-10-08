package dev.deeptelar.telar

/**
 * The structure of a compiled graph: its nodes and the edges between them. Read it from
 * [CompiledGraph.topology] to draw the graph or to check it in a test.
 *
 * @property nodes the node names in the order they were added, without [START] and [END].
 * @property edges every edge that is known before the graph runs: the static edges, the declared
 * targets of conditional edges, and an edge to [END] for each node that has no outgoing edge.
 * @property dynamicRoutes the nodes whose conditional edge declares no targets. Such a node can
 * route to any node or to [END], so it has no entries in [edges].
 * @property subgraphs the structure of the graph that a node added with [StateGraph.subgraph] runs,
 * by the name of that node. The node is in [nodes] and [edges] like any other.
 */
public data class GraphTopology(
    val nodes: List<String>,
    val edges: List<GraphEdge>,
    val dynamicRoutes: Set<String>,
    val subgraphs: Map<String, GraphTopology> = emptyMap(),
) {
    /** The nodes (or [END]) that [node] can lead to according to [edges]. */
    public fun successors(node: String): List<String> = edges.filter { it.from == node }.map { it.to }
}

/**
 * A transition from [from] to [to]. Either end can be [START] or [END].
 *
 * @property isConditional `true` when [to] is one of the declared targets of a conditional edge, so
 * the transition only happens when the edge's condition picks it.
 */
public data class GraphEdge(
    val from: String,
    val to: String,
    val isConditional: Boolean = false,
)
