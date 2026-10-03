package org.langgraphkt

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
    fun `a conditional edge can route between node references`() =
        runTest {
            val app =
                StateGraph<TestState> {
                    val small = node("small") { it.copy(count = it.count + 1) }
                    val large = node("large") { it.copy(count = it.count + 100) }

                    conditionalEdge(START, targets = setOf(small, large)) { if (it.count < 10) small else large }
                    conditionalEdge(small, targets = setOf(small, NodeRef.END)) { if (it.count < 3) small else NodeRef.END }
                    large then NodeRef.END
                }.compile()

            assertEquals(GraphResult.Completed(TestState(3)), app.invoke(TestState(0)))
            assertEquals(GraphResult.Completed(TestState(150)), app.invoke(TestState(50)))
            assertEquals(
                listOf(
                    GraphEdge(START, "small", isConditional = true),
                    GraphEdge(START, "large", isConditional = true),
                    GraphEdge("small", "small", isConditional = true),
                    GraphEdge("small", END, isConditional = true),
                    GraphEdge("large", END),
                ),
                app.topology.edges,
            )
        }

    @Test
    fun `a reference that is not a declared target is an invalid route`() =
        runTest {
            val app =
                StateGraph<TestState> {
                    val a = node("a") { it }
                    val b = node("b") { it }

                    START then a
                    conditionalEdge(a, targets = setOf(b)) { NodeRef.END }
                }.compile()

            val exception = assertFailsWith<InvalidRouteException> { app.invoke(TestState()) }

            assertEquals("a", exception.from)
            assertEquals(END, exception.target)
        }

    @Test
    fun `targets given as references are checked by compile`() {
        val exception =
            assertFailsWith<GraphValidationException> {
                StateGraph<TestState> {
                    val a = node("a") { it }
                    node("unused") { it }

                    START then a
                    conditionalEdge(a, targets = setOf(a, NodeRef.END)) { NodeRef.END }
                }.compile()
            }

        assertEquals("Node 'unused' is not reachable from START", exception.message)
    }

    @Test
    fun `the END reference has the name END`() {
        assertEquals(END, NodeRef.END.name)
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
