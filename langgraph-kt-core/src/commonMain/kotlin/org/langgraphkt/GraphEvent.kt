package org.langgraphkt

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map

/**
 * A progress event emitted by [CompiledGraph.stream] and [CompiledGraph.streamResume].
 *
 * A stream emits one [StepCompleted] per executed step and always ends with exactly one
 * [Completed] or [Interrupted].
 */
public sealed interface GraphEvent<out State> {
    /** The graph state at the time of the event. */
    public val state: State

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
