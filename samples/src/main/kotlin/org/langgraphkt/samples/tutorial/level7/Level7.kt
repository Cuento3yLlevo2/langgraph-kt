package org.langgraphkt.samples.tutorial.level7

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.langgraphkt.CompiledGraph
import org.langgraphkt.END
import org.langgraphkt.START
import org.langgraphkt.StateGraph
import org.langgraphkt.agent.AgentState
import org.langgraphkt.agent.ChatMessage
import org.langgraphkt.agent.ChatModel
import org.langgraphkt.agent.ChatResponse
import org.langgraphkt.agent.Description
import org.langgraphkt.agent.Tool
import org.langgraphkt.agent.ToolCall
import org.langgraphkt.agent.chat
import org.langgraphkt.agent.toolAgent
import org.langgraphkt.agent.toolLoop

data class Ticket(
    val customer: String,
    val message: String,
    val conversation: List<ChatMessage> = emptyList(),
    val reply: String = "",
)

/** A node that asks an AI model. Any [ChatModel] works: the graph takes it as a parameter. */
fun replyDesk(model: ChatModel): CompiledGraph<Ticket> =
    StateGraph<Ticket> {
        val answer =
            node(
                "answer",
                work = { ticket ->
                    model.chat("Reply in one friendly sentence to ${ticket.customer}, who wrote: ${ticket.message}", system = HELP_DESK)
                },
            ) { ticket, reply -> ticket.copy(reply = reply) }

        START then answer then END
    }.compile()

@Serializable
data class OrderLookup(
    @Description("The customer's name, for example \"Ana\"") val customer: String,
)

@Serializable
data class MenuLookup(
    @Description("The item, for example \"cola\"") val item: String,
)

val menu = mapOf("margherita" to 9, "salad" to 6, "cola" to 2)

/** A tool: a name, a description that the model reads, and a function that returns text. */
val orderStatus: Tool =
    Tool<OrderLookup>("order_status", "Returns where a customer's order is right now.") { lookup ->
        "The pizza for ${lookup.customer} left the oven and the driver is 5 minutes away."
    }

/** A tool that throws does not stop the run: the model gets the message of the exception as the result. */
val menuPrice: Tool =
    Tool<MenuLookup>("menu_price", "Returns the price of one item on the menu.") { lookup ->
        val price = menu[lookup.item] ?: throw IllegalArgumentException("We do not sell ${lookup.item}.")
        "One ${lookup.item} costs $price euros."
    }

const val HELP_DESK = "You work at the help desk of Pixel Pizza. Never guess where an order is or what something costs."

/** Level 7 of the tutorial in `docs/`: a model that decides by itself which tools to call. */
fun helpDesk(model: ChatModel): CompiledGraph<AgentState> = toolAgent(model, tools = listOf(orderStatus, menuPrice), system = HELP_DESK)

/** The same agent as one part of a graph of your own, with the conversation in your own state. */
fun ticketDesk(model: ChatModel): CompiledGraph<Ticket> =
    StateGraph<Ticket> {
        val send = node("send") { ticket -> ticket.copy(reply = "Hi ${ticket.customer}! ${ticket.conversation.last().text}") }
        val agent =
            toolLoop(
                model = model,
                tools = listOf(orderStatus, menuPrice),
                messages = { ticket -> ticket.conversation },
                append = { ticket, new -> ticket.copy(conversation = ticket.conversation + new) },
                firstMessage = { ticket -> "I'm ${ticket.customer}. ${ticket.message}" },
                system = HELP_DESK,
                then = send,
            )

        START then agent
        send then END
    }.compile()

/**
 * Stands in for a real model, so the level runs without an account or an API key. It asks for a
 * tool it was given when the question has a word it knows, and answers with what the tools returned.
 */
val pretendModel: ChatModel =
    ChatModel { request ->
        val results = request.messages.takeLastWhile { it is ChatMessage.ToolResult }
        val question = request.messages.last { it is ChatMessage.User }.text
        val customer = Regex("I'm (\\w+)").find(question)?.groupValues?.get(1) ?: "the customer"
        val wanted =
            buildList {
                if ("where" in question.lowercase()) add(ToolCall("call-1", "order_status", buildJsonObject { put("customer", customer) }))
                (menu.keys + "tiramisu").filter { it in question.lowercase() }.forEach { item ->
                    add(ToolCall("call-$item", "menu_price", buildJsonObject { put("item", item) }))
                }
            }
        val calls = wanted.filter { call -> request.tools.any { it.name == call.name } }
        ChatResponse(
            when {
                results.isNotEmpty() -> ChatMessage.Assistant(results.joinToString(" ") { it.text })
                calls.isNotEmpty() -> ChatMessage.Assistant(toolCalls = calls)
                else -> ChatMessage.Assistant("Thanks for your message! We are looking into it.")
            },
        )
    }

fun describe(message: ChatMessage): String =
    when (message) {
        is ChatMessage.User -> "customer: ${message.text}"
        is ChatMessage.Assistant ->
            if (message.toolCalls.isEmpty()) {
                "model: ${message.text}"
            } else {
                "model asks for: " + message.toolCalls.joinToString { call -> "${call.name} ${call.input}" }
            }
        is ChatMessage.ToolResult -> "${message.toolName}: ${message.text}"
    }

suspend fun main() {
    val first = replyDesk(pretendModel).invoke(Ticket(customer = "Ana", message = "Where is my pizza?")).state
    println(first.reply)
    println()

    val state = helpDesk(pretendModel).invoke(AgentState("I'm Ana. Where is my pizza, and how much is a cola?")).state
    state.messages.forEach { message -> println(describe(message)) }
    println()

    val ticket = ticketDesk(pretendModel).invoke(Ticket(customer = "Ben", message = "Do you sell salad?")).state
    println(ticket.reply)
}
