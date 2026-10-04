package org.langgraphkt.agent

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatMessageTest {
    private val status = call("call-1", "order_status", "customer" to "Ana")
    private val price = call("call-2", "menu_price", "item" to "cola")

    @Test
    fun `a conversation without an assistant message has no pending tool calls`() {
        assertEquals(emptyList(), emptyList<ChatMessage>().pendingToolCalls())
        assertEquals(emptyList(), listOf<ChatMessage>(ChatMessage.User("Hi")).pendingToolCalls())
    }

    @Test
    fun `the tool calls of the last assistant message are pending until they have a result`() {
        val asked = listOf(ChatMessage.User("Hi"), ChatMessage.Assistant(toolCalls = listOf(status, price)))

        assertEquals(listOf(status, price), asked.pendingToolCalls())
        assertEquals(listOf(price), (asked + ChatMessage.ToolResult("call-1", "order_status", "On its way")).pendingToolCalls())
    }

    @Test
    fun `tool calls of an earlier assistant message are not pending`() {
        val messages =
            listOf(
                ChatMessage.Assistant(toolCalls = listOf(status)),
                ChatMessage.ToolResult("call-1", "order_status", "On its way"),
                ChatMessage.Assistant("Your pizza is on its way."),
            )

        assertEquals(emptyList(), messages.pendingToolCalls())
    }

    @Test
    fun `a state with every kind of message survives serialization`() {
        val state =
            AgentState(
                listOf(
                    ChatMessage.User("Where is my pizza?"),
                    ChatMessage.Assistant("Let me check.", listOf(status), providerContent = JsonPrimitive("raw")),
                    ChatMessage.ToolResult("call-1", "order_status", "No such order", isError = true),
                    ChatMessage.Assistant("I cannot find your", truncated = true),
                ),
            )

        val json = Json.encodeToString(AgentState.serializer(), state)

        assertEquals(state, Json.decodeFromString(AgentState.serializer(), json))
    }

    @Test
    fun `the answer of a state is the text of a final assistant message`() {
        assertNull(AgentState().answer)
        assertNull(AgentState("Hi").answer)
        assertNull(AgentState(listOf(ChatMessage.Assistant("Let me check.", listOf(status)))).answer)
        assertEquals("Hello!", AgentState("Hi").copy(messages = listOf(ChatMessage.Assistant("Hello!"))).answer)
    }

    @Test
    fun `the answer of a state is truncated when its last assistant message is`() {
        assertFalse(AgentState("Hi").answerTruncated)
        assertFalse(AgentState(listOf(ChatMessage.Assistant("Hello!"))).answerTruncated)
        assertTrue(AgentState(listOf(ChatMessage.Assistant("Once upon a", truncated = true))).answerTruncated)
    }

    @Test
    fun `a message saved before it could be truncated is read as complete`() {
        val saved = """{"messages":[{"type":"assistant","text":"Hello!"}]}"""

        assertEquals(AgentState(listOf(ChatMessage.Assistant("Hello!"))), Json.decodeFromString(AgentState.serializer(), saved))
    }

    @Test
    fun `withUserMessage adds a user message at the end`() {
        val state = AgentState("Hi").withUserMessage("Anyone there?")

        assertEquals(listOf<ChatMessage>(ChatMessage.User("Hi"), ChatMessage.User("Anyone there?")), state.messages)
    }

    @Test
    fun `chat with a prompt sends one user message and returns the text of the answer`() =
        runTest {
            val model = ScriptedModel(says("Short."))

            assertEquals("Short.", model.chat("Summarize this", system = "Be brief"))
            assertEquals(ChatRequest(listOf(ChatMessage.User("Summarize this")), "Be brief"), model.requests.single())
        }
}
