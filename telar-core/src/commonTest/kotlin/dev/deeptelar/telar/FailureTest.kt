package dev.deeptelar.telar

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * What the engine does with something that was thrown and is not an `Exception`. A browser throws
 * such things where another platform throws an exception.
 */
class FailureTest {
    /** What Ktor's engine for the browser throws when a request gets no response. */
    private val failedRequest = Error("Fail to fetch")

    private class Signal : Throwable("Neither an exception nor an error")

    private fun graphWith(action: suspend (TestState) -> TestState) =
        StateGraph<TestState> {
            node("fetch", action)
            edge(START, "fetch")
            edge("fetch", END)
        }.compile()

    @Test
    fun `a plain error fails the node like an exception`() =
        runTest {
            val exception = assertFailsWith<NodeExecutionException> { graphWith { throw failedRequest }.invoke(TestState()) }

            assertEquals("fetch", exception.nodeName)
            assertSame(failedRequest, exception.cause)
            assertEquals("Node 'fetch' failed: Fail to fetch", exception.message)
        }

    @Test
    fun `a node that failed with a plain error runs again on resume`() =
        runTest {
            var attempts = 0
            val flaky = graphWith { if (++attempts == 1) throw failedRequest else it.copy(count = it.count + 1) }
            val config = GraphConfig(checkpointer = MemoryCheckpointer<TestState>())

            assertFailsWith<NodeExecutionException> { flaky.invoke(TestState(5), config) }

            assertEquals(GraphResult.Interrupted(TestState(5), listOf("fetch")), flaky.lastResult(config))
            assertEquals(GraphResult.Completed(TestState(6)), flaky.resume(config))
        }

    @Test
    fun `something thrown that is neither an exception nor an error fails the node`() =
        runTest {
            val exception = assertFailsWith<NodeExecutionException> { graphWith { throw Signal() }.invoke(TestState()) }

            assertEquals("Node 'fetch' failed: Neither an exception nor an error", exception.message)
        }

    @Test
    fun `a plain error fails the work of a node`() =
        runTest {
            val app =
                StateGraph<TestState> {
                    node("fetch", work = { throw failedRequest }) { state, _: Int -> state }
                    edge(START, "fetch")
                    edge("fetch", END)
                }.compile()

            assertSame(failedRequest, assertFailsWith<NodeExecutionException> { app.invoke(TestState()) }.cause)
        }

    @Test
    fun `a plain error fails a condition`() =
        runTest {
            val app =
                StateGraph<TestState> {
                    node("a") { it }
                    edge(START, "a")
                    conditionalEdge("a", targets = setOf(END)) { throw failedRequest }
                }.compile()

            val exception = assertFailsWith<EdgeConditionException> { app.invoke(TestState()) }

            assertEquals("a", exception.from)
            assertSame(failedRequest, exception.cause)
        }

    @Test
    fun `a plain error fails a reducer`() =
        runTest {
            val app =
                StateGraph<TestState> {
                    node("a") { it }
                    node("b") { it }
                    edge(START, "a")
                    edge(START, "b")
                }.compile(reducer = { _, _ -> throw failedRequest })

            val exception = assertFailsWith<ReducerException> { app.invoke(TestState()) }

            assertEquals(listOf("a", "b"), exception.nodes)
            assertSame(failedRequest, exception.cause)
        }

    @Test
    fun `a failed assertion in a node is not a failure of the node`() =
        runTest {
            val failure = assertFailsWith<AssertionError> { graphWith { throw AssertionError("Expected 2, was 3") }.invoke(TestState()) }

            assertEquals("Expected 2, was 3", failure.message)
        }

    @Test
    fun `a node that is not written yet is not a failure of the node`() =
        runTest {
            assertFailsWith<NotImplementedError> { graphWith { TODO("Ask the kitchen") }.invoke(TestState()) }
        }

    @Test
    fun `an error of the program passes a condition and a reducer too`() =
        runTest {
            val routed =
                StateGraph<TestState> {
                    node("a") { it }
                    edge(START, "a")
                    conditionalEdge("a", targets = setOf(END)) { throw AssertionError("route") }
                }.compile()
            val merged =
                StateGraph<TestState> {
                    node("a") { it }
                    node("b") { it }
                    edge(START, "a")
                    edge(START, "b")
                }.compile(reducer = { _, _ -> throw AssertionError("merge") })

            assertEquals("route", assertFailsWith<AssertionError> { routed.invoke(TestState()) }.message)
            assertEquals("merge", assertFailsWith<AssertionError> { merged.invoke(TestState()) }.message)
        }
}
