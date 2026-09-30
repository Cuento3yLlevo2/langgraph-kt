package org.langgraphkt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StateGraphTest {
    @Test
    fun `build valid graph successfully`() {
        val workflow =
            StateGraph<TestState> {
                node("a") { it.copy(count = it.count + 1) }
                node("b") { it.copy(count = it.count + 1) }

                edge(START, "a")
                conditionalEdge("a") { state ->
                    if (state.count > 0) "b" else END
                }
                edge("b", END)
            }

        val compiled = workflow.compile()
        assertEquals(2, compiled.nodes.size)
        assertTrue(compiled.nodes.containsKey("a"))
        assertTrue(compiled.nodes.containsKey("b"))

        assertEquals(2, compiled.edges.size) // START->a and b->END
        assertEquals(1, compiled.conditionalEdges.size) // from 'a'
    }

    @Test
    fun `missing START edge throws compilation error`() {
        val workflow =
            StateGraph<TestState> {
                node("a") { it }
                edge("a", END)
            }

        val exception =
            assertFailsWith<IllegalStateException> {
                workflow.compile()
            }
        assertEquals("Graph must have at least one edge originating from START", exception.message)
    }

    @Test
    fun `dangling edge throws compilation error`() {
        val workflow =
            StateGraph<TestState> {
                node("a") { it }
                edge(START, "a")
                edge("a", "unknown")
            }

        val exception =
            assertFailsWith<IllegalArgumentException> {
                workflow.compile()
            }
        assertEquals("Edge references unknown to-node: unknown", exception.message)
    }
}
