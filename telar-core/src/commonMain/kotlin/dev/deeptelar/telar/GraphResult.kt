package dev.deeptelar.telar

/**
 * The outcome of [CompiledGraph.invoke] or [CompiledGraph.resume].
 *
 * ```kotlin
 * when (val result = graph.invoke(input, config)) {
 *     is GraphResult.Completed -> show(result.state)
 *     is GraphResult.Interrupted -> askForApproval(result.state, result.nextNodes)
 * }
 * ```
 */
public sealed interface GraphResult<out State> {
    /** The graph state when the run stopped. */
    public val state: State

    /** Every branch reached [END]. */
    public data class Completed<out State>(
        override val state: State,
    ) : GraphResult<State>

    /**
     * The run paused at an interrupt and was checkpointed. Call [CompiledGraph.resume] to continue
     * with [nextNodes]. [CompiledGraph.lastResult] also returns it for a run that stopped between two
     * steps without reaching an interrupt.
     */
    public data class Interrupted<out State>(
        override val state: State,
        val nextNodes: List<String>,
    ) : GraphResult<State>
}
