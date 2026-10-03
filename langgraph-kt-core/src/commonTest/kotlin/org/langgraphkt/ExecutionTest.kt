package org.langgraphkt

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ExecutionTest {
    @Test
    fun `graph executes from START to END`() =
        runTest {
            val app =
                StateGraph<TestState> {
                    node("a") { it.copy(count = it.count + 1) }
                    node("b") { it.copy(count = it.count + 2) }

                    edge(START, "a")
                    edge("a", "b")
                    edge("b", END)
                }.compile()

            val result = app.invoke(TestState(0))

            assertIs<GraphResult.Completed<TestState>>(result)
            assertEquals(3, result.state.count)
        }

    @Test
    fun `node without outgoing edges ends the run`() =
        runTest {
            val app =
                StateGraph<TestState> {
                    node("a") { it.copy(count = it.count + 1) }
                    edge(START, "a")
                }.compile()

            assertEquals(GraphResult.Completed(TestState(1)), app.invoke(TestState(0)))
        }

    @Test
    fun `conditional edge routes based on state`() =
        runTest {
            val app =
                StateGraph<TestState> {
                    node("a") { it.copy(count = it.count + 1) }
                    node("b") { it.copy(count = it.count + 10) }
                    node("c") { it.copy(count = it.count + 100) }

                    edge(START, "a")
                    conditionalEdge("a", targets = setOf("b", "c")) { state ->
                        if (state.count == 1) "b" else "c"
                    }
                    edge("b", END)
                    edge("c", END)
                }.compile()

            assertEquals(11, app.invoke(TestState(0)).state.count)
            assertEquals(106, app.invoke(TestState(5)).state.count)
        }

    @Test
    fun `conditional edge from START can skip the whole graph`() =
        runTest {
            val app =
                StateGraph<TestState> {
                    node("a") { it.copy(count = it.count + 1) }
                    conditionalEdge(START, targets = setOf("a", END)) { if (it.count < 0) END else "a" }
                }.compile()

            assertEquals(GraphResult.Completed(TestState(-1)), app.invoke(TestState(-1)))
            assertEquals(GraphResult.Completed(TestState(1)), app.invoke(TestState(0)))

            // A run that never enters a node is still saved, as a completed one.
            val config = GraphConfig(checkpointer = MemoryCheckpointer<TestState>())
            app.invoke(TestState(-1), config)
            assertEquals(GraphResult.Completed(TestState(-1)), app.lastResult(config))
        }

    @Test
    fun `cycle runs until the condition routes to END`() =
        runTest {
            val app =
                StateGraph<TestState> {
                    node("increment") { it.copy(count = it.count + 1) }
                    edge(START, "increment")
                    conditionalEdge("increment", targets = setOf("increment", END)) { if (it.count < 5) "increment" else END }
                }.compile()

            assertEquals(5, app.invoke(TestState(0)).state.count)
        }

    @Test
    fun `infinite loop throws MaxIterationsExceededException`() =
        runTest {
            val app =
                StateGraph<TestState> {
                    node("a") { it.copy(count = it.count + 1) }
                    edge(START, "a")
                    edge("a", "a")
                }.compile()

            val exception =
                assertFailsWith<MaxIterationsExceededException> {
                    app.invoke(TestState(0), GraphConfig(maxIterations = 5))
                }
            assertEquals(5, exception.maxIterations)
        }

    @Test
    fun `maxIterations counts executed steps exactly`() =
        runTest {
            val app =
                StateGraph<TestState> {
                    node("a") { it.copy(count = it.count + 1) }
                    node("b") { it.copy(count = it.count + 1) }
                    node("c") { it.copy(count = it.count + 1) }
                    edge(START, "a")
                    edge("a", "b")
                    edge("b", "c")
                }.compile()

            assertEquals(3, app.invoke(TestState(0), GraphConfig(maxIterations = 3)).state.count)
            assertFailsWith<MaxIterationsExceededException> { app.invoke(TestState(0), GraphConfig(maxIterations = 2)) }
        }

    @Test
    fun `non-positive maxIterations is rejected`() {
        assertFailsWith<GraphValidationException> { GraphConfig<TestState>(maxIterations = 0) }
    }

    @Test
    fun `a compiled graph can be invoked many times`() =
        runTest {
            val app =
                StateGraph<TestState> {
                    node("a") { it.copy(count = it.count + 1) }
                    edge(START, "a")
                }.compile()

            assertEquals(1, app.invoke(TestState(0)).state.count)
            assertEquals(8, app.invoke(TestState(7)).state.count)
        }
}
