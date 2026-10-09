package dev.deeptelar.telar.typesafe

import dev.deeptelar.telar.GraphValidationException
import dev.deeptelar.telar.agent.Answer
import dev.deeptelar.telar.agent.DecisionModel
import dev.deeptelar.telar.agent.DecisionModelException
import dev.deeptelar.telar.agent.DecisionRequest
import dev.deeptelar.telar.agent.DecisionResponse
import dev.deeptelar.telar.agent.Question
import dev.deeptelar.telar.agent.TokenUsage
import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * A [DecisionModel] that calls Jev, the decision model of TypeSafe AI, through its System One API.
 * Jev does not write text: it picks an option, answers yes or no, or rates on a scale, in a
 * fraction of the time and the price of a chat model. It is built on Ktor, so it runs on every
 * Kotlin target.
 *
 * ```kotlin
 * val client = HttpClient()
 * val jev = TypeSafeDecisionModel(client, apiKey = System.getenv("TYPESAFE_API_KEY"))
 *
 * val answer = jev.choose(email.body, "Which team should handle this email?", mapOf("refund" to null, "technical" to null))
 * ```
 *
 * `HttpClient()` uses the Ktor engine among your dependencies. The client is yours: share one
 * between models, and close it when your app is done with it. A call may take one minute, whatever
 * limits the client and its engine have. Change the limit with `timeout`.
 *
 * The API answers `429` or `529` when it has too much to do. This class does not try again by
 * itself: install the `HttpRequestRetry` plugin of Ktor in [client] to retry those with a pause.
 *
 * All questions of one request are answered together, so several questions about the same state
 * cost one call. [Question.YesNo] is what TypeSafe calls a Noul.
 *
 * @param client the Ktor client that sends the requests.
 * @param apiKey the TypeSafe API key.
 * @param model the id of the model. `jev-latest` moves to each new release; give a version such as
 * `jev-1.13.0` when you have set confidence limits for it.
 * @param headers more headers for every request.
 * @param baseUrl where the API lives. Change it to go through a proxy or a gateway.
 * @param timeout how long one call may take. It replaces the limits of [client] and of its engine
 * for the requests of this model. `null` leaves those limits in place.
 * @throws GraphValidationException if [timeout] is not positive.
 */
public class TypeSafeDecisionModel(
    client: HttpClient,
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val headers: Map<String, String> = emptyMap(),
    private val baseUrl: String = "https://api.typesafe.ai",
    private val timeout: Duration? = 1.minutes,
) : DecisionModel {
    init {
        if (timeout != null && !timeout.isPositive()) throw GraphValidationException("timeout must be positive, was $timeout.")
    }

    // Ktor applies the limit of a request only in a client that has the HttpTimeout plugin. This
    // client has it, with everything else of the client it was made from, and shares its engine.
    private val client: HttpClient = if (timeout == null) client else client.config { install(HttpTimeout) }

    /**
     * Sends the questions of [request] to the System One API and returns Jev's answers.
     *
     * @throws DecisionModelException when the API cannot be reached, when it returns an error, and
     * when a question has no answer of its kind in the response.
     */
    override suspend fun decide(request: DecisionRequest): DecisionResponse {
        val status: Int
        val text: String
        try {
            val response =
                client.post("${baseUrl.trimEnd('/')}/v1/systemone") {
                    header("Authorization", "Bearer $apiKey")
                    this@TypeSafeDecisionModel.headers.forEach { (name, value) -> header(name, value) }
                    contentType(ContentType.Application.Json)
                    setBody(body(request).toString())
                    this@TypeSafeDecisionModel.timeout?.let { limit ->
                        timeout {
                            requestTimeoutMillis = limit.inWholeMilliseconds
                            socketTimeoutMillis = limit.inWholeMilliseconds
                        }
                    }
                }
            status = response.status.value
            text = response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw failed(e)
        }

        if (status !in 200..299) throw DecisionModelException("TypeSafe API error $status: ${text.trim().ifEmpty { "no details" }}")
        val body = parse(text) ?: throw DecisionModelException("The TypeSafe API returned a response that is not a JSON object.")
        val answers = body["answers"] as? JsonObject
        val usage =
            (body["usage"] as? JsonObject)?.let { TokenUsage(inputTokens = it.int("input_tokens"), outputTokens = it.int("output_tokens")) }
        return DecisionResponse(
            request.questions.mapValues { (name, question) ->
                answer(question, answers?.get(name) as? JsonObject)
                    ?: throw DecisionModelException("The TypeSafe API returned no ${question.type} answer for the question '$name'.")
            },
            usage,
        )
    }

    private fun body(request: DecisionRequest): JsonObject =
        buildJsonObject {
            put("state", request.state)
            put("model", model)
            putJsonObject("questions") {
                request.questions.forEach { (name, question) ->
                    putJsonObject(name) {
                        put("type", question.type)
                        put("instructions", question.instructions)
                        when (question) {
                            is Question.Choice ->
                                putJsonObject("criteria") {
                                    question.options.forEach { (option, description) ->
                                        put(option, description)
                                    }
                                }
                            is Question.Score -> putJsonArray("criteria") { question.levels.forEach { add(it) } }
                            is Question.YesNo ->
                                if (question.yes != null || question.no != null) {
                                    putJsonObject("criteria") {
                                        question.yes?.let { put("true", it) }
                                        question.no?.let { put("false", it) }
                                    }
                                }
                        }
                    }
                }
            }
        }

    /** The exception for a request that ended without a response. */
    private fun failed(cause: Exception): DecisionModelException =
        when {
            cause !is HttpRequestTimeoutException && cause !is SocketTimeoutException ->
                DecisionModelException("Could not reach the TypeSafe API: ${cause.message ?: cause::class.simpleName}", cause)
            timeout != null -> DecisionModelException("The TypeSafe API did not answer within $timeout.", cause)
            else -> DecisionModelException("The TypeSafe API did not answer in the time the client allows: ${cause.message}", cause)
        }

    /** Reads the answer to [question], or returns `null` when [answer] is not an answer of its kind. */
    private fun answer(question: Question, answer: JsonObject?): Answer? {
        if (answer == null || answer.string("type") != question.type) return null
        val probabilities =
            (answer["probabilities"] as? JsonObject).orEmpty().mapValues { (_, value) ->
                (value as? JsonPrimitive)?.doubleOrNull
                    ?: 0.0
            }
        val confidence = answer.double("confidence") ?: 1.0
        return when (question) {
            is Question.YesNo -> answer.double("noul")?.let { Answer.YesNo(it) }
            is Question.Choice -> answer.string("choice")?.let { Answer.Choice(it, probabilities, confidence) }
            is Question.Score ->
                answer.double("score")?.let { score ->
                    // The levels come back under their numbers, as text.
                    Answer.Score(score, question.levels.indices.map { probabilities[it.toString()] ?: 0.0 }, confidence)
                }
        }
    }

    public companion object {
        /** The default for `model`: the most recent stable release of Jev. */
        public const val DEFAULT_MODEL: String = "jev-latest"
    }
}

/** The name of the kind of question in the API. */
private val Question.type: String
    get() =
        when (this) {
            is Question.Choice -> "choice"
            is Question.Score -> "score"
            is Question.YesNo -> "noul"
        }

/** Returns [text] as a JSON object, or `null` when it is not one. */
private fun parse(text: String): JsonObject? =
    try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (_: SerializationException) {
        null
    }

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: 0
