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

internal data class Node<State>(
    val name: String,
    val action: NodeAction<State>,
)
