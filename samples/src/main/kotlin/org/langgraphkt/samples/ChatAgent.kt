package org.langgraphkt.samples

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import org.langgraphkt.CompiledGraph
import org.langgraphkt.END
import org.langgraphkt.START
import org.langgraphkt.StateGraph
import org.langgraphkt.langchain4j.chatMessagesNode

data class ChatState(
    val messages: List<ChatMessage>,
    val needsEscalation: Boolean = false,
)

/**
 * A support agent: the model answers, and a conditional edge escalates when the reply asks for a human.
 *
 * Pass any LangChain4j [ChatModel], for example `OpenAiChatModel` or `OllamaChatModel`.
 */
fun supportAgent(model: ChatModel): CompiledGraph<ChatState> =
    StateGraph<ChatState> {
        val assistant =
            node(
                "assistant",
                chatMessagesNode(
                    model = model,
                    messages = { it.messages },
                    update = { state, response -> state.copy(messages = state.messages + response.aiMessage()) },
                ),
            )
        val escalate = node("escalate") { it.copy(needsEscalation = true) }

        START then assistant
        conditionalEdge(assistant, targets = setOf(escalate.name, END)) { state ->
            val reply = (state.messages.last() as AiMessage).text()
            if ("human" in reply.lowercase()) escalate.name else END
        }
    }.compile()

/** Offline stand-in for a real model so the sample runs without an API key. */
class CannedModel : ChatModel {
    override fun doChat(chatRequest: ChatRequest): ChatResponse {
        val question =
            chatRequest
                .messages()
                .filterIsInstance<UserMessage>()
                .last()
                .singleText()
        val reply = if ("refund" in question) "I need a human colleague to handle refunds." else "Restart the app and try again."
        return ChatResponse.builder().aiMessage(AiMessage.from(reply)).build()
    }
}

suspend fun main() {
    val agent = supportAgent(CannedModel())
    for (question in listOf("The app is frozen", "I want a refund")) {
        val result = agent.invoke(ChatState(listOf(SystemMessage.from("You are a support agent."), UserMessage.from(question))))
        println("$question -> ${(result.state.messages.last() as AiMessage).text()} (escalate: ${result.state.needsEscalation})")
    }
}
