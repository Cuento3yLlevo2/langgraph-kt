package org.langgraphkt

/**
 * The work a node performs: a suspending function that takes the current state and returns the
 * updated state.
 *
 * The action must not mutate the state it receives. Return a new instance instead, typically with
 * `state.copy(...)`.
 */
public typealias NodeAction<State> = suspend (State) -> State

/**
 * A reference to a node added with [StateGraph.node]. Use it with [StateGraph.then] and
 * [StateGraph.conditionalEdge] to connect nodes without repeating their names, and read [name]
 * where a node name is needed (for example in [GraphConfig.interruptBefore]).
 */
public class NodeRef internal constructor(
    public val name: String,
) {
    override fun toString(): String = name

    public companion object {
        /**
         * [org.langgraphkt.END] as a reference, for a conditional edge that works with references:
         * list it in the edge's targets and return it to finish the branch.
         */
        public val END: NodeRef = NodeRef(org.langgraphkt.END)
    }
}

/**
 * A node of a graph.
 *
 * @property returnsState `true` for a node that returns a whole state. Two such nodes in one step
 * need a [Reducer]. `false` for a node with a `work` and an `update`.
 */
internal class Node<State>(
    val name: String,
    val returnsState: Boolean,
    val run: suspend (State) -> NodeOutput<State>,
)

/**
 * What a node produced in one step.
 *
 * @property state the state after this node alone. A step with a single node continues with it, and
 * [GraphEvent.NodeCompleted] reports it.
 * @property update writes the result of a node with a `work` and an `update` into the state it is
 * given. `null` for a node that returns a whole state.
 */
internal class NodeOutput<State>(
    val state: State,
    val update: (suspend (State) -> State)?,
)

/** A node that returns a whole state. */
internal fun <State> stateNode(name: String, action: NodeAction<State>): Node<State> =
    Node(name, returnsState = true) { NodeOutput(action(it), update = null) }

/** A node that does its [work] first and then writes the result into the state with [update]. */
internal fun <State, Result> workNode(
    name: String,
    work: suspend (State) -> Result,
    update: suspend (State, Result) -> State,
): Node<State> =
    Node(name, returnsState = false) { state ->
        val result = work(state)
        NodeOutput(update(state, result)) { update(it, result) }
    }
