package org.langgraphkt

data class Checkpoint<State>(
    val state: State,
    val nextNode: String
)

interface Checkpointer<State> {
    suspend fun save(threadId: String, checkpoint: Checkpoint<State>)
    suspend fun load(threadId: String): Checkpoint<State>?
}

class MemoryCheckpointer<State> : Checkpointer<State> {
    private val memory = mutableMapOf<String, Checkpoint<State>>()

    override suspend fun save(threadId: String, checkpoint: Checkpoint<State>) {
        memory[threadId] = checkpoint
    }

    override suspend fun load(threadId: String): Checkpoint<State>? {
        return memory[threadId]
    }
}

data class GraphConfig<State>(
    val threadId: String = "default",
    val checkpointer: Checkpointer<State>? = null,
    val interruptBefore: List<String> = emptyList(),
    val interruptAfter: List<String> = emptyList()
)
