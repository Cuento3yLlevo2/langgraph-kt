package org.langgraphkt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `cancellation inside a node is not wrapped`() =
        runTest {
            assertFailsWith<CancellationException> {
                graphWith { throw CancellationException("cancelled") }.invoke(TestState())
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
