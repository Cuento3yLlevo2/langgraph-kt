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
 * A reference to a node added with [StateGraph.node]. Use it with [StateGraph.then] to connect
 * nodes without repeating their names, and read [name] where a node name is needed (for example as
 * the return value of a conditional edge or in [GraphConfig.interruptBefore]).
 */
public class NodeRef internal constructor(
    public val name: String,
) {
    override fun toString(): String = name
}

internal data class Node<State>(
    val name: String,
    val action: NodeAction<State>,
)
