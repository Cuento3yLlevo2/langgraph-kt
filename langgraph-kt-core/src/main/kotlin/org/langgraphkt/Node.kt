package org.langgraphkt

/**
 * A Node represents a unit of work in the state graph.
 * It is defined as a suspending function that takes the current state and returns an updated state.
 * Immutability constraint: The node MUST NOT mutate the input state directly.
 * It should return a new instance or a copy.
 */
typealias NodeAction<State> = suspend (State) -> State

data class Node<State>(
    val name: String,
    val action: NodeAction<State>
)
