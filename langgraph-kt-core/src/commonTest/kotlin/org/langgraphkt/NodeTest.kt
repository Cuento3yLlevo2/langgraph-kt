package org.langgraphkt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame

data class TestState(
    val count: Int = 0,
)

class NodeTest {
    @Test
    fun `node execution should not mutate original state but return copy`() =
        runTest {
            val node =
                Node<TestState>("increment") { state ->
                    state.copy(count = state.count + 1)
                }

            val initialState = TestState(0)
            val finalState = node.action(initialState)

            assertEquals(0, initialState.count)
            assertEquals(1, finalState.count)
            assertNotSame(initialState, finalState)
        }
}

class NodeFailureTest {
    private fun graphWith(action: NodeAction<TestState>) =
        StateGraph<TestState> {
            node("boom", action)
            edge(START, "boom")
            edge("boom", END)
        }.compile()

    @Test
    fun `node failure is wrapped with the node name and original cause`() =
        runTest {
            val failure = IllegalArgumentException("bad input")

            val exception = assertFailsWith<NodeExecutionException> { graphWith { throw failure }.invoke(TestState()) }

            assertEquals("boom", exception.nodeName)
            assertSame(failure, exception.cause)
        }

    @Test
    fun `cancelling the run is not wrapped`() =
        runTest {
            val nodeStarted = CompletableDeferred<Unit>()
            val run =
                async {
                    graphWith {
                        nodeStarted.complete(Unit)
                        awaitCancellation()
                    }.invoke(TestState())
                }

            nodeStarted.await()
            run.cancel()

            assertFailsWith<CancellationException> { run.await() }
        }

    @Test
    fun `a timeout inside a node is a node failure`() =
        runTest {
            val exception =
                assertFailsWith<NodeExecutionException> {
                    graphWith { withTimeout(10) { awaitCancellation() } }.invoke(TestState())
                }

            assertEquals("boom", exception.nodeName)
            assertIs<TimeoutCancellationException>(exception.cause)
        }

    @Test
    fun `a timeout around the run is not wrapped`() =
        runTest {
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(10) { graphWith { awaitCancellation() }.invoke(TestState()) }
            }
        }

    @Test
    fun `duplicate node name is a validation error`() {
        assertFailsWith<GraphValidationException> {
            StateGraph<TestState> {
                node("a") { it }
                node("a") { it }
            }
        }
    }
}
