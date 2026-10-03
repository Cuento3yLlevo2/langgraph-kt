package org.langgraphkt

import kotlin.jvm.JvmName

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
 *
 * [node] returns a [NodeRef]. Using the references with [then] and [conditionalEdge] instead of
 * repeating node names lets the compiler catch typos:
 *
 * ```kotlin
 * val graph = StateGraph<AgentState> {
 *     val research = node("research") { ... }
 *     val write = node("write") { ... }
 *
 *     START then research
 *     conditionalEdge(research, targets = setOf(write, NodeRef.END)) { state ->
 *         if (state.notes.isEmpty()) NodeRef.END else write
 *     }
 *     write then END
 * }.compile()
 * ```
 */
public class StateGraph<State> {
    private val nodes = mutableMapOf<String, Node<State>>()
    private val edges = mutableListOf<Edge>()
    private val conditionalEdges = mutableListOf<ConditionalEdge<State>>()

    /**
     * Adds a node named [name] that runs [action] and returns a reference to it for use with [then].
     *
     * @throws GraphValidationException if the name is blank, reserved ([START], [END]) or already used.
     */
    public fun node(name: String, action: NodeAction<State>): NodeRef = add(stateNode(name, action))

    /**
     * Adds a node whose job is split in two, so that it can run next to other nodes without a
     * [Reducer]. [work] does the slow part, such as a call to a model or a service, and returns a
     * result. [update] writes that result into the state.
     *
     * ```kotlin
     * val web = node("web", work = { searchWeb(it.question) }) { state, found ->
     *     state.copy(findings = state.findings + found)
     * }
     * ```
     *
     * When several nodes run in the same step, their [work] runs in parallel on the same state.
     * Their updates are then applied one after another, in the order the nodes were added to the
     * graph, each to the state that the previous one produced. No change is lost, and if two nodes
     * write the same property, the node that was added later wins.
     *
     * [update] must only build the new state. The engine may call it more than once for one run of
     * the node.
     *
     * @throws GraphValidationException if the name is blank, reserved ([START], [END]) or already used.
     */
    public fun <Result> node(name: String, work: suspend (State) -> Result, update: suspend (State, Result) -> State): NodeRef =
        add(workNode(name, work, update))

    private fun add(node: Node<State>): NodeRef {
        val name = node.name
        if (name.isBlank()) throw GraphValidationException("Node name must not be blank.")
        if (name == START || name == END) throw GraphValidationException("'$name' is a reserved node name.")
        if (name in nodes) throw GraphValidationException("Node with name '$name' already exists.")
        nodes[name] = node
        return NodeRef(name)
    }

    /**
     * Adds a static edge: after [from] finishes, [to] runs. Several static edges from the same node
     * fan out and run their targets in parallel. See [compile] for when that needs a [Reducer].
     */
    public fun edge(from: String, to: String) {
        edges.add(Edge(from, to))
    }

    /**
     * Adds a static edge from this node to [to] and returns [to], so edges can be chained:
     * `START then research then write then END`.
     */
    public infix fun NodeRef.then(to: NodeRef): NodeRef = to.also { edge(name, it.name) }

    /** Adds a static edge from this node to the node named [to], usually [END]. */
    public infix fun NodeRef.then(to: String): String = to.also { edge(name, it) }

    /** Adds a static edge from the node named by this string, usually [START], to [to]. */
    public infix fun String.then(to: NodeRef): NodeRef = to.also { edge(this, it.name) }

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

    /** Adds a conditional edge from the node referenced by [from]. See the overload taking a node name. */
    public fun conditionalEdge(from: NodeRef, targets: Set<String>? = null, condition: EdgeCondition<State>) {
        conditionalEdge(from.name, targets, condition)
    }

    /**
     * Adds a conditional edge that works with references instead of names: [condition] returns one
     * of [targets], so a misspelled node is a compiler error.
     *
     * ```kotlin
     * conditionalEdge(check, targets = setOf(write, NodeRef.END)) { draft ->
     *     if (draft.isGood) NodeRef.END else write
     * }
     * ```
     *
     * To finish the branch, list [NodeRef.END] in [targets] and return it.
     */
    @JvmName("conditionalEdgeToRefs")
    public fun conditionalEdge(from: NodeRef, targets: Set<NodeRef>, condition: suspend (State) -> NodeRef) {
        conditionalEdge(from.name, targets, condition)
    }

    /**
     * Adds a conditional edge that works with references, from the node named [from], usually
     * [START]. See the overload taking a node reference.
     */
    @JvmName("conditionalEdgeToRefs")
    public fun conditionalEdge(from: String, targets: Set<NodeRef>, condition: suspend (State) -> NodeRef) {
        conditionalEdge(from, targets.mapTo(mutableSetOf()) { it.name }) { state -> condition(state).name }
    }

    /**
     * Validates the graph and returns an executable [CompiledGraph].
     *
     * @param reducer merges the states of nodes that ran in the same step and each returned a whole
     * state. Required when two such nodes can run in the same step. Nodes added with a `work` and an
     * `update` do not need it.
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
            edges
                .groupBy({ it.from }, { it.to })
                .filterValues { targets -> targets.count { nodes[it]?.returnsState == true } > 1 }
                .keys
                .forEach {
                    problems +=
                        "Node '$it' fans out to several nodes that return a whole state, " +
                        "so compile() needs a Reducer, or those nodes need a work and an update"
                }
        }

        // Reachability is only decidable when every conditional edge declares its targets.
        if (problems.isEmpty() && conditionalEdges.all { it.targets != null }) {
            (nodes.keys - reachableNodes()).forEach { problems += "Node '$it' is not reachable from START" }
        }

        // Branches of a fan-out can also meet later in the graph, away from the node that fans out.
        if (problems.isEmpty() && reducer == null) {
            parallelStateNodes()?.let { (first, second) ->
                problems +=
                    "Nodes '$first' and '$second' can run in the same step and both return a whole state, " +
                    "so compile() needs a Reducer, or those nodes need a work and an update"
            }
        }
        return problems
    }

    /**
     * Returns two nodes that each return a whole state and can run in the same step, or `null` if
     * there are none. Two nodes can run in the same step when they are targets of the same fan-out,
     * or when they follow two nodes that can. A conditional edge without declared targets may lead
     * to any node.
     */
    private fun parallelStateNodes(): Pair<String, String>? {
        val routes = conditionalEdges.associateBy { it.from }
        val staticTargets = edges.groupBy({ it.from }, { it.to })

        fun successors(node: String): Collection<String> {
            val route = routes[node] ?: return staticTargets[node].orEmpty()
            return route.targets ?: nodes.keys
        }

        val together = mutableSetOf<Pair<String, String>>()
        val queue = ArrayDeque<Pair<String, String>>()

        fun add(first: String, second: String) {
            if (first != second && first != END && second != END && together.add(first to second)) queue.add(first to second)
        }

        for (targets in staticTargets.values) {
            for (first in targets) for (second in targets) add(first, second)
        }
        while (queue.isNotEmpty()) {
            val (first, second) = queue.removeFirst()
            for (afterFirst in successors(first)) for (afterSecond in successors(second)) add(afterFirst, afterSecond)
        }
        return together.firstOrNull { (first, second) -> nodes.getValue(first).returnsState && nodes.getValue(second).returnsState }
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
