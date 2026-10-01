package org.langgraphkt

/**
 * Builder for a graph of nodes and edges over an immutable [State].
 *
 * ```kotlin
 * val graph = StateGraph<AgentState> {
 *     node("research") { state -> state.copy(notes = search(state.question)) }
 *     node("write") { state -> state.copy(answer = draft(state.notes)) }
 *
 *     edge(START, "research")
 *     conditionalEdge("research", targets = setOf("write", END)) { state ->
 *         if (state.notes.isEmpty()) END else "write"
 *     }
 *     edge("write", END)
 * }.compile()
 * ```
 *
 * Execution starts at [START] and ends when every active branch reaches [END]. A node with no
 * outgoing edge routes to [END].
 */
public class StateGraph<State> {
    private val nodes = mutableMapOf<String, Node<State>>()
    private val edges = mutableListOf<Edge>()
    private val conditionalEdges = mutableListOf<ConditionalEdge<State>>()

    /**
     * Adds a node named [name] that runs [action].
     *
     * @throws GraphValidationException if the name is blank, reserved ([START], [END]) or already used.
     */
    public fun node(name: String, action: NodeAction<State>) {
        if (name.isBlank()) throw GraphValidationException("Node name must not be blank.")
        if (name == START || name == END) throw GraphValidationException("'$name' is a reserved node name.")
        if (name in nodes) throw GraphValidationException("Node with name '$name' already exists.")
        nodes[name] = Node(name, action)
    }

    /**
     * Adds a static edge: after [from] finishes, [to] runs. Several static edges from the same node
     * fan out and run their targets in parallel, which requires a [Reducer] at [compile] time.
     */
    public fun edge(from: String, to: String) {
        edges.add(Edge(from, to))
    }

    /**
     * Adds a conditional edge: after [from] finishes, [condition] picks the next node from the state.
     *
     * Declare the possible [targets] whenever you can. They are checked when the graph is compiled,
     * the value returned by [condition] is checked against them at run time, and they let [compile]
     * detect unreachable nodes. With `targets = null` the condition may return any node name.
     *
     * A node can have either one conditional edge or static edges, not both.
     */
    public fun conditionalEdge(from: String, targets: Set<String>? = null, condition: EdgeCondition<State>) {
        conditionalEdges.add(ConditionalEdge(from, targets, condition))
    }

    /**
     * Validates the graph and returns an executable [CompiledGraph].
     *
     * @param reducer merges the states produced by nodes that ran in parallel. Required when any
     * node (or [START]) has more than one static edge.
     * @throws GraphValidationException listing every problem found in the graph definition.
     */
    public fun compile(reducer: Reducer<State>? = null): CompiledGraph<State> {
        val problems = validate(reducer)
        if (problems.isNotEmpty()) {
            throw GraphValidationException(
                if (problems.size == 1) problems.single() else "Invalid graph:\n" + problems.joinToString("\n") { "- $it" },
            )
        }

        return CompiledGraph(
            nodes = nodes.toMap(),
            edges = edges.groupBy({ it.from }, { it.to }),
            conditionalEdges = conditionalEdges.associateBy { it.from },
            reducer = reducer,
        )
    }

    private fun validate(reducer: Reducer<State>?): List<String> {
        val problems = mutableListOf<String>()
        val sources = nodes.keys + START
        val destinations = nodes.keys + END

        if (edges.none { it.from == START } && conditionalEdges.none { it.from == START }) {
            problems += "Graph must have at least one edge originating from START"
        }

        for (edge in edges) {
            if (edge.from !in sources) problems += "Edge references unknown from-node: ${edge.from}"
            if (edge.to !in destinations) problems += "Edge references unknown to-node: ${edge.to}"
        }
        edges.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach {
            problems += "Duplicate edge from '${it.from}' to '${it.to}'"
        }

        for (edge in conditionalEdges) {
            if (edge.from !in sources) problems += "Conditional edge references unknown from-node: ${edge.from}"
            edge.targets?.filter { it !in destinations }?.forEach {
                problems += "Conditional edge from '${edge.from}' references unknown target: $it"
            }
            if (edge.targets?.isEmpty() == true) problems += "Conditional edge from '${edge.from}' declares no targets"
        }
        val conditionalSources = conditionalEdges.groupingBy { it.from }.eachCount()
        conditionalSources.filterValues { it > 1 }.keys.forEach {
            problems += "Node '$it' has more than one conditional edge"
        }
        conditionalSources.keys.filter { from -> edges.any { it.from == from } }.forEach {
            problems += "Node '$it' has both a conditional edge and static edges"
        }

        if (reducer == null) {
            edges.groupingBy { it.from }.eachCount().filterValues { it > 1 }.keys.forEach {
                problems += "Node '$it' fans out to several nodes, so compile() needs a Reducer to merge their results"
            }
        }

        // Reachability is only decidable when every conditional edge declares its targets.
        if (problems.isEmpty() && conditionalEdges.all { it.targets != null }) {
            (nodes.keys - reachableNodes()).forEach { problems += "Node '$it' is not reachable from START" }
        }
        return problems
    }

    private fun reachableNodes(): Set<String> {
        val successors: Map<String, List<String>> =
            (edges.map { it.from to it.to } + conditionalEdges.flatMap { edge -> edge.targets.orEmpty().map { edge.from to it } })
                .groupBy({ it.first }, { it.second })
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque(listOf(START))
        while (queue.isNotEmpty()) {
            successors[queue.removeFirst()].orEmpty().filter(visited::add).forEach(queue::add)
        }
        return visited
    }
}

/**
 * DSL entry point for building a [StateGraph].
 */
public fun <State> StateGraph(block: StateGraph<State>.() -> Unit): StateGraph<State> = StateGraph<State>().apply(block)
