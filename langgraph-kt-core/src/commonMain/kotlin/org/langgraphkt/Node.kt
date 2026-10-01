package org.langgraphkt

/**
 * The work a node performs: a suspending function that takes the current state and returns the
 * updated state.
 *
 * The action must not mutate the state it receives. Return a new instance instead, typically with
 * `state.copy(...)`.
 */
public typealias NodeAction<State> = suspend (State) -> State

internal data class Node<State>(
    val name: String,
    val action: NodeAction<State>,
)
