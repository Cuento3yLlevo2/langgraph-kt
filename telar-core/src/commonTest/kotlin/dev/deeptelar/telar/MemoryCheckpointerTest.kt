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
    fun `a thread keeps one checkpoint for each step`() =
        runTest {
            val checkpointer = MemoryCheckpointer<TestState>()
            assertEquals(emptyList(), checkpointer.history("t"))

            checkpointer.save("t", Checkpoint(TestState(0), listOf("a"), step = 0))
            checkpointer.save("t", Checkpoint(TestState(1), listOf("b"), step = 1))
            // The same place of the run, saved again: a pause before "b".
            checkpointer.save("t", Checkpoint(TestState(1), listOf("b"), step = 1, interruptedBefore = true))
            checkpointer.save("t", Checkpoint(TestState(2), listOf("c"), step = 2))
            checkpointer.save("other", Checkpoint(TestState(9), listOf("a"), step = 0))

            assertEquals(
                listOf(
                    Checkpoint(TestState(0), listOf("a"), step = 0),
                    Checkpoint(TestState(1), listOf("b"), step = 1, interruptedBefore = true),
                    Checkpoint(TestState(2), listOf("c"), step = 2),
                ),
                checkpointer.history("t"),
            )

            // A checkpoint of an earlier step takes the place of that step and of what came after it.
            checkpointer.save("t", Checkpoint(TestState(7), listOf("b"), step = 1))
            assertEquals(listOf(TestState(0), TestState(7)), checkpointer.history("t").map { it.state })

            checkpointer.delete("t")
            assertEquals(emptyList(), checkpointer.history("t"))
            assertEquals(1, checkpointer.history("other").size)
        }

    @Test
    fun `maxHistory limits how many checkpoints a thread keeps`() =
        runTest {
            val lastTwo = MemoryCheckpointer<TestState>(maxHistory = 2)
            val latest = MemoryCheckpointer<TestState>(maxHistory = 1)

            repeat(4) { step ->
                lastTwo.save("t", Checkpoint(TestState(step), listOf("a"), step = step))
                latest.save("t", Checkpoint(TestState(step), listOf("a"), step = step))
            }

            assertEquals(listOf(2, 3), lastTwo.history("t").map { it.step })
            assertEquals(listOf(3), latest.history("t").map { it.step })
            assertEquals(3, latest.load("t")?.step)
            assertFailsWith<GraphValidationException> { MemoryCheckpointer<TestState>(maxHistory = 0) }
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
