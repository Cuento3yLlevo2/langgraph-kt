package dev.deeptelar.telar.agent

import dev.deeptelar.telar.GraphValidationException
import dev.deeptelar.telar.NodeRef
import dev.deeptelar.telar.StateGraph

/**
 * Adds a conditional edge from [from] on which a [DecisionModel] picks the next node: a router that
 * understands language, at the speed and price of a decision model.
 *
 * ```kotlin
 * decisionEdge(
 *     from = read,
 *     model = jev,
 *     instructions = "Which team should handle this email?",
 *     routes = mapOf(
 *         refund to "The customer wants money back",
 *         technical to "Something does not work",
 *     ),
 *     // An email the model is not sure about goes to a person.
 *     minConfidence = 0.7,
 *     fallback = escalate,
 * ) { email -> email.body }
 * ```
 *
 * The model sees the names of the nodes in [routes] as its options, so name the nodes for what they
 * do. It picks one of them also when none fits, and can be sure of that pick, so give it a route
 * for what the others do not cover. The decision is not written into the state. When the state
 * should keep it, or the next step needs the probabilities, ask the model in a node with [choose]
 * and route with `conditionalEdge` on what the node stored.
 *
 * A failure of the model fails the run with an `EdgeConditionException`, which has the
 * [DecisionModelException] as its `cause`.
 *
 * @param routes the nodes the model may pick, each with a description of when it applies, or `null`
 * when the name of the node says enough. Use [NodeRef.END] for "nothing more to do".
 * @param minConfidence the confidence below which the edge goes to [fallback] instead of the node
 * the model picked, from 0 to 1.
 * @param fallback where an unsure decision goes. It may be one of [routes].
 * @param state reads what the model judges out of the state of the graph.
 * @throws GraphValidationException if [routes] is empty, [minConfidence] is not between 0 and 1, or
 * it is above 0 and there is no [fallback].
 */
public fun <State> StateGraph<State>.decisionEdge(
    from: NodeRef,
    model: DecisionModel,
    instructions: String,
    routes: Map<NodeRef, String?>,
    minConfidence: Double = 0.0,
    fallback: NodeRef? = null,
    state: suspend (State) -> String,
) {
    decisionEdge(from.name, model, instructions, routes, minConfidence, fallback, state)
}

/** Adds a decision edge from the node named [from], usually `START`. See the overload taking a [NodeRef]. */
public fun <State> StateGraph<State>.decisionEdge(
    from: String,
    model: DecisionModel,
    instructions: String,
    routes: Map<NodeRef, String?>,
    minConfidence: Double = 0.0,
    fallback: NodeRef? = null,
    state: suspend (State) -> String,
) {
    if (routes.isEmpty()) throw GraphValidationException("The decision edge from '$from' needs at least one route.")
    if (minConfidence !in 0.0..1.0) {
        throw GraphValidationException("minConfidence of the decision edge from '$from' is $minConfidence, and must be between 0 and 1.")
    }
    if (minConfidence > 0.0 && fallback == null) {
        throw GraphValidationException("The decision edge from '$from' has a minConfidence and needs a fallback for decisions below it.")
    }

    val nodes = routes.keys.associateBy { it.name }
    val options = routes.mapKeys { it.key.name }
    conditionalEdge(from, targets = routes.keys + listOfNotNull(fallback)) { current ->
        val answer = model.choose(state(current), instructions, options)
        if (fallback != null && answer.confidence < minConfidence) fallback else nodes.getValue(answer.option)
    }
}
