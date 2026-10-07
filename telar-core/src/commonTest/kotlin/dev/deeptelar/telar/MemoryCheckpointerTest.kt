package dev.deeptelar.telar

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MemoryCheckpointerTest {
    @Test
    fun `save replaces the previous checkpoint and delete removes it`() =
        runTest {
            val checkpointer = MemoryCheckpointer<TestState>()
            assertNull(checkpointer.load("t"))

            checkpointer.save("t", Checkpoint(TestState(1), listOf("a"), step = 1))
            checkpointer.save("t", Checkpoint(TestState(2), listOf("b"), step = 2))
            assertEquals(Checkpoint(TestState(2), listOf("b"), step = 2), checkpointer.load("t"))

            checkpointer.delete("t")
            assertNull(checkpointer.load("t"))
            checkpointer.delete("t")
        }

    @Test
    fun `concurrent saves from many coroutines are all stored`() =
        runTest {
            val checkpointer = MemoryCheckpointer<TestState>()

            withContext(Dispatchers.Default) {
                repeat(500) { i -> launch { checkpointer.save("thread-$i", Checkpoint(TestState(i), emptyList(), step = i)) } }
            }

            repeat(500) { i -> assertEquals(i, checkpointer.load("thread-$i")?.state?.count) }
        }

    @Test
    fun `deleting a paused thread means it can no longer be resumed`() =
        runTest {
            val app =
                StateGraph<TestState> {
                    node("a") { it.copy(count = it.count + 1) }
                    node("b") { it.copy(count = it.count + 1) }
                    edge(START, "a")
                    edge("a", "b")
                }.compile()
            val checkpointer = MemoryCheckpointer<TestState>()
            val config = GraphConfig(checkpointer = checkpointer, interruptBefore = setOf("b"))
            app.invoke(TestState(), config)

            checkpointer.delete(config.threadId)

            assertFailsWith<CheckpointNotFoundException> { app.resume(config) }
        }

    @Test
    fun `blank thread id is rejected`() {
        assertFailsWith<GraphValidationException> { GraphConfig<TestState>(threadId = " ") }
    }
}
