package dev.deeptelar.telar.samples

import dev.deeptelar.telar.CompiledGraph
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import dev.deeptelar.telar.agent.Answer
import dev.deeptelar.telar.agent.DecisionModel
import dev.deeptelar.telar.agent.DecisionResponse
import dev.deeptelar.telar.agent.decisionEdge
import dev.deeptelar.telar.typesafe.TypeSafeDecisionModel
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import kotlinx.serialization.json.jsonPrimitive

/** Below this confidence a person reads the email. */
const val SURE_ENOUGH = 0.6

/**
 * The email support of the quick start, with a decision model in the place of `categoryOf`: the
 * model reads the email and picks the node that replies. An email it is not sure about goes to a
 * person.
 *
 * Pass any [DecisionModel]: `TypeSafeDecisionModel` for Jev, or one of your own.
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
                ),
            minConfidence = SURE_ENOUGH,
            fallback = escalate,
        ) { email -> email.body }
        // Each of the three nodes has no edge of its own, so the run ends after it.
    }.compile()

/** Offline stand-in for a real decision model, so the sample runs without an API key. */
val scriptedDecisions: DecisionModel =
    DecisionModel { request ->
        val answer =
            when (categoryOf(request.state.jsonPrimitive.content)) {
                Category.REFUND -> Answer.Choice("refund", mapOf("refund" to 0.95, "technical" to 0.05), confidence = 0.9)
                Category.TECHNICAL -> Answer.Choice("technical", mapOf("refund" to 0.05, "technical" to 0.95), confidence = 0.9)
                // Neither fits: the two options are as likely as each other, which is a confidence of 0.
                Category.ESCALATION -> Answer.Choice("refund", mapOf("refund" to 0.5, "technical" to 0.5), confidence = 0.0)
            }
        DecisionResponse(request.questions.mapValues { answer })
    }

/** Set TYPESAFE_API_KEY to let Jev decide. Without it, the scripted model does. */
suspend fun main() {
    val key = System.getenv("TYPESAFE_API_KEY")
    // The CIO engine of Ktor ends a request after 15 seconds. A model can take longer, so it gets 5 minutes.
    val client = HttpClient { install(HttpTimeout) { requestTimeoutMillis = 5 * 60 * 1000 } }
    val model = if (key.isNullOrBlank()) scriptedDecisions else TypeSafeDecisionModel(client, apiKey = key)
    val graph = routedSupport(model)

    val emails =
        listOf(
            SupportEmail(sender = "Ana", body = "I was charged twice, I would like a refund."),
            SupportEmail(sender = "Ben", body = "The app shows an error when I log in."),
            SupportEmail(sender = "Cleo", body = "This is unacceptable, third time I write to you!!"),
        )
    for (email in emails) {
        val result = graph.invoke(email)
        println("${result.state.category}: ${result.state.reply}")
    }
    client.close()
}
