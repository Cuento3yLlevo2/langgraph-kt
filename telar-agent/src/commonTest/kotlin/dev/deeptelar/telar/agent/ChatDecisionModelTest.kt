package dev.deeptelar.telar.agent

import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChatDecisionModelTest {
    private val teams = mapOf("refund" to "The customer wants money back", "technical" to null)

    private fun near(expected: Double, actual: Double) = assertEquals(expected, actual, absoluteTolerance = 1e-9)

    /** The questions of the prompt that [model] received, as the JSON object they were sent as. */
    private fun questionsSent(model: ScriptedModel): JsonObject {
        val prompt =
            model.requests
                .single()
                .messages
                .single()
                .text
        return Json.parseToJsonElement(prompt.substringAfter("Questions:\n")) as JsonObject
    }

    @Test
    fun `choose picks the option the chat model finds most likely`() =
        runTest {
            val chat = ScriptedModel(says("""{"decision": {"refund": 0.7, "technical": 0.3}}"""))

            val answer = ChatDecisionModel(chat).choose("I want my money back", "Which team?", teams)

            assertEquals("refund", answer.option)
            near(0.7, answer.probabilities.getValue("refund"))
            near(0.3, answer.probabilities.getValue("technical"))
            near(0.4, answer.confidence)
        }

    @Test
    fun `the prompt holds the state and the instructions and every option with its description`() =
        runTest {
            val chat = ScriptedModel(says("""{"decision": {"refund": 1}}"""))

            ChatDecisionModel(chat).choose("I want my money back", "Which team?", teams)

            val request = chat.requests.single()
            assertTrue(request.system!!.contains("JSON"))
            assertContains(request.messages.single().text, "State:\nI want my money back\n")
            assertEquals(
                buildJsonObject {
                    put(
                        "decision",
                        buildJsonObject {
                            put("instructions", "Which team?")
                            put(
                                "options",
                                buildJsonObject {
                                    put("refund", "The customer wants money back")
                                    put("technical", null as String?)
                                },
                            )
                        },
                    )
                },
                questionsSent(chat),
            )
        }

    @Test
    fun `probabilities that do not add up to 1 are scaled and a missing option counts as 0`() =
        runTest {
            val chat = ScriptedModel(says("""{"decision": {"refund": 3, "billing": 5}}"""))

            val answer = ChatDecisionModel(chat).choose("Money back", "Which team?", teams)

            assertEquals(mapOf("refund" to 1.0, "technical" to 0.0), answer.probabilities)
            near(1.0, answer.confidence)
        }

    @Test
    fun `isYes reads the probability of yes`() =
        runTest {
            val chat = ScriptedModel(says("""{"decision": {"yes": 0.2, "no": 0.6}}"""))

            val answer = ChatDecisionModel(chat).isYes("Where is my order?", "Is the customer angry?")

            near(0.25, answer.probability)
            assertEquals(false, answer.isYes)
        }

    @Test
    fun `score weighs the levels by their probabilities`() =
        runTest {
            val chat = ScriptedModel(says("""{"decision": {"0": 0.1, "1": 0.3, "2": 0.6}}"""))

            val answer = ChatDecisionModel(chat).score("This is unacceptable!", "How angry?", listOf("Calm", "Annoyed", "Furious"))

            near(1.5, answer.score)
            assertEquals(3, answer.probabilities.size)
            near(0.3, answer.confidence)
            assertEquals(
                buildJsonObject {
                    put("0", "Calm")
                    put("1", "Annoyed")
                    put("2", "Furious")
                },
                (questionsSent(chat).getValue("decision") as JsonObject).getValue("options"),
            )
        }

    @Test
    fun `several questions cost one call and keep their names and the usage`() =
        runTest {
            val reply = """{"team": {"refund": 0.9, "technical": 0.1}, "urgent": {"yes": 1, "no": 0}}"""
            val chat = ScriptedModel(ChatResponse(ChatMessage.Assistant(reply), TokenUsage(120, 30)))

            val response =
                ChatDecisionModel(chat).decide(
                    DecisionRequest(
                        "Refund me today",
                        mapOf("team" to Question.Choice("Which team?", teams), "urgent" to Question.YesNo("Is it urgent?", yes = "Today")),
                    ),
                )

            assertEquals(1, chat.requests.size)
            assertEquals("refund", (response.answers.getValue("team") as Answer.Choice).option)
            near(1.0, (response.answers.getValue("urgent") as Answer.YesNo).probability)
            assertEquals(TokenUsage(120, 30), response.usage)
        }

    @Test
    fun `a state that is not a text is sent as JSON`() =
        runTest {
            val chat = ScriptedModel(says("""{"decision": {"yes": 1}}"""))
            val state = buildJsonObject { put("total", 120) }

            ChatDecisionModel(chat).decide(DecisionRequest(state, mapOf("decision" to Question.YesNo("Large?"))))

            assertContains(
                chat.requests
                    .single()
                    .messages
                    .single()
                    .text,
                "State:\n{\"total\":120}",
            )
        }

    @Test
    fun `an answer around the JSON is ignored`() =
        runTest {
            val chat = ScriptedModel(says("Here is my decision:\n```json\n{\"decision\": {\"technical\": 0.8, \"refund\": 0.2}}\n```"))

            assertEquals("technical", ChatDecisionModel(chat).choose("It crashes", "Which team?", teams).option)
        }

    @Test
    fun `a decision edge routes with a chat model and sends an unsure decision to the fallback`() =
        runTest {
            fun router(reply: String) =
                StateGraph<String> {
                    val refund = node("refund") { "refund" }
                    val technical = node("technical") { "technical" }
                    val person = node("person") { "person" }
                    decisionEdge(
                        START,
                        ChatDecisionModel(ScriptedModel(says(reply))),
                        "Which team?",
                        routes = mapOf(refund to "Money back", technical to null),
                        minConfidence = 0.5,
                        fallback = person,
                    ) { it }
                }.compile()

            assertEquals("technical", router("""{"decision": {"refund": 0.1, "technical": 0.9}}""").invoke("It crashes").state)
            assertEquals("person", router("""{"decision": {"refund": 0.45, "technical": 0.55}}""").invoke("Hmm").state)
        }

    @Test
    fun `a failed chat call is a DecisionModelException with the cause`() =
        runTest {
            val failure = ChatModelException("API error 529: overloaded")

            val thrown =
                assertFailsWith<DecisionModelException> {
                    ChatDecisionModel(ChatModel { throw failure }).choose("Hello", "Which team?", teams)
                }

            assertSame(failure, thrown.cause)
            assertContains(thrown.message!!, "overloaded")
        }

    @Test
    fun `an answer cut off at the output limit fails`() =
        runTest {
            val chat = ScriptedModel(says("""{"decision": {"refund": 0.""").cutOff())

            val thrown = assertFailsWith<DecisionModelException> { ChatDecisionModel(chat).choose("Hello", "Which team?", teams) }

            assertContains(thrown.message!!, "output limit")
        }

    @Test
    fun `an answer that is not usable fails with what the model wrote`() =
        runTest {
            suspend fun failureFor(reply: String): String =
                assertFailsWith<DecisionModelException> {
                    ChatDecisionModel(ScriptedModel(says(reply))).choose("Hello", "Which team?", teams)
                }.message!!

            assertContains(failureFor("I think refund."), "I think refund.")
            assertContains(failureFor("{not json}"), "did not answer with a JSON object")
            assertContains(failureFor("""{"team": {"refund": 1}}"""), "did not answer the question 'decision'")
            assertContains(failureFor("""{"decision": {"refund": 0, "technical": 0}}"""), "no probability")
            assertContains(failureFor("""{"decision": {"billing": 1}}"""), "no probability")
            assertContains(failureFor("""{"decision": {"refund": -0.5, "technical": 1}}"""), "-0.5")
            assertContains(failureFor("""{"decision": {"refund": "high"}}"""), "\"high\"")
        }
}
