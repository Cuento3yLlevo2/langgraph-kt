package org.langgraphkt.langchain4j

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.model.chat.ChatLanguageModel
import dev.langchain4j.model.output.Response
import kotlinx.coroutines.test.runTest
import org.langgraphkt.END
import org.langgraphkt.START
import org.langgraphkt.StateGraph
import kotlin.test.Test
import kotlin.test.assertEquals

data class BotState(
    val input: String = "",
    val output: String = "",
)

/**
 * A dummy model to mock LangChain4j for testing without network calls.
 */
class DummyModel : ChatLanguageModel {
    override fun generate(userMessage: String): String = "Echo: $userMessage"

    override fun generate(vararg messages: ChatMessage?): Response<AiMessage> = throw NotImplementedError()

    override fun generate(messages: MutableList<ChatMessage>?): Response<AiMessage> = throw NotImplementedError()

    override fun generate(
        messages: MutableList<ChatMessage>?,
        tools: MutableList<dev.langchain4j.agent.tool.ToolSpecification>?,
    ): Response<AiMessage> = throw NotImplementedError()

    override fun generate(messages: MutableList<ChatMessage>?, tool: dev.langchain4j.agent.tool.ToolSpecification?): Response<AiMessage> =
        throw NotImplementedError()
}

class LangChain4jExtensionsTest {
    @Test
    fun `generateNode updates state properly with LLM response`() =
        runTest {
            val model = DummyModel()

            val workflow =
                StateGraph<BotState> {
                    node(
                        "llm",
                        generateNode(
                            model = model,
                            promptBuilder = { it.input },
                            stateUpdater = { state, response -> state.copy(output = response) },
                        ),
                    )

                    edge(START, "llm")
                    edge("llm", END)
                }

            val app = workflow.compile()
            val result = app.invoke(BotState(input = "Hello!"))

            assertEquals("Echo: Hello!", result.output)
        }
}
