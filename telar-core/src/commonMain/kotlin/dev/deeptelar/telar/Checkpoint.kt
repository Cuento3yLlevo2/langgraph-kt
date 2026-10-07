package dev.deeptelar.telar

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A saved point in a graph run.
 *
 * @property state the graph state after the last executed step, or the input of the run when no
 * step has finished yet.
 * @property nextNodes the nodes that run when the thread is resumed. Empty when the run completed.
 * @property step the number of steps the thread has executed so far. `0` for the checkpoint that is
 * saved when a run starts.
 * @property interruptedBefore `true` when the run paused before [nextNodes] because of
 * [GraphConfig.interruptBefore], or because one of them called [interrupt] when it ran.
 * [CompiledGraph.resume] then runs those nodes without pausing before them again. A checkpoint saved
 * for any other reason is `false`, so resuming it still pauses before a node listed in
 * [GraphConfig.interruptBefore].
 */
public data class Checkpoint<State>(
    val state: State,
    val nextNodes: List<String>,
    val step: Int = 0,
    val interruptedBefore: Boolean = false,
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

    /** Removes the checkpoint of [threadId]. Does nothing if the thread has none. */
    public suspend fun delete(threadId: String)
}

/**
 * A [Checkpointer] that keeps checkpoints in memory. Intended for tests and short-lived processes.
 * Safe to share between coroutines and threads.
 */
public class MemoryCheckpointer<State> : Checkpointer<State> {
    private val mutex = Mutex()
    private val memory = mutableMapOf<String, Checkpoint<State>>()

    override suspend fun save(threadId: String, checkpoint: Checkpoint<State>) {
        mutex.withLock { memory[threadId] = checkpoint }
    }

    override suspend fun load(threadId: String): Checkpoint<State>? = mutex.withLock { memory[threadId] }

    override suspend fun delete(threadId: String) {
        mutex.withLock { memory.remove(threadId) }
    }
}
