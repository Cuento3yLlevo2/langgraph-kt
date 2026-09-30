package org.langgraphkt

/**
 * An Edge represents a static transition from one node to another.
 */
data class Edge(
    val from: String,
    val to: String
)

/**
 * A ConditionalEdge routes to different target nodes based on the current state.
 */
typealias EdgeCondition<State> = suspend (State) -> String

data class ConditionalEdge<State>(
    val from: String,
    val condition: EdgeCondition<State>
)
