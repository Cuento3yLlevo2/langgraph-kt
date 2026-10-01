package org.langgraphkt

/**
 * Defines how to merge state updates from parallel node execution.
 *
 * When multiple nodes execute in parallel (fan-out), they each produce a modified copy of the state.
 * The Reducer is responsible for taking the original state and the list of updated states,
 * and combining them into a single definitive state for the next step of execution.
 */
fun interface Reducer<State> {
    fun reduce(currentState: State, updates: List<State>): State
}
