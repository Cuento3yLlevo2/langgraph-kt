package org.langgraphkt

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

data class TestState(val count: Int = 0)

class NodeTest {

    @Test
    fun `node execution should not mutate original state but return copy`() = runTest {
        val node = Node<TestState>("increment") { state ->
            state.copy(count = state.count + 1)
        }

        val initialState = TestState(0)
        val finalState = node.action(initialState)

        assertEquals(0, initialState.count)
        assertEquals(1, finalState.count)
        assertNotSame(initialState, finalState)
    }
}
