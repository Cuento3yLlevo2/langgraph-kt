package org.langgraphkt

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

data class ParallelState(
    val messages: List<String> = emptyList()
)

class ReducerTest {

    @Test
    fun `fan-out parallel execution runs and reduces state`() = runTest {
        val workflow = StateGraph<ParallelState> {
            node("branchA") { state ->
                delay(10) // simulate work
                state.copy(messages = listOf("A"))
            }
            node("branchB") { state ->
                delay(20) // simulate work
                state.copy(messages = listOf("B"))
            }
            node("aggregator") { state ->
                state.copy(messages = state.messages + "Aggregated")
            }

            // Fan out from START to both branches
            edge(START, "branchA")
            edge(START, "branchB")

            // Fan in from both branches to aggregator
            edge("branchA", "aggregator")
            edge("branchB", "aggregator")
            
            edge("aggregator", END)
        }

        val listReducer = Reducer<ParallelState> { original, updates ->
            // Our custom logic to merge messages from parallel branches
            val allNewMessages = updates.flatMap { it.messages }
            original.copy(messages = original.messages + allNewMessages)
        }

        val app = workflow.compile(reducer = listReducer)
        
        val result = app.invoke(ParallelState())
        
        assertEquals(3, result.messages.size)
        // Since branchA and branchB run in parallel, their updates are merged.
        // The reducer adds both "A" and "B" (order might depend on iteration, but both are present).
        assert(result.messages.contains("A"))
        assert(result.messages.contains("B"))
        assertEquals("Aggregated", result.messages.last())
    }
}
