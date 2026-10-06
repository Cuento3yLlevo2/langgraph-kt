package dev.deeptelar.telar

/**
 * Converts a graph state to and from a string so a [Checkpointer] can persist it.
 */
public interface StateSerializer<State> {
    public fun serialize(state: State): String

    public fun deserialize(data: String): State
}
