package org.langgraphkt.samples.tutorial.level8

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import org.langgraphkt.CompiledGraph
import org.langgraphkt.END
import org.langgraphkt.START
import org.langgraphkt.StateGraph
import org.langgraphkt.langchain4j.chatNode

data class Ticket(
    val customer: String,
    val message: String,
    val reply: String = "",
)

/** Level 8 of the tutorial in `docs/`: a node that asks an AI model. Any LangChain4j [ChatModel] works. */
fun helpDesk(model: ChatModel): CompiledGraph<Ticket> =
    StateGraph<Ticket> {
        val answer =
            node(
                "answer",
                chatNode(
                    model = model,
                    prompt = { ticket ->
                        "You work at the help desk of Pixel Pizza. Reply in one friendly sentence " +
                            "to ${ticket.customer}, who wrote: ${ticket.message}"
                    },
                    update = { ticket, reply -> ticket.copy(reply = reply) },
                ),
            )

        START then answer then END
    }.compile()

/** Stands in for a real model, so the level runs without an account or an API key. */
class PretendModel : ChatModel {
    override fun doChat(chatRequest: ChatRequest): ChatResponse =
        ChatResponse.builder().aiMessage(AiMessage.from("Thanks for your patience! Your pizza is on its way.")).build()
}

suspend fun main() {
    val result = helpDesk(PretendModel()).invoke(Ticket(customer = "Ana", message = "Where is my pizza?"))
    println(result.state.reply)
}
