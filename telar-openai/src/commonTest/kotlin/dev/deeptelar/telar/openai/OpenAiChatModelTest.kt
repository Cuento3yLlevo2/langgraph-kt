package dev.deeptelar.telar.openai

import dev.deeptelar.telar.agent.AgentState
import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.agent.ChatModelException
import dev.deeptelar.telar.agent.ChatRequest
import dev.deeptelar.telar.agent.TokenUsage
import dev.deeptelar.telar.agent.Tool
import dev.deeptelar.telar.agent.ToolCall
import dev.deeptelar.telar.agent.toolAgent
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Serializable
data class Lookup(
    val item: String,
)

class OpenAiChatModelTest {
    private val sent = mutableListOf<HttpRequestData>()
    private val menuPrice = Tool<Lookup>("menu_price", "Returns the price of an item.") { "One ${it.item} costs 2 euros." }
    private val hello = ChatRequest(listOf(ChatMessage.User("Hello")))

    /** A client that answers the requests it gets with [replies], in order. */
    private fun client(vararg replies: Pair<HttpStatusCode, String>): HttpClient {
        val queue = ArrayDeque(replies.toList())
        return HttpClient(
            MockEngine { request ->
                sent += request
                val (status, body) = queue.removeFirst()
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        )
    }

    private fun model(body: String, status: HttpStatusCode = HttpStatusCode.OK): OpenAiChatModel =
        OpenAiChatModel(client(status to body), apiKey = "test-key", model = "gpt-5")

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun sentBody(index: Int = 0): JsonObject = json((sent[index].body as TextContent).text) as JsonObject

    private fun answer(message: String, finishReason: String = "stop"): String =
        """{"choices": [{"index": 0, "finish_reason": "$finishReason", "message": $message}]}"""

    private fun text(text: String): String = answer("""{"role": "assistant", "content": "$text"}""")

    @Test
    fun `a request has the headers and the body the Chat Completions API expects`() =
        runTest {
            val model = model(text("Hi"))

            val response = model.chat(ChatRequest(listOf(ChatMessage.User("Hello")), "Be brief", listOf(menuPrice.spec)))

            assertEquals(ChatMessage.Assistant("Hi"), response.message)
            val request = sent.single()
            assertEquals("https://api.openai.com/v1/chat/completions", request.url.toString())
            assertEquals("Bearer test-key", request.headers["Authorization"])
            assertEquals("application/json", request.body.contentType.toString())
            assertEquals(
                json(
                    """
                    {
                      "model": "gpt-5",
                      "messages": [
                        {"role": "system", "content": "Be brief"},
                        {"role": "user", "content": "Hello"}
                      ],
                      "tools": [{
                        "type": "function",
                        "function": {
                          "name": "menu_price",
                          "description": "Returns the price of an item.",
                          "parameters": {
                            "type": "object",
                            "properties": {"item": {"type": "string"}},
                            "required": ["item"],
                            "additionalProperties": false
                          }
                        }
                      }]
                    }
                    """,
                ),
                sentBody(),
            )
        }

    @Test
    fun `a request without instructions and tools leaves those out`() =
        runTest {
            model(text("Hi")).chat(hello)

            assertEquals(setOf("model", "messages"), sentBody().keys)
            assertEquals(json("""[{"role": "user", "content": "Hello"}]"""), sentBody()["messages"])
        }

    @Test
    fun `parameters and headers of the model are added to every request`() =
        runTest {
            val model =
                OpenAiChatModel(
                    client(HttpStatusCode.OK to text("Hi")),
                    apiKey = "test-key",
                    model = "gpt-5-mini",
                    parameters =
                        buildJsonObject {
                            put("temperature", 0)
                            put("model", "gpt-5-nano")
                        },
                    headers = mapOf("OpenAI-Organization" to "org-1"),
                    baseUrl = "https://gateway.example.com/openai/v1/",
                )

            model.chat(hello)

            assertEquals("https://gateway.example.com/openai/v1/chat/completions", sent.single().url.toString())
            assertEquals("org-1", sent.single().headers["OpenAI-Organization"])
            assertEquals(json("0"), sentBody()["temperature"])
            assertEquals(json("\"gpt-5-nano\""), sentBody()["model"])
        }

    @Test
    fun `a model of Ollama is asked on this machine without a key`() =
        runTest {
            val model =
                OpenAiChatModel.ollama(
                    client(HttpStatusCode.OK to text("Hi")),
                    model = "llama3.2",
                    parameters =
                        buildJsonObject {
                            put("seed", 7)
                        },
                )

            assertEquals("Hi", model.chat(hello).message.text)

            assertEquals("http://localhost:11434/v1/chat/completions", sent.single().url.toString())
            assertNull(sent.single().headers["Authorization"])
            assertEquals(json("\"llama3.2\""), sentBody()["model"])
            assertEquals(json("7"), sentBody()["seed"])
        }

    @Test
    fun `tool calls go back with their input as text and every result is a message of its own`() =
        runTest {
            val model = model(text("Done"))
            val cola = ToolCall("call_1", "menu_price", buildJsonObject { put("item", "cola") })
            val fries = ToolCall("call_2", "menu_price", buildJsonObject { put("item", "fries") })

            model.chat(
                ChatRequest(
                    listOf(
                        ChatMessage.User("How much are a cola and fries?"),
                        ChatMessage.Assistant("Let me look.", listOf(cola)),
                        ChatMessage.ToolResult("call_1", "menu_price", "One cola costs 2 euros."),
                        ChatMessage.Assistant(toolCalls = listOf(fries)),
                        ChatMessage.ToolResult("call_2", "menu_price", "We have no fries.", isError = true),
                        ChatMessage.User("And water?"),
                    ),
                ),
            )

            assertEquals(
                json(
                    """
                    [
                      {"role": "user", "content": "How much are a cola and fries?"},
                      {"role": "assistant", "content": "Let me look.", "tool_calls": [
                        {"id": "call_1", "type": "function", "function": {"name": "menu_price", "arguments": "{\"item\":\"cola\"}"}}
                      ]},
                      {"role": "tool", "tool_call_id": "call_1", "content": "One cola costs 2 euros."},
                      {"role": "assistant", "content": null, "tool_calls": [
                        {"id": "call_2", "type": "function", "function": {"name": "menu_price", "arguments": "{\"item\":\"fries\"}"}}
                      ]},
                      {"role": "tool", "tool_call_id": "call_2", "content": "We have no fries."},
                      {"role": "user", "content": "And water?"}
                    ]
                    """,
                ),
                sentBody()["messages"],
            )
        }

    @Test
    fun `an assistant message without content is left out`() =
        runTest {
            val model = model(text("Hi"))

            model.chat(ChatRequest(listOf(ChatMessage.User("Hello"), ChatMessage.Assistant(), ChatMessage.User("Anyone?"))))

            assertEquals(
                json("""[{"role": "user", "content": "Hello"}, {"role": "user", "content": "Anyone?"}]"""),
                sentBody()["messages"],
            )
        }

    @Test
    fun `an answer with tool calls has the calls and the text and the usage`() =
        runTest {
            val model =
                model(
                    """
                    {
                      "choices": [{
                        "index": 0,
                        "finish_reason": "tool_calls",
                        "message": {"role": "assistant", "content": "Let me look.", "refusal": null, "tool_calls": [
                          {"id": "call_1", "type": "function", "function": {"name": "menu_price", "arguments": "{\"item\": \"cola\"}"}},
                          {"id": "call_2", "type": "function", "function": {"name": "menu_price", "arguments": "{\"item\": \"water\"}"}}
                        ]}
                      }],
                      "usage": {"prompt_tokens": 40, "completion_tokens": 9, "total_tokens": 49}
                    }
                    """,
                )

            val response = model.chat(hello)

            assertEquals(
                ChatMessage.Assistant(
                    "Let me look.",
                    listOf(
                        ToolCall("call_1", "menu_price", buildJsonObject { put("item", "cola") }),
                        ToolCall("call_2", "menu_price", buildJsonObject { put("item", "water") }),
                    ),
                ),
                response.message,
            )
            assertEquals(TokenUsage(inputTokens = 40, outputTokens = 9), response.usage)
            assertFalse(response.message.truncated)
        }

    @Test
    fun `an answer that only asks for tools has no text and no usage`() =
        runTest {
            val response =
                model(
                    answer(
                        """{"content": null, "tool_calls": [{"id": "call_1", "function": {"name": "menu_price", "arguments": "{\"item\": \"cola\"}"}}]}""",
                        finishReason = "tool_calls",
                    ),
                ).chat(hello)

            assertEquals("", response.message.text)
            assertEquals(
                "call_1",
                response.message.toolCalls
                    .single()
                    .id,
            )
            assertNull(response.usage)
        }

    @Test
    fun `a server may send a call with an object as input or without an id and the text in parts`() =
        runTest {
            val response =
                model(
                    answer(
                        """
                        {"content": [{"type": "text", "text": "Let me "}, {"type": "text", "text": "look."}], "tool_calls": [
                          {"function": {"name": "menu_price", "arguments": {"item": "cola"}}},
                          {"id": "", "function": {"name": "menu_price", "arguments": "{\"item\": \"tea\"}"}}
                        ]}
                        """,
                    ),
                ).chat(hello)

            assertEquals(
                ChatMessage.Assistant(
                    "Let me look.",
                    listOf(
                        ToolCall("call_0", "menu_price", buildJsonObject { put("item", "cola") }),
                        ToolCall("call_1", "menu_price", buildJsonObject { put("item", "tea") }),
                    ),
                ),
                response.message,
            )
        }

    @Test
    fun `a call whose input is not JSON gets an empty input`() =
        runTest {
            val response =
                model(
                    answer(
                        """{"content": null, "tool_calls": [{"id": "call_1", "function": {"name": "menu_price", "arguments": "{\"item\": \"co"}}]}""",
                        "length",
                    ),
                ).chat(hello)

            assertEquals(ToolCall("call_1", "menu_price", JsonObject(emptyMap())), response.message.toolCalls.single())
            assertTrue(response.message.truncated)
        }

    @Test
    fun `an answer that reached the output limit is truncated`() =
        runTest {
            val response = model(answer("""{"content": "Once upon a"}""", finishReason = "length")).chat(hello)

            assertEquals("Once upon a", response.message.text)
            assertTrue(response.message.truncated)
        }

    @Test
    fun `a refusal is an error`() =
        runTest {
            val failure =
                assertFailsWith<ChatModelException> {
                    model(answer("""{"content": null, "refusal": "I can't help with that."}""")).chat(hello)
                }

            assertEquals("The model declined this request: I can't help with that.", failure.message)
        }

    @Test
    fun `an answer that a content filter stopped is an error`() =
        runTest {
            val failure =
                assertFailsWith<ChatModelException> {
                    model(answer("""{"content": ""}""", finishReason = "content_filter")).chat(hello)
                }

            assertEquals("A content filter stopped the model's answer.", failure.message)
        }

    @Test
    fun `an error of the API is reported with its status and type and message`() =
        runTest {
            val failure =
                assertFailsWith<ChatModelException> {
                    model(
                        """{"error": {"message": "Incorrect API key provided.", "type": "invalid_request_error", "code": "invalid_api_key"}}""",
                        HttpStatusCode.Unauthorized,
                    ).chat(hello)
                }

            assertEquals("API error 401 (invalid_request_error): Incorrect API key provided.", failure.message)
        }

    @Test
    fun `an error with a code or with only a text is reported too`() =
        runTest {
            val coded =
                assertFailsWith<ChatModelException> {
                    model(
                        """{"error": {"message": "Slow down.", "code": "rate_limit_exceeded"}}""",
                        HttpStatusCode.TooManyRequests,
                    ).chat(hello)
                }
            val plain =
                assertFailsWith<ChatModelException> {
                    model("""{"error": "model 'llama9' not found"}""", HttpStatusCode.NotFound).chat(hello)
                }

            assertEquals("API error 429 (rate_limit_exceeded): Slow down.", coded.message)
            assertEquals("API error 404 (unknown): model 'llama9' not found", plain.message)
        }

    @Test
    fun `an error that is not JSON is reported with its text`() =
        runTest {
            val failure = assertFailsWith<ChatModelException> { model("Bad gateway", HttpStatusCode.BadGateway).chat(hello) }

            assertEquals("API error 502 (unknown): Bad gateway", failure.message)
        }

    @Test
    fun `a successful response that is not a JSON object or has no answer is an error`() =
        runTest {
            val noObject = assertFailsWith<ChatModelException> { model("[]").chat(hello) }
            val noAnswer = assertFailsWith<ChatModelException> { model("""{"choices": []}""").chat(hello) }

            assertEquals("The API at https://api.openai.com/v1 returned a response that is not a JSON object.", noObject.message)
            assertEquals("The API at https://api.openai.com/v1 returned a response without an answer.", noAnswer.message)
        }

    @Test
    fun `a failure to reach the API is an error with the address and the cause`() =
        runTest {
            val unreachable = HttpClient(MockEngine { throw IllegalStateException("Connection refused") })

            val failure = assertFailsWith<ChatModelException> { OpenAiChatModel.ollama(unreachable, "llama3.2").chat(hello) }

            assertEquals("Could not reach the API at http://localhost:11434/v1: Connection refused", failure.message)
            assertIs<IllegalStateException>(failure.cause)
        }

    @Test
    fun `a cancellation is not turned into an error of the model`() =
        runTest {
            val cancelling = HttpClient(MockEngine { throw CancellationException("Stopped") })

            val failure = runCatching { OpenAiChatModel(cancelling, "test-key", "gpt-5").chat(hello) }.exceptionOrNull()

            assertIs<CancellationException>(failure)
        }

    @Test
    fun `an agent runs a tool for the model and sends the result back`() =
        runTest {
            val client =
                client(
                    HttpStatusCode.OK to
                        answer(
                            """{"content": null, "tool_calls": [{"id": "call_1", "type": "function", "function": {"name": "menu_price", "arguments": "{\"item\": \"cola\"}"}}]}""",
                            finishReason = "tool_calls",
                        ),
                    HttpStatusCode.OK to text("A cola costs 2 euros."),
                )
            val agent = toolAgent(OpenAiChatModel(client, "test-key", "gpt-5"), listOf(menuPrice))

            val state = agent.invoke(AgentState("How much is a cola?")).state

            assertEquals("A cola costs 2 euros.", state.answer)
            assertEquals(
                json(
                    """
                    [
                      {"role": "user", "content": "How much is a cola?"},
                      {"role": "assistant", "content": null, "tool_calls": [
                        {"id": "call_1", "type": "function", "function": {"name": "menu_price", "arguments": "{\"item\":\"cola\"}"}}
                      ]},
                      {"role": "tool", "tool_call_id": "call_1", "content": "One cola costs 2 euros."}
                    ]
                    """,
                ),
                sentBody(1)["messages"],
            )
        }
}
