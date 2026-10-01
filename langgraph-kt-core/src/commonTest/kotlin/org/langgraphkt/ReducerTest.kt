package org.langgraphkt

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

data class ParallelState(
    val messages: List<String> = emptyList(),
)

class ReducerTest {
    private val appendMessages =
        Reducer<ParallelState> { current, updates ->
            current.copy(messages = current.messages + updates.flatMap { it.messages - current.messages.toSet() })
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `fan-out runs branches in parallel and fan-in runs the join once`() =
        runTest {
            val app =
                StateGraph<ParallelState> {
                    node("branchA") {
                        delay(100)
                        it.copy(messages = it.messages + "A")
                    }
                    node("branchB") {
                        delay(100)
                        it.copy(messages = it.messages + "B")
                    }
                    node("aggregator") { it.copy(messages = it.messages + "Aggregated") }

                    edge(START, "branchA")
                    edge(START, "branchB")
                    edge("branchA", "aggregator")
                    edge("branchB", "aggregator")
                    edge("aggregator", END)
                }.compile(reducer = appendMessages)

            val events = app.stream(ParallelState()).toList()

            assertEquals(listOf("A", "B", "Aggregated"), events.last().state.messages)
            assertEquals(
                listOf(listOf("branchA", "branchB"), listOf("aggregator")),
                events.filterIsInstance<GraphEvent.StepCompleted<ParallelState>>().map { it.nodes },
            )
            // Both 100 ms branches overlap, so virtual time advances by 100 ms, not 200 ms.
            assertEquals(100, currentTime)
        }

    @Test
    fun `reducer can suspend`() =
        runTest {
            val app =
                StateGraph<ParallelState> {
                    node("a") { it.copy(messages = listOf("A")) }
                    node("b") { it.copy(messages = listOf("B")) }
                    edge(START, "a")
                    edge(START, "b")
                }.compile { current, updates ->
                    delay(10)
                    current.copy(messages = updates.flatMap { it.messages })
                }

            assertEquals(listOf("A", "B"), app.invoke(ParallelState()).state.messages)
        }

    @Test
    fun `a failing branch cancels its siblings`() =
        runTest {
            var siblingCancelled = false
            val app =
                StateGraph<ParallelState> {
                    node("fails") {
                        delay(10)
                        error("boom")
                    }
                    node("waits") {
                        try {
                            awaitCancellation()
                        } finally {
                            siblingCancelled = true
                        }
                    }
                    edge(START, "fails")
                    edge(START, "waits")
                }.compile(reducer = appendMessages)

            val exception = assertFailsWith<NodeExecutionException> { app.invoke(ParallelState()) }

            assertEquals("fails", exception.nodeName)
            assertTrue(siblingCancelled)
        }

    @Test
    fun `parallel interrupt lists every pending node`() =
        runTest {
            val app =
                StateGraph<ParallelState> {
                    node("a") { it.copy(messages = it.messages + "A") }
                    node("b") { it.copy(messages = it.messages + "B") }
                    edge(START, "a")
                    edge(START, "b")
                }.compile(reducer = appendMessages)
            val config = GraphConfig(checkpointer = MemoryCheckpointer<ParallelState>(), interruptBefore = setOf("b"))

            assertEquals(GraphResult.Interrupted(ParallelState(), listOf("a", "b")), app.invoke(ParallelState(), config))
            assertEquals(listOf("A", "B"), app.resume(config).state.messages)
        }
}
