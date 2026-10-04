package org.langgraphkt.agent

import kotlinx.serialization.Serializable
import org.langgraphkt.CompiledGraph
import org.langgraphkt.GraphValidationException
import org.langgraphkt.NodeRef
import org.langgraphkt.START
import org.langgraphkt.StateGraph

/**
 * Adds the loop of a tool-calling agent to this graph and returns its first node.
 *
 * The loop has two nodes. The model node sends the conversation to [model]. When the answer asks
 * for tools, the tools node runs them in parallel and the model is asked again with their results.
 * When the answer asks for none, the graph continues at [then].
 *
 * ```kotlin
 * data class Ticket(val messages: List<ChatMessage>, val reply: String = "")
 *
 * val graph = StateGraph<Ticket> {
 *     val send = node("send") { it.copy(reply = it.messages.last().text) }
 *     val agent = toolLoop(
 *         model = model,
 *         tools = listOf(orderStatus, menuPrice),
 *         messages = { it.messages },
 *         append = { ticket, new -> ticket.copy(messages = ticket.messages + new) },
 *         then = send,
 *     )
 *     START then agent
 *     send then END
 * }.compile()
 *
 * graph.invoke(Ticket(listOf(ChatMessage.User("Where is my pizza?"))))
 * ```
 *
 * Both nodes have a `work` and an `update`, so the loop can run next to other branches without a
 * `Reducer`. Every round of tools takes two steps of the run, so raise `GraphConfig.maxIterations`
 * for an agent that needs many rounds.
 *
 * [messages] and [append] read and write the same list: [messages] must return what [append] stored.
 * When the conversation starts from other fields of the state, do not build the first message in
 * [messages], because an [append] that adds to the stored list would then lose it. Give it to
 * [firstMessage] instead, and the loop stores it with the model's first answer:
 *
 * ```kotlin
 * data class Order(val customer: String, val question: String, val messages: List<ChatMessage> = emptyList())
 *
 * toolLoop(
 *     model = model,
 *     tools = tools,
 *     messages = { it.messages },
 *     append = { order, new -> order.copy(messages = order.messages + new) },
 *     firstMessage = { "${it.customer} writes: ${it.question}" },
 * )
 * ```
 *
 * To approve tool calls by hand, run the graph with `interruptBefore = setOf("tools")`. The calls
 * that wait are `messages.pendingToolCalls()`. Resume the run to let them through, or resume it with
 * a state that has a [ChatMessage.ToolResult] for each call you reject: a call that already has a
 * result is not run.
 *
 * @param model the model to ask.
 * @param tools the tools the model may call.
 * @param messages reads the conversation from the state.
 * @param append returns the state with the given messages added to its conversation. It is called
 * with the model's answer, and with the results of the tools.
 * @param firstMessage returns the user message that starts the conversation. It is used only while
 * [messages] returns an empty list, and [append] receives it together with the model's first answer.
 * Leave it out when the state already holds the first message.
 * @param system instructions for the model.
 * @param then where the graph continues when the model is done. The default ends the branch.
 * @param modelNode the name of the model node.
 * @param toolsNode the name of the tools node.
 * @throws GraphValidationException if two tools have the same name, or a node name is already used.
 */
public fun <State> StateGraph<State>.toolLoop(
    model: ChatModel,
    tools: List<Tool>,
    messages: (State) -> List<ChatMessage>,
    append: (State, List<ChatMessage>) -> State,
    firstMessage: ((State) -> String)? = null,
    system: String? = null,
    then: NodeRef = NodeRef.END,
    modelNode: String = "model",
    toolsNode: String = "tools",
): NodeRef {
    val specs = tools.map { it.spec }
    specs.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys.firstOrNull()?.let {
        throw GraphValidationException("Two tools are named '$it'. Tool names must be unique.")
    }

    val askModel =
        node(
            modelNode,
            work = { state ->
                val stored = messages(state)
                val opening = if (stored.isEmpty() && firstMessage != null) listOf(ChatMessage.User(firstMessage(state))) else emptyList()
                val response = model.chat(ChatRequest(opening + stored, system, specs))
                if (response.truncated && response.message.toolCalls.isNotEmpty()) {
                    throw ChatModelException("The model reached its output limit in the middle of a tool call. Raise the limit.")
                }
                opening + response.message
            },
        ) { state, new -> append(state, new) }
    val runTools =
        node(toolsNode, work = { state -> tools.execute(messages(state).pendingToolCalls()) }) { state, results ->
            append(state, results)
        }

    conditionalEdge(askModel, targets = setOf(runTools, then)) { state ->
        if (messages(state).pendingToolCalls().isEmpty()) then else runTools
    }
    runTools then askModel
    return askModel
}

/**
 * Returns a graph that is a tool-calling agent: [model] answers the conversation in the state, and
 * runs [tools] as often as it needs to before it answers.
 *
 * ```kotlin
 * val agent = toolAgent(model, tools = listOf(forecast), system = "You are a travel assistant.")
 *
 * val first = agent.invoke(AgentState("What is the weather in Madrid?")).state
 * println(first.answer)
 * val second = agent.invoke(first.withUserMessage("And in Lisbon?")).state
 * ```
 *
 * The graph is a [toolLoop] with its default node names. Use [toolLoop] directly to put the agent
 * inside a larger graph, or to keep the conversation in a state of your own.
 *
 * @throws GraphValidationException if two tools have the same name.
 */
public fun toolAgent(model: ChatModel, tools: List<Tool> = emptyList(), system: String? = null): CompiledGraph<AgentState> =
    StateGraph<AgentState> {
        START then
            toolLoop(
                model = model,
                tools = tools,
                messages = { it.messages },
                append = { state, new -> state.copy(messages = state.messages + new) },
                system = system,
            )
    }.compile()

/**
 * The state of a [toolAgent]: the conversation so far, oldest message first.
 *
 * It is `@Serializable`, so a checkpointer that uses kotlinx.serialization can save it.
 */
@Serializable
public data class AgentState(
    val messages: List<ChatMessage> = emptyList(),
) {
    /** Starts a conversation with [question] as the user's first message. */
    public constructor(question: String) : this(listOf(ChatMessage.User(question)))

    /**
     * The text of the model's final answer, or `null` while the conversation does not end with one:
     * before the first run, or when the run paused with tool calls waiting.
     */
    public val answer: String?
        get() = (messages.lastOrNull() as? ChatMessage.Assistant)?.takeIf { it.toolCalls.isEmpty() }?.text

    /** Returns this state with [text] added as the user's next message, to continue the conversation. */
    public fun withUserMessage(text: String): AgentState = copy(messages = messages + ChatMessage.User(text))
}
