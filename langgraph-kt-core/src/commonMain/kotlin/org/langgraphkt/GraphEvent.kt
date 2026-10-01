package org.langgraphkt

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map

/**
 * A progress event emitted by [CompiledGraph.stream] and [CompiledGraph.streamResume].
 *
 * For every executed step a stream emits a [NodeStarted] for each node of the step, a
 * [NodeCompleted] as each of them finishes, and then one [StepCompleted]. It always ends with
 * exactly one [Completed] or [Interrupted].
 */
public sealed interface GraphEvent<out State> {
    /** The graph state at the time of the event. */
    public val state: State

    /**
     * [node] is about to run in [step]. [state] is the state the node receives. When a step runs
     * several nodes in parallel, all of their [NodeStarted] events come before the first
     * [NodeCompleted].
     */
    public data class NodeStarted<out State>(
        val step: Int,
        val node: String,
        override val state: State,
    ) : GraphEvent<State>

    /**
     * [node] finished in [step]. [state] is what the node returned. In a step with several nodes
     * this is the node's own result, before the [Reducer] merges it with the others; the merged
     * state arrives with [StepCompleted].
     */
    public data class NodeCompleted<out State>(
        val step: Int,
        val node: String,
        override val state: State,
    ) : GraphEvent<State>

    /**
     * A step finished: [nodes] ran (in parallel when there are several) and produced [state].
     *
     * @property step the thread's step number, starting at 1 and continuing across resumes.
     */
    public data class StepCompleted<out State>(
        val step: Int,
        val nodes: List<String>,
        override val state: State,
    ) : GraphEvent<State>

    /** The run paused at an interrupt. [nextNodes] run when the thread is resumed. */
    public data class Interrupted<out State>(
        override val state: State,
        val nextNodes: List<String>,
    ) : GraphEvent<State>

    /** Every branch reached [END]. */
    public data class Completed<out State>(
        override val state: State,
    ) : GraphEvent<State>
}

/**
 * Maps a stream of events to the state after each step, which is convenient for UI code that only
 * renders the latest state (for example `collectAsState` in Compose).
 */
public fun <State> Flow<GraphEvent<State>>.states(): Flow<State> = filterIsInstance<GraphEvent.StepCompleted<State>>().map { it.state }
