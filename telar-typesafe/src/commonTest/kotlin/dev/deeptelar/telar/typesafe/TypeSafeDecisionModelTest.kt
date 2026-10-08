package dev.deeptelar.telar.typesafe

import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import dev.deeptelar.telar.agent.Answer
import dev.deeptelar.telar.agent.DecisionModelException
import dev.deeptelar.telar.agent.DecisionRequest
import dev.deeptelar.telar.agent.Question
import dev.deeptelar.telar.agent.TokenUsage
import dev.deeptelar.telar.agent.choose
import dev.deeptelar.telar.agent.decisionEdge
import dev.deeptelar.telar.agent.isYes
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class TypeSafeDecisionModelTest {
    private val sent = mutableListOf<HttpRequestData>()
    private val payouts = "Help! My payouts have been failing for 3 days."

    private fun client(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpClient =
        HttpClient(
            MockEngine { request ->
                sent += request
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        )

    private fun model(body: String, status: HttpStatusCode = HttpStatusCode.OK): TypeSafeDecisionModel =
        TypeSafeDecisionModel(client(body, status), apiKey = "test-key")

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun sentBody(): JsonObject = json((sent.single().body as TextContent).text) as JsonObject

    private fun answers(answers: String): String =
        """{"model": "jev-1.13.0", "answers": $answers, "usage": {"input_tokens": 296, "output_tokens": 20}}"""

    private val urgent = answers("""{"decision": {"type": "noul", "noul": 0.95}}""")

    @Test
    fun `a request has the headers and the body the System One API expects`() =
        runTest {
            val model =
                model(
                    answers(
                        """
                        {
                          "is_urgent": {"type": "noul", "noul": 0.95},
                          "plain": {"type": "noul", "noul": 0.2},
                          "department": {
                            "type": "choice", "choice": "billing",
                            "probabilities": {"billing": 0.88, "technical": 0.12, "sales": 0.0}, "confidence": 0.81
                          },
                          "frustration": {
                            "type": "score", "score": 1.05,
                            "legend": {"0": "Calm", "1": "Frustrated", "2": "Very angry"},
                            "probabilities": {"0": 0.0, "1": 0.95, "2": 0.05}, "confidence": 0.92
                          }
                        }
                        """,
                    ),
                )

            val response =
                model.decide(
                    DecisionRequest(
                        payouts,
                        mapOf(
                            "is_urgent" to
                                Question.YesNo("Does this convey urgency?", yes = "Explicitly time-sensitive", no = "No urgency expressed"),
                            "plain" to Question.YesNo("Is this spam?"),
                            "department" to
                                Question.Choice(
                                    "Which team should handle this?",
                                    mapOf(
                                        "billing" to "Payments, invoicing, refunds",
                                        "technical" to "Bugs, outages, integrations",
                                        "sales" to null,
                                    ),
                                ),
                            "frustration" to Question.Score("How frustrated is the customer?", listOf("Calm", "Frustrated", "Very angry")),
                        ),
                    ),
                )

            assertEquals(
                mapOf(
                    "is_urgent" to Answer.YesNo(0.95),
                    "plain" to Answer.YesNo(0.2),
                    "department" to
                        Answer.Choice("billing", mapOf("billing" to 0.88, "technical" to 0.12, "sales" to 0.0), confidence = 0.81),
                    "frustration" to Answer.Score(1.05, listOf(0.0, 0.95, 0.05), confidence = 0.92),
                ),
                response.answers,
            )
            assertEquals(TokenUsage(inputTokens = 296, outputTokens = 20), response.usage)
            val request = sent.single()
            assertEquals("https://api.typesafe.ai/v1/systemone", request.url.toString())
            assertEquals("Bearer test-key", request.headers["Authorization"])
            assertEquals("application/json", request.body.contentType.toString())
            assertEquals(
                json(
                    """
                    {
                      "state": "Help! My payouts have been failing for 3 days.",
                      "model": "jev-latest",
                      "questions": {
                        "is_urgent": {
                          "type": "noul",
                          "instructions": "Does this convey urgency?",
                          "criteria": {"true": "Explicitly time-sensitive", "false": "No urgency expressed"}
                        },
                        "plain": {"type": "noul", "instructions": "Is this spam?"},
                        "department": {
                          "type": "choice",
                          "instructions": "Which team should handle this?",
                          "criteria": {"billing": "Payments, invoicing, refunds", "technical": "Bugs, outages, integrations", "sales": null}
                        },
                        "frustration": {
                          "type": "score",
                          "instructions": "How frustrated is the customer?",
                          "criteria": ["Calm", "Frustrated", "Very angry"]
                        }
                      }
                    }
                    """,
                ),
                sentBody(),
            )
        }

    @Test
    fun `a yes or no question may describe only one of its answers`() =
        runTest {
            model(urgent).decide(DecisionRequest(payouts, mapOf("decision" to Question.YesNo("Urgent?", no = "No urgency expressed"))))

            assertEquals(
                json("""{"false": "No urgency expressed"}"""),
                (sentBody()["questions"] as JsonObject).let {
                    (it["decision"] as JsonObject)["criteria"]
                },
            )
        }

    @Test
    fun `the state can be structured data`() =
        runTest {
            val state = buildJsonObject { put("customer", "Ana") }

            model(urgent).decide(DecisionRequest(state, mapOf("decision" to Question.YesNo("Urgent?"))))

            assertEquals(state, sentBody()["state"])
        }

    @Test
    fun `the model and headers and address can be changed`() =
        runTest {
            val model =
                TypeSafeDecisionModel(
                    client(urgent),
                    apiKey = "test-key",
                    model = "jev-1.13.0",
                    headers = mapOf("X-Team" to "support"),
                    baseUrl = "https://gateway.example.com/typesafe/",
                )

            assertEquals(0.95, model.isYes(payouts, "Urgent?").probability)

            assertEquals("https://gateway.example.com/typesafe/v1/systemone", sent.single().url.toString())
            assertEquals("support", sent.single().headers["X-Team"])
            assertEquals(json("\"jev-1.13.0\""), sentBody()["model"])
        }

    @Test
    fun `an answer without probabilities or usage is still an answer`() =
        runTest {
            val model = model("""{"answers": {"decision": {"type": "choice", "choice": "billing"}}}""")

            val response =
                model.decide(
                    DecisionRequest(
                        payouts,
                        mapOf(
                            "decision" to Question.Choice("Which team?", mapOf("billing" to null)),
                        ),
                    ),
                )

            assertEquals(Answer.Choice("billing", emptyMap(), confidence = 1.0), response.answers["decision"])
            assertNull(response.usage)
        }

    @Test
    fun `a question without an answer of its kind is an error`() =
        runTest {
            val question = mapOf("decision" to Question.Choice("Which team?", mapOf("billing" to null)))
            val bodies =
                listOf(
                    answers("{}"),
                    """{"model": "jev-1.13.0"}""",
                    urgent,
                    answers("""{"decision": {"type": "choice"}}"""),
                )

            for (body in bodies) {
                val failure = assertFailsWith<DecisionModelException> { model(body).decide(DecisionRequest(payouts, question)) }
                assertEquals("The TypeSafe API returned no choice answer for the question 'decision'.", failure.message)
            }
        }

    @Test
    fun `an error of the API is reported with its status and body`() =
        runTest {
            val invalid =
                assertFailsWith<DecisionModelException> {
                    model(
                        """{"detail": "questions.department.criteria: field required"}""",
                        HttpStatusCode.UnprocessableEntity,
                    ).isYes(payouts, "Urgent?")
                }
            val empty = assertFailsWith<DecisionModelException> { model("", HttpStatusCode.TooManyRequests).isYes(payouts, "Urgent?") }

            assertEquals("""TypeSafe API error 422: {"detail": "questions.department.criteria: field required"}""", invalid.message)
            assertEquals("TypeSafe API error 429: no details", empty.message)
        }

    @Test
    fun `a successful response that is not a JSON object is an error`() =
        runTest {
            for (body in listOf("[]", "not json")) {
                val failure = assertFailsWith<DecisionModelException> { model(body).isYes(payouts, "Urgent?") }
                assertEquals("The TypeSafe API returned a response that is not a JSON object.", failure.message)
            }
        }

    @Test
    fun `a failure to reach the API is an error with the cause`() =
        runTest {
            val unreachable = HttpClient(MockEngine { throw IllegalStateException("No network") })

            val failure =
                assertFailsWith<DecisionModelException> { TypeSafeDecisionModel(unreachable, "test-key").isYes(payouts, "Urgent?") }

            assertEquals("Could not reach the TypeSafe API: No network", failure.message)
            assertIs<IllegalStateException>(failure.cause)
        }

    @Test
    fun `a cancellation is not turned into an error of the model`() =
        runTest {
            val cancelling = HttpClient(MockEngine { throw CancellationException("Stopped") })

            val failure = runCatching { TypeSafeDecisionModel(cancelling, "test-key").isYes(payouts, "Urgent?") }.exceptionOrNull()

            assertIs<CancellationException>(failure)
        }

    @Test
    fun `Jev routes a graph and an unsure decision goes to the fallback`() =
        runTest {
            fun graph(confidence: Double) =
                StateGraph<String> {
                    val billing = node("billing") { "billing" }
                    val technical = node("technical") { "technical" }
                    val person = node("person") { "person" }

                    decisionEdge(
                        START,
                        model(
                            answers(
                                """{"decision": {"type": "choice", "choice": "billing", "probabilities": {"billing": 0.6, "technical": 0.4}, "confidence": $confidence}}""",
                            ),
                        ),
                        "Which team should handle this?",
                        routes = mapOf(billing to "Payments, invoicing, refunds", technical to "Bugs, outages, integrations"),
                        minConfidence = 0.5,
                        fallback = person,
                    ) { it }
                }.compile()

            assertEquals("billing", graph(0.81).invoke(payouts).state)
            assertEquals("person", graph(0.2).invoke(payouts).state)
            assertEquals(
                Answer.Choice("billing", mapOf("billing" to 0.6, "technical" to 0.4), 0.2),
                model(
                    answers(
                        """{"decision": {"type": "choice", "choice": "billing", "probabilities": {"billing": 0.6, "technical": 0.4}, "confidence": 0.2}}""",
                    ),
                ).choose(payouts, "Which team?", mapOf("billing" to null, "technical" to null)),
            )
        }
}
