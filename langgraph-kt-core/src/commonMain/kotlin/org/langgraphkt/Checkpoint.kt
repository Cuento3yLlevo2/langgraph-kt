package org.langgraphkt

data class Checkpoint<State>(
    val state: State,
    val nextNodes: List<String>,
) {
    val nextNode: String get() = nextNodes.firstOrNull() ?: END
    constructor(state: State, nextNode: String) : this(state, listOf(nextNode))
}

interface Checkpointer<State> {
    suspend fun save(threadId: String, checkpoint: Checkpoint<State>)

    suspend fun load(threadId: String): Checkpoint<State>?
}

class MemoryCheckpointer<State> : Checkpointer<State> {
    private val memory = mutableMapOf<String, Checkpoint<State>>()

    override suspend fun save(threadId: String, checkpoint: Checkpoint<State>) {
        memory[threadId] = checkpoint
    }

    override suspend fun load(threadId: String): Checkpoint<State>? = memory[threadId]
}

data class GraphConfig<State>(
    val threadId: String = "default",
    val checkpointer: Checkpointer<State>? = null,
    val interruptBefore: List<String> = emptyList(),
    val interruptAfter: List<String> = emptyList(),
    val maxIterations: Int = 25,
)
