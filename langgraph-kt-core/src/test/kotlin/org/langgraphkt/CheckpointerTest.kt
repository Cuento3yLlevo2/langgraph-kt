package org.langgraphkt

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CheckpointerTest {

    @Test
    fun `graph interrupts before node and resumes correctly`() = runTest {
        val workflow = StateGraph<TestState> {
            node("a") { it.copy(count = it.count + 1) }
            node("b") { it.copy(count = it.count + 10) }
            node("c") { it.copy(count = it.count + 100) }
            
            edge(START, "a")
            edge("a", "b")
            edge("b", "c")
            edge("c", END)
        }

        val app = workflow.compile()
        val checkpointer = MemoryCheckpointer<TestState>()
        val config = GraphConfig(
            threadId = "test-1",
            checkpointer = checkpointer,
            interruptBefore = listOf("b")
        )

        // 1. Initial run: Should execute START -> a, then interrupt before b
        val state1 = app.invoke(TestState(0), config)
        assertEquals(1, state1.count) // Node 'a' executed

        // Checkpoint should be saved
        val checkpoint1 = checkpointer.load("test-1")
        assertEquals("b", checkpoint1?.nextNode)
        assertEquals(1, checkpoint1?.state?.count)

        // 2. We can simulate a human modifying the state
        val modifiedState = state1.copy(count = 5)

        // 3. Resume run: Should execute b -> c -> END
        val state2 = app.invoke(modifiedState, config, resume = true)
        
        // Node 'b' adds 10 (count becomes 15), Node 'c' adds 100 (count becomes 115)
        assertEquals(115, state2.count)
    }

    @Test
    fun `graph interrupts after node and resumes correctly`() = runTest {
        val workflow = StateGraph<TestState> {
            node("a") { it.copy(count = it.count + 1) }
            node("b") { it.copy(count = it.count + 10) }
            node("c") { it.copy(count = it.count + 100) }
            
            edge(START, "a")
            edge("a", "b")
            edge("b", "c")
            edge("c", END)
        }

        val app = workflow.compile()
        val checkpointer = MemoryCheckpointer<TestState>()
        val config = GraphConfig(
            threadId = "test-2",
            checkpointer = checkpointer,
            interruptAfter = listOf("a")
        )

        // 1. Initial run: Should execute START -> a, then interrupt after a
        val state1 = app.invoke(TestState(0), config)
        assertEquals(1, state1.count)

        // Checkpoint should point to next node 'b'
        val checkpoint1 = checkpointer.load("test-2")
        assertEquals("b", checkpoint1?.nextNode)

        // 2. Resume run: Should execute b -> c -> END
        val state2 = app.invoke(state1, config, resume = true)
        assertEquals(111, state2.count)
    }
}
