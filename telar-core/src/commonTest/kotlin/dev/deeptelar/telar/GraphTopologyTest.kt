package dev.deeptelar.telar

import kotlin.test.Test
import kotlin.test.assertEquals

class GraphTopologyTest {
    @Test
    fun `lists nodes in declaration order and static edges including START and END`() {
        val topology =
            StateGraph<TestState> {
                val b = node("b") { it }
                val a = node("a") { it }
                START then a then b then END
            }.compile().topology

        assertEquals(listOf("b", "a"), topology.nodes)
        assertEquals(listOf(GraphEdge(START, "a"), GraphEdge("b", END), GraphEdge("a", "b")), topology.edges)
        assertEquals(emptySet(), topology.dynamicRoutes)
    }

    @Test
    fun `declared targets of a conditional edge are conditional edges`() {
        val topology =
            StateGraph<TestState> {
                val agent = node("agent") { it }
                val tools = node("tools") { it }
                START then agent
                conditionalEdge(agent, targets = setOf(tools.name, END)) { END }
                tools then agent
            }.compile().topology

        assertEquals(
            listOf(
                GraphEdge(START, "agent"),
                GraphEdge("agent", "tools", isConditional = true),
                GraphEdge("agent", END, isConditional = true),
                GraphEdge("tools", "agent"),
            ),
            topology.edges,
        )
        assertEquals(listOf("tools", END), topology.successors("agent"))
    }

    @Test
    fun `a conditional edge without declared targets is a dynamic route`() {
        val topology =
            StateGraph<TestState> {
                val router = node("router") { it }
                node("other") { it }
                START then router
                conditionalEdge(router) { "other" }
            }.compile().topology

        assertEquals(setOf("router"), topology.dynamicRoutes)
        assertEquals(emptyList(), topology.successors("router"))
    }

    @Test
    fun `a node without an outgoing edge leads to END`() {
        val topology =
            StateGraph<TestState> {
                val only = node("only") { it }
                START then only
            }.compile().topology

        assertEquals(listOf(GraphEdge(START, "only"), GraphEdge("only", END)), topology.edges)
    }

    @Test
    fun `fan-out and fan-in appear as several edges`() {
        val topology =
            StateGraph<TestState> {
                val a = node("a") { it }
                val b = node("b") { it }
                val join = node("join") { it }
                START then a then join
                START then b then join
                join then END
            }.compile(reducer = { current, _ -> current }).topology

        assertEquals(listOf("a", "b"), topology.successors(START))
        assertEquals(listOf("join"), topology.successors("a"))
        assertEquals(listOf("join"), topology.successors("b"))
    }
}
