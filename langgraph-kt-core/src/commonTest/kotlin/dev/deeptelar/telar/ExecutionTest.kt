package dev.deeptelar.telar

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
    fun `a failing condition is reported with the node its edge starts from`() =
        runTest {
            val app =
                StateGraph<TestState> {
                    node("a") { it.copy(count = it.count + 1) }
                    edge(START, "a")
                    conditionalEdge("a", targets = setOf(END)) { error("no route") }
                }.compile()

            val exception = assertFailsWith<EdgeConditionException> { app.invoke(TestState(0)) }

            assertEquals("a", exception.from)
            assertEquals("no route", exception.cause?.message)
            assertEquals("Conditional edge from 'a' failed: no route", exception.message)
        }

    @Test
    fun `a condition whose own timeout expires fails instead of cancelling the caller`() =
        runTest {
            val app =
                StateGraph<TestState> {
                    node("a") { it }
                    conditionalEdge(START, targets = setOf("a")) { withTimeout(10) { awaitCancellation() } }
                }.compile()

            assertEquals(START, assertFailsWith<EdgeConditionException> { app.invoke(TestState(0)) }.from)
        }

    @Test
    fun `a step whose condition failed runs again on resume`() =
        runTest {
            var runsOfA = 0
            var conditionFails = true
            val app =
                StateGraph<TestState> {
                    node("a") {
                        runsOfA++
                        it.copy(count = it.count + 1)
                    }
                    edge(START, "a")
                    conditionalEdge("a", targets = setOf(END)) { if (conditionFails) error("no route") else END }
                }.compile()
            val config = GraphConfig(checkpointer = MemoryCheckpointer<TestState>())

            assertFailsWith<EdgeConditionException> { app.invoke(TestState(0), config) }
            // The failed step was not saved, so the thread still stands before "a".
            assertEquals(GraphResult.Interrupted(TestState(0), listOf("a")), app.lastResult(config))

            conditionFails = false
            assertEquals(GraphResult.Completed(TestState(1)), app.resume(config))
            assertEquals(2, runsOfA)
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
