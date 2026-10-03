package org.langgraphkt

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class NodeRefDslTest {
    @Test
    fun `then chains static edges between node references`() =
        runTest {
            val app =
                StateGraph<TestState> {
                    val a = node("a") { it.copy(count = it.count + 1) }
                    val b = node("b") { it.copy(count = it.count + 10) }

                    START then a then b then END
                }.compile()

            assertEquals(mapOf(START to listOf("a"), "a" to listOf("b"), "b" to listOf(END)), app.edges)
            assertEquals(11, app.invoke(TestState()).state.count)
        }

    @Test
    fun `node references work with conditional edges and interrupts`() =
        runTest {
            lateinit var review: NodeRef
            val app =
                StateGraph<TestState> {
                    val draft = node("draft") { it.copy(count = it.count + 1) }
                    review = node("review") { it.copy(count = it.count + 10) }

                    START then draft
                    conditionalEdge(draft, targets = setOf(review.name, END)) { if (it.count > 0) review.name else END }
                }.compile()

            val config = GraphConfig(checkpointer = MemoryCheckpointer<TestState>(), interruptBefore = setOf(review.name))

            assertEquals(GraphResult.Interrupted(TestState(1), listOf("review")), app.invoke(TestState(), config))
            assertEquals("review", review.toString())
        }

    @Test
    fun `fan-out and fan-in read naturally with then`() =
        runTest {
            val app =
                StateGraph<TestState> {
                    val left = node("left") { it.copy(count = it.count + 1) }
                    val right = node("right") { it.copy(count = it.count + 2) }
                    val join = node("join") { it.copy(count = it.count * 10) }

                    START then left then join
                    START then right then join
                }.compile { current, updates -> current.copy(count = updates.sumOf { it.count }) }

            assertEquals(30, app.invoke(TestState()).state.count)
        }
}
