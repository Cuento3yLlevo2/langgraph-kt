package org.langgraphkt

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LastResultTest {
    private val app =
        StateGraph<TestState> {
            node("a") { it.copy(count = it.count + 1) }
            node("b") { if (it.count >= 1000) error("rejected") else it.copy(count = it.count + 10) }

            edge(START, "a")
            edge("a", "b")
            edge("b", END)
        }.compile()

    @Test
    fun `a thread that never ran has no last result`() =
        runTest {
            assertNull(app.lastResult(GraphConfig(checkpointer = MemoryCheckpointer())))
        }

    @Test
    fun `a paused thread reports the pause`() =
        runTest {
            val config = GraphConfig(checkpointer = MemoryCheckpointer<TestState>(), interruptBefore = setOf("b"))
            val paused = app.invoke(TestState(0), config)

            assertEquals(GraphResult.Interrupted(TestState(1), listOf("b")), paused)
            assertEquals(paused, app.lastResult(config))
        }

    @Test
    fun `a finished thread reports its final state`() =
        runTest {
            val config = GraphConfig(checkpointer = MemoryCheckpointer<TestState>())
            val finished = app.invoke(TestState(0), config)

            assertEquals(GraphResult.Completed(TestState(11)), finished)
            assertEquals(finished, app.lastResult(config))
        }

    @Test
    fun `a thread whose node failed reports the step it can resume from`() =
        runTest {
            val config = GraphConfig(checkpointer = MemoryCheckpointer<TestState>())

            assertFailsWith<NodeExecutionException> { app.invoke(TestState(1000), config) }

            assertEquals(GraphResult.Interrupted(TestState(1001), listOf("b")), app.lastResult(config))
        }

    @Test
    fun `reading the last result does not change the thread`() =
        runTest {
            val checkpointer = MemoryCheckpointer<TestState>()
            val config = GraphConfig(checkpointer = checkpointer, interruptBefore = setOf("b"))
            app.invoke(TestState(0), config)
            val saved = checkpointer.load("default")

            app.lastResult(config)

            assertEquals(saved, checkpointer.load("default"))
            assertEquals(GraphResult.Completed(TestState(11)), app.resume(config))
        }

    @Test
    fun `threads are reported separately`() =
        runTest {
            val checkpointer = MemoryCheckpointer<TestState>()
            app.invoke(TestState(0), GraphConfig(threadId = "one", checkpointer = checkpointer))

            assertNull(app.lastResult(GraphConfig(threadId = "two", checkpointer = checkpointer)))
        }

    @Test
    fun `the next turn of a conversation starts from the last result`() =
        runTest {
            val config = GraphConfig(checkpointer = MemoryCheckpointer<TestState>())
            app.invoke(TestState(0), config)

            val previous = app.lastResult(config)?.state ?: TestState(0)
            val next = app.invoke(previous.copy(count = previous.count + 100), config)

            assertEquals(GraphResult.Completed(TestState(122)), next)
        }

    @Test
    fun `lastResult needs a checkpointer`() =
        runTest {
            assertFailsWith<GraphValidationException> { app.lastResult(GraphConfig()) }
        }
}
