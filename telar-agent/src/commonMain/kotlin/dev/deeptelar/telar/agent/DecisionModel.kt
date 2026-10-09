package dev.deeptelar.telar.agent

import dev.deeptelar.telar.GraphValidationException
import dev.deeptelar.telar.TelarException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * A model that decides instead of writing: it picks one of the options you give it, answers yes or
 * no, or rates something on a scale, and says how sure it is. Such a model is fast and cheap, and
 * its answer has a fixed type, which makes it a good fit for the places where a graph has to
 * decide: which node comes next, whether an answer is good enough, whether a person has to look.
 *
 * Use [ChatDecisionModel] to decide with a chat model you already have, an implementation from an
 * integration module (`telar-typesafe` for Jev), or write one. In a test, a lambda is enough:
 *
 * ```kotlin
 * val model = DecisionModel { request ->
 *     DecisionResponse(request.questions.mapValues { Answer.YesNo(1.0) })
 * }
 * ```
 *
 * One request can ask several questions about the same [DecisionRequest.state]. For a single
 * question, [choose], [isYes] and [score] are shorter.
 */
public fun interface DecisionModel {
    /**
     * Returns an answer for each question of [request], under the name the question has there.
     *
     * @throws DecisionModelException when the call fails.
     */
    public suspend fun decide(request: DecisionRequest): DecisionResponse
}

/**
 * What a [DecisionModel] is asked.
 *
 * @property state what the model judges: a text as a [JsonPrimitive], or structured data such as a
 * conversation or a record.
 * @property questions the questions about [state], each under a name of your choice. Its answer
 * comes back under the same name.
 * @throws GraphValidationException if there is no question.
 */
public data class DecisionRequest(
    val state: JsonElement,
    val questions: Map<String, Question>,
) {
    /** Asks [questions] about the text [state]. */
    public constructor(state: String, questions: Map<String, Question>) : this(JsonPrimitive(state), questions)

    init {
        if (questions.isEmpty()) throw GraphValidationException("A DecisionRequest needs at least one question.")
    }
}

/** A question to a [DecisionModel]. Its kind decides the kind of [Answer]. */
public sealed interface Question {
    /** What the model should decide. */
    public val instructions: String

    /**
     * Pick one of [options]. The answer is an [Answer.Choice].
     *
     * @property options the options by name, each with a description of when it applies, or `null`
     * when the name says enough.
     * @throws GraphValidationException if there is no option.
     */
    public data class Choice(
        override val instructions: String,
        val options: Map<String, String?>,
    ) : Question {
        init {
            if (options.isEmpty()) throw GraphValidationException("A Choice needs at least one option.")
        }
    }

    /**
     * Answer yes or no. The answer is an [Answer.YesNo].
     *
     * @property yes what a yes means, when the question alone does not say it.
     * @property no what a no means.
     */
    public data class YesNo(
        override val instructions: String,
        val yes: String? = null,
        val no: String? = null,
    ) : Question

    /**
     * Rate the state on a scale. The answer is an [Answer.Score].
     *
     * @property levels a description of each level, from the lowest to the highest.
     * @throws GraphValidationException if there are fewer than two levels.
     */
    public data class Score(
        override val instructions: String,
        val levels: List<String>,
    ) : Question {
        init {
            if (levels.size < 2) throw GraphValidationException("A Score needs at least two levels.")
        }
    }
}

/** What a [DecisionModel] answered to one [Question]. */
public sealed interface Answer {
    /**
     * The model picked [option].
     *
     * @property probabilities how likely the model finds each option. They add up to 1.
     * @property confidence how clearly [option] won, from 0 (every option is as likely) to 1. Send
     * an answer with a low confidence to a person or to a larger model.
     */
    public data class Choice(
        val option: String,
        val probabilities: Map<String, Double> = mapOf(option to 1.0),
        val confidence: Double = 1.0,
    ) : Answer

    /**
     * How likely the answer is yes: [probability] is 0 for a certain no and 1 for a certain yes.
     */
    public data class YesNo(
        val probability: Double,
    ) : Answer {
        /** `true` when yes is more likely than no. Compare [probability] yourself for a stricter line. */
        public val isYes: Boolean get() = probability > EVEN
    }

    /**
     * The rating of the state. Levels are counted from 0, the lowest.
     *
     * @property score the rating: the levels weighted by their probabilities, so it can lie between
     * two levels.
     * @property probabilities how likely the model finds each level, in the order of the levels.
     * @property confidence how clearly one level won, from 0 to 1.
     */
    public data class Score(
        val score: Double,
        val probabilities: List<Double> = emptyList(),
        val confidence: Double = 1.0,
    ) : Answer

    private companion object {
        const val EVEN = 0.5
    }
}

/**
 * What a [DecisionModel] answered.
 *
 * @property answers the answer to each question, under the name the question has in the request.
 * @property usage the tokens the call used, when the provider reports them.
 */
public data class DecisionResponse(
    val answers: Map<String, Answer>,
    val usage: TokenUsage? = null,
)

/**
 * A call to a [DecisionModel] failed: the provider could not be reached, it returned an error, or
 * its answer does not fit the question.
 */
public class DecisionModelException(
    message: String,
    cause: Throwable? = null,
) : TelarException(message, cause)

/**
 * Asks which of [options] fits [state], for a node or an edge with one decision to make.
 *
 * ```kotlin
 * val classify = node("classify", work = { email ->
 *     model.choose(
 *         email.body,
 *         "Which team should handle this email?",
 *         mapOf("refund" to "The customer wants money back", "technical" to "Something does not work"),
 *     )
 * }) { email, answer -> email.copy(team = answer.option, sure = answer.confidence) }
 * ```
 *
 * @param options the options by name, each with a description of when it applies, or `null`.
 * @throws DecisionModelException when the call fails, or the model picks something that is not an option.
 */
public suspend fun DecisionModel.choose(state: String, instructions: String, options: Map<String, String?>): Answer.Choice {
    val answer = ask<Answer.Choice>(state, Question.Choice(instructions, options))
    if (answer.option !in options) {
        throw DecisionModelException("The model chose '${answer.option}', which is not one of the options ${options.keys}.")
    }
    return answer
}

/**
 * Asks which constant of the enum [Option] fits [state]. The model sees the names of the constants,
 * and for each one the text that [describe] returns. Read the constant from the answer with [value].
 *
 * ```kotlin
 * enum class Team(val handles: String) { REFUND("The customer wants money back"), TECHNICAL("Something does not work") }
 *
 * val team: Team = model.choose<Team>(email.body, "Which team should handle this email?") { it.handles }.value()
 * ```
 *
 * @throws DecisionModelException when the call fails, or the model picks something that is not a constant.
 */
public suspend inline fun <reified Option : Enum<Option>> DecisionModel.choose(
    state: String,
    instructions: String,
    describe: (Option) -> String? = { null },
): Answer.Choice = choose(state, instructions, enumValues<Option>().associate { it.name to describe(it) })

/** The constant of [Option] that this answer picked, after a [choose] with that enum. */
public inline fun <reified Option : Enum<Option>> Answer.Choice.value(): Option = enumValueOf(option)

/**
 * Asks a yes/no question about [state]. Read [Answer.YesNo.isYes], or compare
 * [Answer.YesNo.probability] with a line of your own.
 *
 * ```kotlin
 * if (model.isYes(reply, "Does this reply promise a refund?").probability > 0.9) ...
 * ```
 *
 * @throws DecisionModelException when the call fails.
 */
public suspend fun DecisionModel.isYes(state: String, instructions: String): Answer.YesNo = ask(state, Question.YesNo(instructions))

/**
 * Asks for a rating of [state] on the scale of [levels], described from the lowest to the highest.
 *
 * ```kotlin
 * val anger = model.score(email.body, "How angry is the customer?", listOf("Calm", "Annoyed", "Furious")).score
 * ```
 *
 * @throws DecisionModelException when the call fails.
 */
public suspend fun DecisionModel.score(state: String, instructions: String, levels: List<String>): Answer.Score =
    ask(state, Question.Score(instructions, levels))

/** Asks [question] alone and returns its answer, which has to be of the kind of the question. */
private suspend inline fun <reified Kind : Answer> DecisionModel.ask(state: String, question: Question): Kind {
    val answer = decide(DecisionRequest(state, mapOf(ONLY to question))).answers[ONLY]
    return answer as? Kind
        ?: throw DecisionModelException(
            "The model answered ${answer?.let { it::class.simpleName } ?: "nothing"} to a ${question::class.simpleName} question.",
        )
}

/** The name of the question in a request with one question. */
private const val ONLY = "decision"
