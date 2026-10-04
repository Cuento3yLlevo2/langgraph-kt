package org.langgraphkt.agent

import org.langgraphkt.LangGraphException

/**
 * A language model that continues a conversation. It is the only thing the rest of this module
 * needs from a model provider, so a graph written against it runs on every Kotlin target and with
 * any provider.
 *
 * Use an implementation from an integration module (`langgraph-kt-anthropic`,
 * `langgraph-kt-langchain4j`), or write one. In a test, a lambda is enough:
 *
 * ```kotlin
 * val model = ChatModel { request -> ChatResponse(ChatMessage.Assistant("Hello!")) }
 * ```
 */
public fun interface ChatModel {
    /**
     * Returns the model's next message for [request].
     *
     * @throws ChatModelException when the call fails or the model declines to answer.
     */
    public suspend fun chat(request: ChatRequest): ChatResponse
}

/**
 * Sends [prompt] as a single user message and returns the text of the answer, for a node that only
 * needs a piece of text from the model. Call [ChatModel.chat] with a [ChatRequest] when you need to
 * know whether the answer was cut off.
 *
 * ```kotlin
 * node("summarize", work = { model.chat("Summarize: ${it.notes}") }) { state, summary ->
 *     state.copy(summary = summary)
 * }
 * ```
 */
public suspend fun ChatModel.chat(prompt: String, system: String? = null): String =
    chat(ChatRequest(listOf(ChatMessage.User(prompt)), system)).message.text

/**
 * What a [ChatModel] is asked.
 *
 * @property messages the conversation so far, oldest first.
 * @property system instructions for the model that are not part of the conversation.
 * @property tools the tools the model may ask for.
 */
public data class ChatRequest(
    val messages: List<ChatMessage>,
    val system: String? = null,
    val tools: List<ToolSpec> = emptyList(),
)

/**
 * What a [ChatModel] answered.
 *
 * @property message the model's message. Add it to the conversation before the next request. Its
 * [ChatMessage.Assistant.truncated] tells whether the model reached its output limit.
 * @property usage the tokens the call used, when the provider reports them.
 */
public data class ChatResponse(
    val message: ChatMessage.Assistant,
    val usage: TokenUsage? = null,
)

/** The tokens one call to a [ChatModel] read ([inputTokens]) and wrote ([outputTokens]). */
public data class TokenUsage(
    val inputTokens: Int,
    val outputTokens: Int,
)

/**
 * A call to a [ChatModel] failed: the provider could not be reached, it returned an error, or the
 * model declined to answer.
 */
public class ChatModelException(
    message: String,
    cause: Throwable? = null,
) : LangGraphException(message, cause)
