package dev.deeptelar.telar.agent

import dev.deeptelar.telar.EdgeConditionException
import dev.deeptelar.telar.GraphValidationException
import dev.deeptelar.telar.NodeRef
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private enum class Team(
    val handles: String?,
) {
    REFUND("The customer wants money back"),
    TECHNICAL(null),
}

private data class Email(
    val body: String,
    val handledBy: String = "",
)

class DecisionModelTest {
    private val asked = mutableListOf<DecisionRequest>()

    /** A model that gives [answer] to every question and records what it was asked. */
    private fun answering(answer: Answer): DecisionModel =
        DecisionModel { request ->
            asked += request
            DecisionResponse(request.questions.mapValues { answer })
        }

    private val teams = mapOf("refund" to "The customer wants money back", "technical" to null)

    @Test
    fun `choose asks one choice question about a text and returns its answer`() =
        runTest {
            val answer = Answer.Choice("refund", mapOf("refund" to 0.9, "technical" to 0.1), confidence = 0.8)

            assertEquals(answer, answering(answer).choose("I want my money back", "Which team?", teams))

            val request = asked.single()
            assertEquals(JsonPrimitive("I want my money back"), request.state)
            assertEquals(listOf<Question>(Question.Choice("Which team?", teams)), request.questions.values.toList())
        }

    @Test
    fun `choose with an enum offers its constants and reads the one that was picked`() =
        runTest {
            val answer = answering(Answer.Choice("TECHNICAL")).choose<Team>("It crashes", "Which team?") { it.handles }

            assertEquals(Team.TECHNICAL, answer.value<Team>())
            assertEquals(
                Question.Choice("Which team?", mapOf("REFUND" to "The customer wants money back", "TECHNICAL" to null)),
                asked
                    .single()
                    .questions.values
                    .single(),
            )
        }

    @Test
    fun `choose with an enum needs no descriptions`() =
        runTest {
            answering(Answer.Choice("REFUND")).choose<Team>("Money back", "Which team?")

            assertEquals(
                mapOf("REFUND" to null, "TECHNICAL" to null),
                (
                    asked
                        .single()
                        .questions.values
                        .single() as Question.Choice
                ).options,
            )
        }

    @Test
    fun `a choice that is not an option is an error`() =
        runTest {
            val failure =
                assertFailsWith<DecisionModelException> { answering(Answer.Choice("sales")).choose("Hello", "Which team?", teams) }

            assertEquals("The model chose 'sales', which is not one of the options [refund, technical].", failure.message)
        }

    @Test
    fun `isYes asks a yes or no question and says which is more likely`() =
        runTest {
            val yes = answering(Answer.YesNo(0.95)).isYes("Help! Now!", "Is this urgent?")

            assertEquals(0.95, yes.probability)
            assertTrue(yes.isYes)
            assertFalse(Answer.YesNo(0.5).isYes)
            assertFalse(Answer.YesNo(0.1).isYes)
            assertEquals(
                Question.YesNo("Is this urgent?"),
                asked
                    .single()
                    .questions.values
                    .single(),
            )
        }

    @Test
    fun `score asks for a rating on the levels`() =
        runTest {
            val answer = Answer.Score(1.05, listOf(0.0, 0.95, 0.05), confidence = 0.92)

            assertEquals(answer, answering(answer).score("This is the third time!", "How angry?", listOf("Calm", "Annoyed", "Furious")))
            assertEquals(
                Question.Score("How angry?", listOf("Calm", "Annoyed", "Furious")),
                asked
                    .single()
                    .questions.values
                    .single(),
            )
        }

    @Test
    fun `an answer of another kind and a missing answer are errors`() =
        runTest {
            val wrongKind = assertFailsWith<DecisionModelException> { answering(Answer.YesNo(1.0)).choose("Hello", "Which team?", teams) }
            val nothing =
                assertFailsWith<DecisionModelException> { DecisionModel { DecisionResponse(emptyMap()) }.isYes("Hello", "Urgent?") }

            assertEquals("The model answered YesNo to a Choice question.", wrongKind.message)
            assertEquals("The model answered nothing to a YesNo question.", nothing.message)
        }

    @Test
    fun `a request and its questions are checked when they are built`() {
        assertFailsWith<GraphValidationException> { DecisionRequest("Hello", emptyMap()) }
        assertFailsWith<GraphValidationException> { Question.Choice("Which team?", emptyMap()) }
        assertFailsWith<GraphValidationException> { Question.Score("How angry?", listOf("Calm")) }
    }

    @Test
    fun `an answer a test builds is a sure one`() {
        assertEquals(Answer.Choice("refund", mapOf("refund" to 1.0), confidence = 1.0), Answer.Choice("refund"))
        assertEquals(Answer.Score(2.0, emptyList(), confidence = 1.0), Answer.Score(2.0))
        assertIs<DecisionModelException>(DecisionModelException("Failed", IllegalStateException("cause"))).also {
            assertIs<IllegalStateException>(it.cause)
        }
    }

    /** A graph that lets [model] send an email to "refund" or "technical", or to "person" below [minConfidence]. */
    private fun router(model: DecisionModel, minConfidence: Double = 0.0) =
        StateGraph<Email> {
            val refund = node("refund") { it.copy(handledBy = "refund") }
            val technical = node("technical") { it.copy(handledBy = "technical") }
            val person = node("person") { it.copy(handledBy = "person") }

            decisionEdge(
                START,
                model,
                "Which team should handle this email?",
                routes = mapOf(refund to "The customer wants money back", technical to null),
                minConfidence = minConfidence,
                fallback = person,
            ) { it.body }
        }.compile()

    @Test
    fun `a decision edge runs the node the model picks`() =
        runTest {
            val graph = router(answering(Answer.Choice("technical", confidence = 0.4)))

            assertEquals("technical", graph.invoke(Email("It crashes")).state.handledBy)

            val request = asked.single()
            assertEquals(JsonPrimitive("It crashes"), request.state)
            assertEquals(
                Question.Choice(
                    "Which team should handle this email?",
                    mapOf(
                        "refund" to "The customer wants money back",
                        "technical" to null,
                    ),
                ),
                request.questions.values.single(),
            )
        }

    @Test
    fun `a decision below the confidence goes to the fallback`() =
        runTest {
            val unsure = router(answering(Answer.Choice("refund", confidence = 0.69)), minConfidence = 0.7)
            val sure = router(answering(Answer.Choice("refund", confidence = 0.7)), minConfidence = 0.7)

            assertEquals("person", unsure.invoke(Email("Hm")).state.handledBy)
            assertEquals("refund", sure.invoke(Email("Money back")).state.handledBy)
        }

    @Test
    fun `the routes and the fallback are the targets of the edge`() {
        val topology = router(answering(Answer.Choice("refund"))).topology

        assertEquals(listOf("refund", "technical", "person"), topology.successors(START))
    }

    @Test
    fun `a decision edge from a node can end the run`() =
        runTest {
            val graph =
                StateGraph<Email> {
                    val read = node("read") { it }
                    val reply = node("reply") { it.copy(handledBy = "reply") }

                    START then read
                    decisionEdge(
                        read,
                        answering(Answer.Choice(NodeRef.END.name)),
                        "Does it need a reply?",
                        mapOf(
                            reply to "It asks something",
                            NodeRef.END to "Spam",
                        ),
                    ) {
                        it.body
                    }
                }.compile()

            assertEquals("", graph.invoke(Email("Buy now")).state.handledBy)
        }

    @Test
    fun `a failure of the model fails the edge with the cause`() =
        runTest {
            val graph = router(DecisionModel { throw DecisionModelException("TypeSafe API error 429: slow down") })

            val failure = assertFailsWith<EdgeConditionException> { graph.invoke(Email("Hello")) }

            assertEquals(START, failure.from)
            assertEquals("TypeSafe API error 429: slow down", assertIs<DecisionModelException>(failure.cause).message)
        }

    @Test
    fun `a decision edge is checked when it is added`() {
        val model = answering(Answer.Choice("a"))

        fun edge(routes: (NodeRef) -> Map<NodeRef, String?>, minConfidence: Double = 0.0, fallback: Boolean = false) =
            StateGraph<Email> {
                val a = node("a") { it }
                decisionEdge(START, model, "Which?", routes(a), minConfidence, if (fallback) a else null) { it.body }
            }

        assertFailsWith<GraphValidationException> { edge({ emptyMap() }) }
        assertFailsWith<GraphValidationException> { edge({ mapOf(it to null) }, minConfidence = 1.5, fallback = true) }
        assertFailsWith<GraphValidationException> { edge({ mapOf(it to null) }, minConfidence = -0.1, fallback = true) }
        val noFallback = assertFailsWith<GraphValidationException> { edge({ mapOf(it to null) }, minConfidence = 0.7) }
        assertEquals(
            "The decision edge from '__START__' has a minConfidence and needs a fallback for decisions below it.",
            noFallback.message,
        )
    }
}
