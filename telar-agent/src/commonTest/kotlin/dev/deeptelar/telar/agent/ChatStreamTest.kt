package dev.deeptelar.telar.agent

import dev.deeptelar.telar.GraphEvent
import dev.deeptelar.telar.NodeExecutionException
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/** A model that writes each of its [answers] in pieces, and records how it was asked. */
private class PiecewiseModel(
    vararg answers: Pair<List<String>, List<ToolCall>>,
    private val completes: Boolean = true,
) : ChatModel {
    private val answers = ArrayDeque(answers.toList())
    val asked = mutableListOf<String>()
    val requests = mutableListOf<ChatRequest>()

    override suspend fun chat(request: ChatRequest): ChatResponse {
        asked += "chat"
        requests += request
        val (pieces, calls) = answers.removeFirst()
        return ChatResponse(ChatMessage.Assistant(pieces.joinToString(""), calls))
    }

    override fun stream(request: ChatRequest): Flow<ChatEvent> =
        flow {
            asked += "stream"
            requests += request
            val (pieces, calls) = answers.removeFirst()
            pieces.forEach { emit(ChatEvent.TextDelta(it)) }
            if (completes) emit(ChatEvent.Completed(ChatResponse(ChatMessage.Assistant(pieces.joinToString(""), calls))))
        }
}

class ChatStreamTest {
    private val hello = ChatRequest(listOf(ChatMessage.User("Hello")))
    private val orderStatus = Tool<Unit>("order_status", "Returns where the order is.") { "The driver is 5 minutes away." }
    private val status = ToolCall("call-1", "order_status", noInput)

    private fun pieces(vararg pieces: String) = pieces.toList() to emptyList<ToolCall>()

    @Test
    fun `a model that cannot stream emits its text in one piece and then the answer`() =
        runTest {
            val events = ScriptedModel(says("Hello!")).stream(hello).toList()

            assertEquals(listOf(ChatEvent.TextDelta("Hello!"), ChatEvent.Completed(says("Hello!"))), events)
        }

    @Test
    fun `an answer without text has no text event`() =
        runTest {
            val events = ScriptedModel(calls(status)).stream(hello).toList()

            assertEquals(listOf<ChatEvent>(ChatEvent.Completed(calls(status))), events)
        }

    @Test
    fun `a streamed agent reports the text of the model node piece by piece`() =
        runTest {
            val model = PiecewiseModel(listOf("Let me ", "check.") to listOf(status), pieces("5 ", "minutes", "."))

            val events = toolAgent(model, listOf(orderStatus)).stream(AgentState("Where is my pizza?")).toList()

            val progress = events.filterIsInstance<GraphEvent.NodeProgress<AgentState>>()
            assertEquals(listOf("Let me ", "check.", "5 ", "minutes", "."), events.mapNotNull { it.textDelta })
            assertEquals(listOf(1, 1, 3, 3, 3), progress.map { it.step })
            assertEquals(setOf("model"), progress.map { it.node }.toSet())
            assertEquals("5 minutes.", assertIs<GraphEvent.Completed<AgentState>>(events.last()).state.answer)
            assertEquals(listOf("stream", "stream"), model.asked)
        }

    @Test
    fun `an agent that is not streamed asks for the whole answer`() =
        runTest {
            val model = PiecewiseModel(pieces("Hello", "!"))

            assertEquals("Hello!", toolAgent(model).invoke(AgentState("Hi")).state.answer)
            assertEquals(listOf("chat"), model.asked)
        }

    @Test
    fun `chatWithProgress outside a run asks for the whole answer`() =
        runTest {
            val model = PiecewiseModel(pieces("Hello", "!"))

            assertEquals("Hello!", model.chatWithProgress(hello).message.text)
            assertEquals(listOf("chat"), model.asked)
        }

    @Test
    fun `a node of your own reports the text with chatWithProgress`() =
        runTest {
            val model = PiecewiseModel(pieces("Dear ", "Ana"))
            val graph =
                StateGraph<AgentState> {
                    START then
                        node("write", work = { model.chatWithProgress(ChatRequest(it.messages)).message }) { state, reply ->
                            state.copy(messages = state.messages + reply)
                        }
                }.compile()

            val events = graph.stream(AgentState("Write to Ana")).toList()

            assertEquals(listOf("Dear ", "Ana"), events.mapNotNull { it.textDelta })
            assertEquals("write", events.filterIsInstance<GraphEvent.NodeProgress<AgentState>>().first().node)
        }

    @Test
    fun `chatWithProgress with a prompt sends one user message and reports the text`() =
        runTest {
            val model = PiecewiseModel(pieces("Dear ", "Ana"))
            val graph =
                StateGraph<AgentState> {
                    START then
                        node("write", work = { model.chatWithProgress("Write to Ana", system = "Be brief") }) { state, text ->
                            state.withUserMessage(text)
                        }
                }.compile()

            val events = graph.stream(AgentState()).toList()

            assertEquals(listOf("Dear ", "Ana"), events.mapNotNull { it.textDelta })
            assertEquals(
                "Dear Ana",
                events
                    .last()
                    .state.messages
                    .single()
                    .text,
            )
            assertEquals(ChatRequest(listOf(ChatMessage.User("Write to Ana")), "Be brief"), model.requests.single())
        }

    @Test
    fun `a stream that ends without an answer fails the model node`() =
        runTest {
            val model = PiecewiseModel(pieces("Hel"), completes = false)

            val failure = assertFailsWith<NodeExecutionException> { toolAgent(model).stream(AgentState("Hi")).toList() }

            assertEquals("model", failure.nodeName)
            assertIs<ChatModelException>(failure.cause)
        }

    @Test
    fun `only the report of a piece of text has a text delta`() {
        val state = AgentState("Hi")

        assertEquals("Hi", GraphEvent.NodeProgress(1, "model", ChatEvent.TextDelta("Hi"), state).textDelta)
        assertNull(GraphEvent.NodeProgress(1, "download", "2 of 5", state).textDelta)
        assertNull(GraphEvent.NodeStarted(1, "model", state).textDelta)
    }
}
