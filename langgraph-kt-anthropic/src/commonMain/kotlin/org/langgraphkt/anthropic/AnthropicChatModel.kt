package org.langgraphkt.anthropic

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.langgraphkt.agent.ChatMessage
import org.langgraphkt.agent.ChatModel
import org.langgraphkt.agent.ChatModelException
import org.langgraphkt.agent.ChatRequest
import org.langgraphkt.agent.ChatResponse
import org.langgraphkt.agent.TokenUsage
import org.langgraphkt.agent.ToolCall

/**
 * A [ChatModel] that calls Claude through the Messages API of Anthropic. It is built on Ktor, so it
 * runs on every Kotlin target.
 *
 * ```kotlin
 * val client = HttpClient()
 * val model = AnthropicChatModel(client, apiKey = System.getenv("ANTHROPIC_API_KEY"), model = "claude-opus-5-5")
 * val agent = toolAgent(model, tools = listOf(forecast))
 * ```
 *
 * `HttpClient()` uses the Ktor engine among your dependencies, for example `ktor-client-okhttp` on
 * Android, `ktor-client-darwin` on iOS or `ktor-client-js` in a browser. The client is yours: share
 * one between models, and close it when your app is done with it.
 *
 * Answers are not streamed: [chat] returns when the model has finished. The model's thinking and
 * other content that the API wants back unchanged travels in [ChatMessage.Assistant.providerContent].
 *
 * In a browser the API only accepts a request with the header
 * `anthropic-dangerous-direct-browser-access: true`; pass it in [headers]. That is acceptable when
 * each user enters a key of their own. Never ship an app that contains a key of yours.
 *
 * @param client the Ktor client that sends the requests.
 * @param apiKey the Anthropic API key.
 * @param model the id of the model, for example `claude-opus-5-5`.
 * @param maxTokens the most tokens the model may write in one answer. An answer that reaches the
 * limit comes back with [ChatMessage.Assistant.truncated] set.
 * @param parameters more fields for the body of every request, such as `temperature`, `thinking`
 * or `tool_choice`. A field given here replaces the one this class would send.
 * @param headers more headers for every request, such as `anthropic-beta`.
 * @param baseUrl where the API lives. Change it to go through a proxy or a gateway.
 */
public class AnthropicChatModel(
    private val client: HttpClient,
    private val apiKey: String,
    private val model: String,
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
    private val parameters: JsonObject = JsonObject(emptyMap()),
    private val headers: Map<String, String> = emptyMap(),
    private val baseUrl: String = "https://api.anthropic.com",
) : ChatModel {
    /**
     * Sends [request] to the Messages API and returns Claude's answer.
     *
     * @throws ChatModelException when the API cannot be reached, when it returns an error, and when
     * Claude declines the request.
     */
    override suspend fun chat(request: ChatRequest): ChatResponse {
        val status: Int
        val text: String
        try {
            val response =
                client.post("${baseUrl.trimEnd('/')}/v1/messages") {
                    header("x-api-key", apiKey)
                    header("anthropic-version", API_VERSION)
                    this@AnthropicChatModel.headers.forEach { (name, value) -> header(name, value) }
                    contentType(ContentType.Application.Json)
                    setBody(body(request).toString())
                }
            status = response.status.value
            text = response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ChatModelException("Could not reach the Claude API: ${e.message ?: e::class.simpleName}", e)
        }

        val body =
            try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (_: SerializationException) {
                null
            }
        if (status !in 200..299) {
            val error = body?.get("error") as? JsonObject
            throw ChatModelException("Claude API error $status (${error.string("type") ?: "unknown"}): ${error.string("message") ?: text}")
        }
        if (body == null) throw ChatModelException("The Claude API returned a response that is not a JSON object.")
        return response(body)
    }

    private fun body(request: ChatRequest): JsonObject {
        val body =
            buildJsonObject {
                put("model", model)
                put("max_tokens", maxTokens)
                request.system?.let { put("system", it) }
                if (request.tools.isNotEmpty()) {
                    putJsonArray("tools") {
                        request.tools.forEach { tool ->
                            addJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("input_schema", tool.inputSchema)
                            }
                        }
                    }
                }
                put("messages", turns(request.messages))
            }
        return JsonObject(body + parameters)
    }

    private fun response(body: JsonObject): ChatResponse {
        val stopReason = body.string("stop_reason")
        // A refusal is a successful response, so check the stop reason before reading the content.
        if (stopReason == "refusal") throw ChatModelException("Claude declined this request.")

        val content = (body["content"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val message =
            ChatMessage.Assistant(
                text = content.filter { it.string("type") == "text" }.joinToString("") { it.string("text").orEmpty() },
                toolCalls =
                    content.filter { it.string("type") == "tool_use" }.map {
                        ToolCall(
                            it.string("id").orEmpty(),
                            it.string("name").orEmpty(),
                            it["input"] as? JsonObject ?: JsonObject(emptyMap()),
                        )
                    },
                // Text and tool calls can be rebuilt from the message. Everything else, such as a
                // thinking block, must go back to the API exactly as it came.
                providerContent = JsonArray(content).takeIf { blocks -> blocks.any { (it as JsonObject).string("type") !in rebuilt } },
                truncated = stopReason in truncating,
            )
        val usage =
            (body["usage"] as? JsonObject)?.let { usage ->
                val input = inputCounters.sumOf { usage.int(it) }
                TokenUsage(inputTokens = input, outputTokens = usage.int("output_tokens"))
            }
        return ChatResponse(message, usage)
    }

    public companion object {
        /** The default for `maxTokens`. */
        public const val DEFAULT_MAX_TOKENS: Int = 16_000

        private const val API_VERSION = "2023-06-01"

        private val rebuilt = setOf("text", "tool_use")
        private val truncating = setOf("max_tokens", "model_context_window_exceeded")

        /** The API counts tokens that were written to or read from the prompt cache apart from the rest of the input. */
        private val inputCounters = listOf("input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens")
    }
}

/**
 * Returns [messages] as the turns of the Messages API. Tool results travel in a user turn, and all
 * results of one assistant turn must be in the same one, so neighbouring user messages and tool
 * results become a single turn, with the results first.
 */
private fun turns(messages: List<ChatMessage>): JsonArray =
    buildJsonArray {
        var index = 0
        while (index < messages.size) {
            val message = messages[index]
            if (message is ChatMessage.Assistant) {
                val content = message.providerContent as? JsonArray ?: blocks(message)
                if (content.isNotEmpty()) addTurn("assistant", content)
                index++
            } else {
                val end = (index until messages.size).firstOrNull { messages[it] is ChatMessage.Assistant } ?: messages.size
                val turn = messages.subList(index, end)
                addTurn(
                    "user",
                    buildJsonArray {
                        turn.filterIsInstance<ChatMessage.ToolResult>().forEach { result ->
                            addJsonObject {
                                put("type", "tool_result")
                                put("tool_use_id", result.toolCallId)
                                put("content", result.text)
                                if (result.isError) put("is_error", true)
                            }
                        }
                        turn.filterIsInstance<ChatMessage.User>().forEach { add(textBlock(it.text)) }
                    },
                )
                index = end
            }
        }
    }

private fun JsonArrayBuilder.addTurn(role: String, content: JsonArray) {
    addJsonObject {
        put("role", role)
        put("content", content)
    }
}

private fun blocks(message: ChatMessage.Assistant): JsonArray =
    buildJsonArray {
        if (message.text.isNotEmpty()) add(textBlock(message.text))
        message.toolCalls.forEach { call ->
            addJsonObject {
                put("type", "tool_use")
                put("id", call.id)
                put("name", call.name)
                put("input", call.input)
            }
        }
    }

private fun textBlock(text: String): JsonObject =
    buildJsonObject {
        put("type", "text")
        put("text", text)
    }

private fun JsonObject?.string(key: String): String? = (this?.get(key) as? JsonPrimitive)?.contentOrNull

private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: 0

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
