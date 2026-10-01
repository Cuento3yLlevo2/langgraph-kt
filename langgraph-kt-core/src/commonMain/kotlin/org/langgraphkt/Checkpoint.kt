package org.langgraphkt

/**
 * A saved point in a graph run: the [state] at that point and the nodes that run next.
 */
public data class Checkpoint<State>(
    val state: State,
    val nextNodes: List<String>,
) {
    public val nextNode: String get() = nextNodes.firstOrNull() ?: END
    public constructor(state: State, nextNode: String) : this(state, listOf(nextNode))
}

/**
 * Persists [Checkpoint]s per thread so a run can pause and resume later.
 */
public interface Checkpointer<State> {
    public suspend fun save(threadId: String, checkpoint: Checkpoint<State>)

    public suspend fun load(threadId: String): Checkpoint<State>?
}

/**
 * A [Checkpointer] that keeps checkpoints in memory. Intended for tests and short-lived processes.
 */
public class MemoryCheckpointer<State> : Checkpointer<State> {
    private val memory = mutableMapOf<String, Checkpoint<State>>()

    override suspend fun save(threadId: String, checkpoint: Checkpoint<State>) {
        memory[threadId] = checkpoint
    }

    override suspend fun load(threadId: String): Checkpoint<State>? = memory[threadId]
}

/**
 * Per-run settings for a [CompiledGraph].
 */
public data class GraphConfig<State>(
    val threadId: String = "default",
    val checkpointer: Checkpointer<State>? = null,
    val interruptBefore: List<String> = emptyList(),
    val interruptAfter: List<String> = emptyList(),
    val maxIterations: Int = 25,
)
