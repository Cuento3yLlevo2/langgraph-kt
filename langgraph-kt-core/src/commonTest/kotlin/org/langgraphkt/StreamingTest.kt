package org.langgraphkt

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StreamingTest {
    private var runs = 0
    private val app =
        StateGraph<TestState> {
            node("a") {
                runs++
                it.copy(count = it.count + 1)
            }
            node("b") { it.copy(count = it.count + 2) }

            edge(START, "a")
            edge("a", "b")
            edge("b", END)
        }.compile()

    @Test
    fun `stream emits an event per step and ends with Completed`() =
        runTest {
            assertEquals(
                listOf(
                    GraphEvent.StepCompleted(1, listOf("a"), TestState(1)),
                    GraphEvent.StepCompleted(2, listOf("b"), TestState(3)),
                    GraphEvent.Completed(TestState(3)),
                ),
                app.stream(TestState(0)).toList(),
            )
        }

    @Test
    fun `states maps the stream to the state after each step`() =
        runTest {
            assertEquals(listOf(TestState(1), TestState(3)), app.stream(TestState(0)).states().toList())
        }

    @Test
    fun `stream ends with Interrupted and streamResume continues the step numbering`() =
        runTest {
            val config = GraphConfig(checkpointer = MemoryCheckpointer<TestState>(), interruptBefore = setOf("b"))

            assertEquals(
                listOf(
                    GraphEvent.StepCompleted(1, listOf("a"), TestState(1)),
                    GraphEvent.Interrupted(TestState(1), listOf("b")),
                ),
                app.stream(TestState(0), config).toList(),
            )
            assertEquals(
                listOf(
                    GraphEvent.StepCompleted(2, listOf("b"), TestState(3)),
                    GraphEvent.Completed(TestState(3)),
                ),
                app.streamResume(config).toList(),
            )
        }

    @Test
    fun `stream is cold and each collection is a new run`() =
        runTest {
            val flow = app.stream(TestState(0))
            assertEquals(0, runs)

            flow.toList()
            flow.toList()

            assertEquals(2, runs)
        }
}
