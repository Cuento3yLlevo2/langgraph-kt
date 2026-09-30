package org.langgraphkt.langchain4j

import dev.langchain4j.model.chat.ChatLanguageModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.langgraphkt.NodeAction

/**
 * Executes a LangChain4j ChatLanguageModel generation within the IO dispatcher
 * to prevent the blocking network/JNI call from starving the main coroutine thread.
 */
suspend fun ChatLanguageModel.generateSuspending(message: String): String {
    return withContext(Dispatchers.IO) {
        generate(message)
    }
}

/**
 * A helper DSL function to create a NodeAction that leverages a ChatLanguageModel.
 *
 * @param model The ChatLanguageModel instance.
 * @param promptBuilder A function to extract the prompt string from the current State.
 * @param stateUpdater A function to update the State with the LLM's generated response.
 */
fun <State> generateNode(
    model: ChatLanguageModel,
    promptBuilder: (State) -> String,
    stateUpdater: (State, String) -> State
): NodeAction<State> {
    return { state ->
        val prompt = promptBuilder(state)
        val response = model.generateSuspending(prompt)
        stateUpdater(state, response)
    }
}
