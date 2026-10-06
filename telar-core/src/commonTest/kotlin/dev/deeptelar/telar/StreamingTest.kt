package dev.deeptelar.telar

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private fun <State> Flow<GraphEvent<State>>.withoutNodeEvents(): Flow<GraphEvent<State>> =
    filterNot { it is GraphEvent.NodeStarted || it is GraphEvent.NodeCompleted }

@OptIn(ExperimentalCoroutinesApi::class)
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
    fun `stream emits node and step events and ends with Completed`() =
        runTest {
            assertEquals(
                listOf(
                    GraphEvent.NodeStarted(1, "a", TestState(0)),
                    GraphEvent.NodeCompleted(1, "a", TestState(1)),
                    GraphEvent.StepCompleted(1, listOf("a"), TestState(1)),
                    GraphEvent.NodeStarted(2, "b", TestState(1)),
                    GraphEvent.NodeCompleted(2, "b", TestState(3)),
                    GraphEvent.StepCompleted(2, listOf("b"), TestState(3)),
                    GraphEvent.Completed(TestState(3)),
                ),
                app.stream(TestState(0)).toList(),
            )
        }

    @Test
    fun `parallel nodes all start first and complete in the order they finish`() =
        runTest {
            val parallel =
                StateGraph<TestState> {
                    val slow =
                        node("slow") {
                            delay(200)
                            it.copy(count = it.count + 1)
                        }
                    val fast =
                        node("fast") {
                            delay(100)
                            it.copy(count = it.count + 10)
                        }
                    START then slow then END
                    START then fast then END
                }.compile(reducer = { current, updates -> current.copy(count = updates.sumOf { it.count }) })

            assertEquals(
                listOf(
                    GraphEvent.NodeStarted(1, "slow", TestState(0)),
                    GraphEvent.NodeStarted(1, "fast", TestState(0)),
                    GraphEvent.NodeCompleted(1, "fast", TestState(10)),
                    GraphEvent.NodeCompleted(1, "slow", TestState(1)),
                    GraphEvent.StepCompleted(1, listOf("slow", "fast"), TestState(11)),
                    GraphEvent.Completed(TestState(11)),
                ),
                parallel.stream(TestState(0)).toList(),
            )
        }

    @Test
    fun `a node event is delivered while the other parallel nodes are still running`() =
        runTest {
            val parallel =
                StateGraph<TestState> {
                    val slow =
                        node("slow") {
                            delay(1000)
                            it
                        }
                    val fast =
                        node("fast") {
                            delay(100)
                            it
                        }
                    START then slow then END
                    START then fast then END
                }.compile(reducer = { current, _ -> current })

            val arrival = mutableMapOf<String, Long>()
            parallel.stream(TestState(0)).collect { if (it is GraphEvent.NodeCompleted) arrival[it.node] = currentTime }

            assertEquals(mapOf("fast" to 100L, "slow" to 1000L), arrival)
        }

    @Test
    fun `a failing parallel node ends the stream with its error after the events so far`() =
        runTest {
            val parallel =
                StateGraph<TestState> {
                    val ok = node("ok") { it }
                    val broken =
                        node("broken") {
                            delay(100)
                            error("boom")
                        }
                    val never =
                        node("never") {
                            delay(1000)
                            it
                        }
                    START then ok then END
                    START then broken then END
                    START then never then END
                }.compile(reducer = { current, _ -> current })

            val seen = mutableListOf<GraphEvent<TestState>>()
            val error = assertFailsWith<NodeExecutionException> { parallel.stream(TestState(0)).collect { seen += it } }

            assertEquals("broken", error.nodeName)
            assertEquals(GraphEvent.NodeCompleted(1, "ok", TestState(0)), seen.last())
            assertEquals(100, currentTime)
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
                app.stream(TestState(0), config).withoutNodeEvents().toList(),
            )
            assertEquals(
                listOf(
                    GraphEvent.StepCompleted(2, listOf("b"), TestState(3)),
                    GraphEvent.Completed(TestState(3)),
                ),
                app.streamResume(config).withoutNodeEvents().toList(),
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
