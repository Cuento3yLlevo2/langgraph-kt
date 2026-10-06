package dev.deeptelar.telar

/**
 * A routing function for a conditional edge: it inspects the current state and returns the name of
 * the node to run next, or [END] to finish.
 */
public typealias EdgeCondition<State> = suspend (State) -> String

/** A static transition from one node to another. */
internal data class Edge(
    val from: String,
    val to: String,
)

/** A transition whose target is chosen at run time by [condition], optionally restricted to [targets]. */
internal data class ConditionalEdge<State>(
    val from: String,
    val targets: Set<String>?,
    val condition: EdgeCondition<State>,
)
