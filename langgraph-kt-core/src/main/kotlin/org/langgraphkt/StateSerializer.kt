package org.langgraphkt

interface StateSerializer<State> {
    fun serialize(state: State): String
    fun deserialize(data: String): State
}
