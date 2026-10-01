package org.langgraphkt

/**
 * A saved point in a graph run.
 *
 * @property state the graph state after the last executed step.
 * @property nextNodes the nodes that run when the thread is resumed. Empty when the run completed.
 * @property step the number of steps the thread has executed so far.
 */
public data class Checkpoint<State>(
    val state: State,
    val nextNodes: List<String>,
    val step: Int = 0,
) {
    /** `true` when the run reached [END] and there is nothing left to resume. */
    public val isComplete: Boolean get() = nextNodes.isEmpty()
}

/**
 * Persists the latest [Checkpoint] of each thread so a run can pause and resume later, even in a
 * different process.
 */
public interface Checkpointer<State> {
    /** Stores [checkpoint] as the latest checkpoint of [threadId], replacing any previous one. */
    public suspend fun save(threadId: String, checkpoint: Checkpoint<State>)

    /** Returns the latest checkpoint of [threadId], or `null` if the thread has none. */
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
