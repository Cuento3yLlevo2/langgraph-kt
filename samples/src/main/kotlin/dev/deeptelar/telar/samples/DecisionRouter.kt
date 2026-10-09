package dev.deeptelar.telar.samples

import dev.deeptelar.telar.CompiledGraph
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import dev.deeptelar.telar.agent.Answer
import dev.deeptelar.telar.agent.ChatDecisionModel
import dev.deeptelar.telar.agent.DecisionModel
import dev.deeptelar.telar.agent.DecisionResponse
import dev.deeptelar.telar.agent.decisionEdge
import dev.deeptelar.telar.typesafe.TypeSafeDecisionModel
import io.ktor.client.HttpClient
import kotlinx.serialization.json.jsonPrimitive

/** Below this confidence a person reads the email. */
const val SURE_ENOUGH = 0.6

/**
 * The email support of the quick start, with a decision model in the place of `categoryOf`: the
 * model reads the email and picks the node that replies. A person is one of the nodes it can pick,
 * for a complaint and for everything else that is neither a refund nor a defect. An email the
 * model is not sure about goes to the person as well.
 *
 * Pass any [DecisionModel]: `TypeSafeDecisionModel` for Jev, `ChatDecisionModel` for a chat model
 * you already use, or one of your own.
 */
fun routedSupport(model: DecisionModel): CompiledGraph<SupportEmail> =
    StateGraph<SupportEmail> {
        val refund =
            node("refund") { email ->
                email.copy(category = Category.REFUND, reply = "Hi ${email.sender}, your refund is on its way. It takes 3 to 5 days.")
            }
        val technical =
            node("technical") { email ->
                email.copy(
                    category = Category.TECHNICAL,
                    reply = "Hi ${email.sender}, please update the app and try again. Here is our guide.",
                )
            }
        val escalate =
            node("escalate") { email ->
                email.copy(
                    category = Category.ESCALATION,
                    reply = "Hi ${email.sender}, a colleague from our team will reply to you personally today.",
                )
            }

        // The model sees the names of the nodes and these descriptions, and picks one.
        decisionEdge(
            from = START,
            model = model,
            instructions = "What does the customer who wrote this support email want?",
            routes =
                mapOf(
                    refund to "Money back for an order or a charge",
                    technical to "Help with something that does not work",
                    // A model picks one of its options, also when none fits. Without this one, Jev
                    // sends an angry email that asks for nothing to `technical`, and is sure of it.
                    escalate to "A complaint, or anything else that a person should read",
                ),
            // A person also reads an email that the model is not sure about.
            minConfidence = SURE_ENOUGH,
            fallback = escalate,
        ) { email -> email.body }
        // Each of the three nodes has no edge of its own, so the run ends after it.
    }.compile()

/** Offline stand-in for a real decision model, so the sample runs without an API key. */
val scriptedDecisions: DecisionModel =
    DecisionModel { request ->
        val body = request.state.jsonPrimitive.content
        val answer =
            when {
                // This stand-in is not sure about an email with two requests. Jev is: it picks the refund.
                listOf("refund", "error").all { it in body.lowercase() } -> choice("refund" to 0.5, "technical" to 0.45, "escalate" to 0.05)
                categoryOf(body) == Category.REFUND -> choice("refund" to 0.9, "technical" to 0.05, "escalate" to 0.05)
                categoryOf(body) == Category.TECHNICAL -> choice("refund" to 0.05, "technical" to 0.9, "escalate" to 0.05)
                else -> choice("refund" to 0.05, "technical" to 0.05, "escalate" to 0.9)
            }
        DecisionResponse(request.questions.mapValues { answer })
    }

/**
 * The answer of a model that gives its options these [probabilities]. The confidence is the one of
 * Jev: how far the most likely option is above an even split, from 0 to 1.
 */
private fun choice(vararg probabilities: Pair<String, Double>): Answer.Choice {
    val (option, highest) = probabilities.maxBy { it.second }
    val even = 1.0 / probabilities.size
    return Answer.Choice(option, probabilities.toMap(), confidence = (highest - even) / (1 - even))
}

/**
 * The decision model that the environment asks for: Jev with TYPESAFE_API_KEY, or else the chat
 * model that [modelFor] finds a key for, deciding through [ChatDecisionModel]. Without any key, the
 * scripted model decides.
 */
fun decisionModelFor(client: HttpClient, environment: Map<String, String> = System.getenv()): DecisionModel {
    val jevKey = environment["TYPESAFE_API_KEY"]?.takeIf { it.isNotBlank() }
    val chatModel = modelFor(client, environment)
    return when {
        jevKey != null -> TypeSafeDecisionModel(client, apiKey = jevKey)
        chatModel !== scriptedModel -> ChatDecisionModel(chatModel)
        else -> scriptedDecisions
    }
}

suspend fun main() {
    val client = HttpClient()
    val graph = routedSupport(decisionModelFor(client))

    val emails =
        listOf(
            SupportEmail(sender = "Ana", body = "I was charged twice, I would like a refund."),
            SupportEmail(sender = "Ben", body = "The app shows an error when I log in."),
            SupportEmail(sender = "Cleo", body = "This is unacceptable, third time I write to you!!"),
            SupportEmail(sender = "Dan", body = "I get an error when I pay, and I want a refund for last month."),
        )
    for (email in emails) {
        val result = graph.invoke(email)
        println("${result.state.category}: ${result.state.reply}")
    }
    client.close()
}
