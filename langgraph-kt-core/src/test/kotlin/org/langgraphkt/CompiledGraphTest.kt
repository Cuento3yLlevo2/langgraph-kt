package org.langgraphkt

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CompiledGraphTest {

    @Test
    fun `graph executes from START to END properly`() = runTest {
        val workflow = StateGraph<TestState> {
            node("a") { it.copy(count = it.count + 1) }
            node("b") { it.copy(count = it.count + 2) }
            
            edge(START, "a")
            edge("a", "b")
            edge("b", END)
        }

        val app = workflow.compile()
        val finalState = app.invoke(TestState(0))
        
        assertEquals(3, finalState.count)
    }

    @Test
    fun `conditional edge routes correctly based on state`() = runTest {
        val workflow = StateGraph<TestState> {
            node("a") { it.copy(count = it.count + 1) }
            node("b") { it.copy(count = it.count + 10) }
            node("c") { it.copy(count = it.count + 100) }
            
            edge(START, "a")
            conditionalEdge("a") { state ->
                if (state.count == 1) "b" else "c"
            }
            edge("b", END)
            edge("c", END)
        }

        val app = workflow.compile()
        val finalState = app.invoke(TestState(0))
        
        // Node 'a' sets count to 1, condition routes to 'b', 'b' adds 10
        assertEquals(11, finalState.count)
    }
}
