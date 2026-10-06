package dev.deeptelar.telar.langchain4j

import dev.deeptelar.telar.agent.AgentState
import dev.deeptelar.telar.agent.ChatEvent
import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.agent.ChatModelException
import dev.deeptelar.telar.agent.ChatRequest
import dev.deeptelar.telar.agent.Tool
import dev.deeptelar.telar.agent.textDelta
import dev.deeptelar.telar.agent.toolAgent
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.chat.response.PartialResponse
import dev.langchain4j.model.chat.response.PartialResponseContext
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler
import dev.langchain4j.model.chat.response.StreamingHandle
import dev.langchain4j.model.output.FinishReason
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import dev.langchain4j.model.chat.request.ChatRequest as LangChain4jRequest
import dev.langchain4j.model.chat.response.ChatResponse as LangChain4jResponse

/** Plays one of [answers] to the handler for each request, and records what it was asked. */
private class PiecewiseModel(
    vararg answers: (StreamingChatResponseHandler) -> Unit,
) : StreamingChatModel {
    private val answers = ArrayDeque(answers.toList())
    val requests = mutableListOf<LangChain4jRequest>()

    override fun doChat(chatRequest: LangChain4jRequest, handler: StreamingChatResponseHandler) {
        requests += chatRequest
        answers.removeFirst()(handler)
    }
}

private class Handle : StreamingHandle {
    @Volatile
    var cancelled = false

    override fun cancel() {
        cancelled = true
    }

    override fun isCancelled(): Boolean = cancelled
}

class LangChain4jStreamTest {
    private val hello = ChatRequest(listOf(ChatMessage.User("Hello")), system = "Be brief")
    private val unused = RecordingModel()

    private fun reply(message: AiMessage, finishReason: FinishReason = FinishReason.STOP): LangChain4jResponse =
        LangChain4jResponse
            .builder()
            .aiMessage(message)
            .finishReason(finishReason)
            .build()

    /** An answer of plain text that arrives in [pieces]. */
    private fun text(vararg pieces: String): (StreamingChatResponseHandler) -> Unit =
        { handler ->
            pieces.forEach { handler.onPartialResponse(it) }
            handler.onCompleteResponse(reply(AiMessage.from(pieces.joinToString(""))))
        }

    @Test
    fun `a streaming model delivers the text in pieces and then the whole answer`() =
        runTest {
            val streaming = PiecewiseModel(text("Hello", " there", "!"))

            val events = LangChain4jChatModel(unused, streaming).stream(hello).toList()

            assertEquals(listOf("Hello", " there", "!"), events.filterIsInstance<ChatEvent.TextDelta>().map { it.text })
            assertEquals(ChatMessage.Assistant("Hello there!"), assertIs<ChatEvent.Completed>(events.last()).response.message)
            assertEquals(4, events.size)
            assertIs<SystemMessage>(
                streaming.requests
                    .single()
                    .messages()
                    .first(),
            )
            assertEquals(emptyList(), unused.requests)
        }

    @Test
    fun `without a streaming model the text arrives in one piece`() =
        runTest {
            val model = RecordingModel(reply(AiMessage.from("Hello there!")))

            val events = LangChain4jChatModel(model).stream(hello).toList()

            assertEquals(ChatEvent.TextDelta("Hello there!"), events.first())
            assertEquals(2, events.size)
        }

    @Test
    fun `an error of the streaming model fails the flow after the pieces so far`() =
        runTest {
            val cause = IllegalStateException("No network")
            val streaming =
                PiecewiseModel({ handler ->
                    handler.onPartialResponse("Hel")
                    handler.onError(cause)
                })

            val seen = mutableListOf<ChatEvent>()
            val failure =
                assertFailsWith<ChatModelException> { LangChain4jChatModel(unused, streaming).stream(hello).collect { seen += it } }

            assertEquals("The LangChain4j model failed: No network", failure.message)
            assertSame(cause, failure.cause)
            assertEquals(listOf<ChatEvent>(ChatEvent.TextDelta("Hel")), seen)
        }

    @Test
    fun `a streaming model that throws fails the flow`() =
        runTest {
            val streaming = PiecewiseModel({ throw IllegalArgumentException("Bad request") })

            val failure = assertFailsWith<ChatModelException> { LangChain4jChatModel(unused, streaming).stream(hello).toList() }

            assertEquals("The LangChain4j model failed: Bad request", failure.message)
        }

    @Test
    fun `a filtered streamed answer is an error`() =
        runTest {
            val streaming = PiecewiseModel({ it.onCompleteResponse(reply(AiMessage.from(""), FinishReason.CONTENT_FILTER)) })

            assertFailsWith<ChatModelException> { LangChain4jChatModel(unused, streaming).stream(hello).toList() }
        }

    @Test
    fun `a collector that stops stops the model`() =
        runTest {
            val handle = Handle()
            // The model has written one piece and is still writing.
            val streaming = PiecewiseModel({ it.onPartialResponse(PartialResponse("Hello"), PartialResponseContext(handle)) })

            val first = LangChain4jChatModel(unused, streaming).stream(hello).first()

            assertEquals(ChatEvent.TextDelta("Hello"), first)
            assertTrue(handle.cancelled)
        }

    @Test
    fun `a model that finished is not stopped`() =
        runTest {
            val handle = Handle()
            val streaming =
                PiecewiseModel({ handler ->
                    handler.onPartialResponse(PartialResponse("Hello"), PartialResponseContext(handle))
                    handler.onCompleteResponse(reply(AiMessage.from("Hello")))
                })

            LangChain4jChatModel(unused, streaming).stream(hello).toList()

            assertFalse(handle.cancelled)
        }

    @Test
    fun `a streamed agent shows the model's text and runs its tool`() =
        runTest {
            val orderStatus = Tool<Unit>("order_status", "Returns where the order is.") { "The driver is 5 minutes away." }
            val call =
                ToolExecutionRequest
                    .builder()
                    .id("call-1")
                    .name("order_status")
                    .arguments("{}")
                    .build()
            val streaming =
                PiecewiseModel(
                    { handler ->
                        handler.onPartialResponse("Let me check.")
                        handler.onCompleteResponse(reply(AiMessage.from("Let me check.", listOf(call)), FinishReason.TOOL_EXECUTION))
                    },
                    text("5 ", "minutes."),
                )

            val events =
                toolAgent(
                    LangChain4jChatModel(unused, streaming),
                    listOf(orderStatus),
                ).stream(AgentState("Where is my pizza?")).toList()

            assertEquals(listOf("Let me check.", "5 ", "minutes."), events.mapNotNull { it.textDelta })
            assertEquals("5 minutes.", events.last().state.answer)
            assertEquals(
                1,
                streaming.requests
                    .first()
                    .toolSpecifications()
                    .size,
            )
        }
}
