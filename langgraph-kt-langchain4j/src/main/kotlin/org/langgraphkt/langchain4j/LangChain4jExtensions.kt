package org.langgraphkt.langchain4j

import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.response.ChatResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.langgraphkt.NodeAction

/**
 * Sends a single user message to this [ChatModel] and returns the model's text reply.
 *
 * LangChain4j calls block on network I/O, so the call runs on [Dispatchers.IO] and the caller's
 * thread (for example the Android main thread) is never blocked.
 */
suspend fun ChatModel.chatSuspending(message: String): String = withContext(Dispatchers.IO) { chat(message) }

/**
 * Sends a conversation to this [ChatModel] and returns the full [ChatResponse] (AI message, tool
 * execution requests, token usage). Like the single-message overload, it runs on [Dispatchers.IO].
 */
suspend fun ChatModel.chatSuspending(messages: List<ChatMessage>): ChatResponse = withContext(Dispatchers.IO) { chat(messages) }

/**
 * Creates a [NodeAction] that sends a prompt built from the current state to [model] and merges the
 * text reply back into the state.
 *
 * ```kotlin
 * node("writer", chatNode(
 *     model = chatModel,
 *     prompt = { state -> "Summarize: ${state.notes}" },
 *     update = { state, reply -> state.copy(summary = reply) },
 * ))
 * ```
 *
 * @param model the LangChain4j chat model to call.
 * @param prompt builds the user message from the current state.
 * @param update returns a new state that includes the model's reply.
 */
fun <State> chatNode(model: ChatModel, prompt: suspend (State) -> String, update: suspend (State, String) -> State): NodeAction<State> =
    { state -> update(state, model.chatSuspending(prompt(state))) }

/**
 * Creates a [NodeAction] that sends a whole conversation (system, user, AI and tool messages)
 * built from the current state to [model]. The node gets the full [ChatResponse], so it can handle
 * tool execution requests.
 *
 * @param model the LangChain4j chat model to call.
 * @param messages builds the conversation from the current state.
 * @param update returns a new state that includes the model's response.
 */
fun <State> chatMessagesNode(
    model: ChatModel,
    messages: suspend (State) -> List<ChatMessage>,
    update: suspend (State, ChatResponse) -> State,
): NodeAction<State> = { state -> update(state, model.chatSuspending(messages(state))) }
