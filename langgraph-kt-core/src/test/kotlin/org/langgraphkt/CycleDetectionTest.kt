package org.langgraphkt

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CycleDetectionTest {

    @Test
    fun `infinite loop throws MaxIterationsExceededException`() = runTest {
        val workflow = StateGraph<TestState> {
            node("a") { it.copy(count = it.count + 1) }
            
            edge(START, "a")
            // Cycle back to itself unconditionally
            edge("a", "a")
        }

        val app = workflow.compile()
        
        assertFailsWith<MaxIterationsExceededException> {
            app.invoke(TestState(0), config = GraphConfig(maxIterations = 5))
        }
    }
}
