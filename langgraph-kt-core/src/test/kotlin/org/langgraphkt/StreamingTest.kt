package org.langgraphkt

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StreamingTest {

    @Test
    fun `stream emits state at each step`() = runTest {
        val workflow = StateGraph<TestState> {
            node("a") { it.copy(count = it.count + 1) }
            node("b") { it.copy(count = it.count + 2) }
            
            edge(START, "a")
            edge("a", "b")
            edge("b", END)
        }

        val app = workflow.compile()
        
        // Initial state + after 'a' + after 'b' = 3 emissions total
        val emittedStates = app.stream(TestState(0)).toList()
        
        assertEquals(3, emittedStates.size)
        assertEquals(0, emittedStates[0].count)
        assertEquals(1, emittedStates[1].count)
        assertEquals(3, emittedStates[2].count)
    }
}
