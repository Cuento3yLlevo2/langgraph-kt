package org.langgraphkt.langchain4j

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import kotlinx.coroutines.test.runTest
import org.langgraphkt.END
import org.langgraphkt.START
import org.langgraphkt.StateGraph
import kotlin.test.Test
import kotlin.test.assertEquals

data class BotState(
    val input: String = "",
    val output: String = "",
    val history: List<ChatMessage> = emptyList(),
)

/** Echoes the last user message back, so tests need no network. */
class EchoModel : ChatModel {
    override fun doChat(chatRequest: ChatRequest): ChatResponse {
        val lastUser =
            chatRequest
                .messages()
                .filterIsInstance<UserMessage>()
                .last()
                .singleText()
        return ChatResponse.builder().aiMessage(AiMessage.from("Echo: $lastUser")).build()
    }
}

class LangChain4jExtensionsTest {
    @Test
    fun `chatNode updates state with the model reply`() =
        runTest {
            val app =
                StateGraph<BotState> {
                    node(
                        "llm",
                        chatNode(
                            model = EchoModel(),
                            prompt = { it.input },
                            update = { state, reply -> state.copy(output = reply) },
                        ),
                    )
                    edge(START, "llm")
                    edge("llm", END)
                }.compile()

            assertEquals("Echo: Hello!", app.invoke(BotState(input = "Hello!")).state.output)
        }

    @Test
    fun `chatMessagesNode sends the conversation and appends the AI message`() =
        runTest {
            val app =
                StateGraph<BotState> {
                    node(
                        "llm",
                        chatMessagesNode(
                            model = EchoModel(),
                            messages = { it.history },
                            update = { state, response -> state.copy(history = state.history + response.aiMessage()) },
                        ),
                    )
                    edge(START, "llm")
                    edge("llm", END)
                }.compile()

            val initial = BotState(history = listOf(SystemMessage.from("Be terse."), UserMessage.from("ping")))
            val result = app.invoke(initial).state

            assertEquals(3, result.history.size)
            assertEquals("Echo: ping", (result.history.last() as AiMessage).text())
        }
}
