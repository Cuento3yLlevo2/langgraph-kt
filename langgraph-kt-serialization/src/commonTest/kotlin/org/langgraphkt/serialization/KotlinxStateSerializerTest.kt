package org.langgraphkt.serialization

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

@Serializable
data class ChatState(
    val messages: List<String> = emptyList(),
    val turn: Int = 0,
)

class KotlinxStateSerializerTest {
    @Test
    fun `round-trips a serializable state`() {
        val serializer = KotlinxStateSerializer<ChatState>()
        val state = ChatState(messages = listOf("hi", "hello"), turn = 2)

        assertEquals(state, serializer.deserialize(serializer.serialize(state)))
    }

    @Test
    fun `uses the provided Json configuration`() {
        val serializer = KotlinxStateSerializer<ChatState>(Json { encodeDefaults = true })

        assertEquals("""{"messages":[],"turn":0}""", serializer.serialize(ChatState()))
    }
}
