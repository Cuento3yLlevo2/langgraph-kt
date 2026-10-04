package org.langgraphkt.langchain4j

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessageDeserializer
import dev.langchain4j.data.message.ChatMessageSerializer
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema
import dev.langchain4j.model.chat.request.json.JsonArraySchema
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema
import dev.langchain4j.model.chat.request.json.JsonEnumSchema
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema
import dev.langchain4j.model.chat.request.json.JsonNullSchema
import dev.langchain4j.model.chat.request.json.JsonNumberSchema
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import dev.langchain4j.model.chat.request.json.JsonRawSchema
import dev.langchain4j.model.chat.request.json.JsonSchemaElement
import dev.langchain4j.model.chat.request.json.JsonStringSchema
import dev.langchain4j.model.output.FinishReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.langgraphkt.agent.ChatMessage
import org.langgraphkt.agent.ChatModel
import org.langgraphkt.agent.ChatModelException
import org.langgraphkt.agent.ChatRequest
import org.langgraphkt.agent.ChatResponse
import org.langgraphkt.agent.TokenUsage
import org.langgraphkt.agent.ToolCall
import org.langgraphkt.agent.ToolSpec
import java.util.UUID
import dev.langchain4j.data.message.ChatMessage as LangChain4jMessage
import dev.langchain4j.model.chat.request.ChatRequest as LangChain4jRequest
import dev.langchain4j.model.chat.response.ChatResponse as LangChain4jResponse

/**
 * A [ChatModel] that calls any LangChain4j chat model, so an agent built with `toolAgent` or
 * `toolLoop` works with every provider LangChain4j supports.
 *
 * ```kotlin
 * val openAi = OpenAiChatModel.builder().apiKey(key).modelName("gpt-5").build()
 * val agent = toolAgent(LangChain4jChatModel(openAi), tools = listOf(forecast))
 * ```
 *
 * LangChain4j calls block on network I/O, so each call runs on [Dispatchers.IO]. A model's thinking
 * and other details that LangChain4j keeps in its `AiMessage` travel in
 * [ChatMessage.Assistant.providerContent].
 *
 * @param model the LangChain4j chat model to call.
 */
public class LangChain4jChatModel(
    private val model: dev.langchain4j.model.chat.ChatModel,
) : ChatModel {
    /**
     * Sends [request] to the LangChain4j model and returns its answer.
     *
     * @throws ChatModelException when the model throws, and when its answer was filtered.
     */
    override suspend fun chat(request: ChatRequest): ChatResponse {
        val messages = listOfNotNull(request.system?.let { SystemMessage.from(it) }) + request.messages.mapNotNull(::message)
        val builder = LangChain4jRequest.builder().messages(messages)
        if (request.tools.isNotEmpty()) builder.toolSpecifications(request.tools.map(::specification))

        val response =
            try {
                withContext(Dispatchers.IO) { model.chat(builder.build()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw ChatModelException("The LangChain4j model failed: ${e.message ?: e::class.simpleName}", e)
            }
        return response(response)
    }
}

/** Returns [message] as a LangChain4j message, or `null` for an assistant message with nothing in it. */
private fun message(message: ChatMessage): LangChain4jMessage? =
    when (message) {
        is ChatMessage.User -> UserMessage.from(message.text)
        is ChatMessage.ToolResult ->
            ToolExecutionResultMessage
                .builder()
                .id(message.toolCallId)
                .toolName(message.toolName)
                .text(message.text)
                .isError(message.isError)
                .build()
        is ChatMessage.Assistant -> kept(message) ?: rebuilt(message)
    }

/** Returns the `AiMessage` that [response] stored in [message], if it is one. */
private fun kept(message: ChatMessage.Assistant): AiMessage? {
    val content = message.providerContent as? JsonObject ?: return null
    return try {
        ChatMessageDeserializer.messageFromJson(content.toString()) as? AiMessage
    } catch (_: RuntimeException) {
        // The content is from another provider's ChatModel.
        null
    }
}

private fun rebuilt(message: ChatMessage.Assistant): AiMessage? {
    if (message.text.isEmpty() && message.toolCalls.isEmpty()) return null
    val requests =
        message.toolCalls.map {
            ToolExecutionRequest
                .builder()
                .id(it.id)
                .name(it.name)
                .arguments(it.input.toString())
                .build()
        }
    return AiMessage
        .builder()
        .text(message.text.ifEmpty { null })
        .toolExecutionRequests(requests)
        .build()
}

private fun response(response: LangChain4jResponse): ChatResponse {
    if (response.finishReason() == FinishReason.CONTENT_FILTER) throw ChatModelException("The model's answer was filtered.")

    val answer = response.aiMessage()
    val message =
        ChatMessage.Assistant(
            text = answer.text().orEmpty(),
            toolCalls =
                answer.toolExecutionRequests().orEmpty().map {
                    // Some providers do not number their tool calls.
                    ToolCall(it.id() ?: "call-${UUID.randomUUID()}", it.name(), arguments(it.arguments()))
                },
            // Text and tool calls can be rebuilt from the message. Thinking and provider attributes cannot.
            providerContent =
                if (answer.thinking() == null && answer.attributes().isNullOrEmpty()) {
                    null
                } else {
                    Json.parseToJsonElement(ChatMessageSerializer.messageToJson(answer))
                },
            truncated = response.finishReason() == FinishReason.LENGTH,
        )
    val usage = response.tokenUsage()?.let { TokenUsage(it.inputTokenCount() ?: 0, it.outputTokenCount() ?: 0) }
    return ChatResponse(message, usage)
}

private fun arguments(json: String?): JsonObject =
    try {
        Json.parseToJsonElement(json.orEmpty()) as? JsonObject
    } catch (_: SerializationException) {
        null
    } ?: JsonObject(emptyMap())

private fun specification(tool: ToolSpec): ToolSpecification =
    ToolSpecification
        .builder()
        .name(tool.name)
        .description(tool.description)
        .parameters(objectSchema(tool.inputSchema))
        .build()

/**
 * Returns the JSON Schema [schema] as a LangChain4j schema element. A schema that uses keywords
 * LangChain4j has no element for is passed on as it is, in a [JsonRawSchema].
 */
private fun element(schema: JsonObject): JsonSchemaElement {
    val description = schema.string("description")
    val type = schema.string("type")
    val keywords = schema.keys - "description"
    return when {
        keywords == setOf("anyOf") && schema["anyOf"] is JsonArray ->
            JsonAnyOfSchema
                .builder()
                .description(description)
                .anyOf((schema["anyOf"] as JsonArray).map { element(it as? JsonObject ?: JsonObject(emptyMap())) })
                .build()
        type == "object" && keywords.all { it in objectKeywords } && schema["additionalProperties"] !is JsonObject -> objectSchema(schema)
        type == "array" && keywords == setOf("type", "items") && schema["items"] is JsonObject ->
            JsonArraySchema
                .builder()
                .description(description)
                .items(element(schema["items"] as JsonObject))
                .build()
        type == "string" && keywords == setOf("type", "enum") ->
            JsonEnumSchema
                .builder()
                .description(description)
                .enumValues(schema.strings("enum"))
                .build()
        keywords != setOf("type") -> JsonRawSchema.from(schema.toString())
        type == "string" -> JsonStringSchema.builder().description(description).build()
        type == "integer" -> JsonIntegerSchema.builder().description(description).build()
        type == "number" -> JsonNumberSchema.builder().description(description).build()
        type == "boolean" -> JsonBooleanSchema.builder().description(description).build()
        type == "null" -> JsonNullSchema()
        else -> JsonRawSchema.from(schema.toString())
    }
}

/** The input of a tool is always an object, so the keywords LangChain4j has no place for are dropped. */
private fun objectSchema(schema: JsonObject): JsonObjectSchema {
    val builder = JsonObjectSchema.builder().description(schema.string("description"))
    (schema["properties"] as? JsonObject)?.forEach { (name, property) ->
        builder.addProperty(name, element(property as? JsonObject ?: JsonObject(emptyMap())))
    }
    builder.required(schema.strings("required"))
    (schema["additionalProperties"] as? JsonPrimitive)?.booleanOrNull?.let { builder.additionalProperties(it) }
    return builder.build()
}

private val objectKeywords = setOf("type", "properties", "required", "additionalProperties")

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
