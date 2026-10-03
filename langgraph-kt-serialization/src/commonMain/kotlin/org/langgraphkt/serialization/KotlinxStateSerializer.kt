package org.langgraphkt.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.langgraphkt.StateSerializer

/**
 * A [StateSerializer] backed by kotlinx.serialization, for states annotated with `@Serializable`.
 *
 * ```kotlin
 * @Serializable
 * data class AgentState(val messages: List<String> = emptyList())
 *
 * val checkpointer = FileCheckpointer(Path("checkpoints"), KotlinxStateSerializer<AgentState>())
 * ```
 *
 * @param serializer the kotlinx.serialization serializer for [State].
 * @param json the [Json] instance used for encoding and decoding.
 */
public class KotlinxStateSerializer<State>(
    private val serializer: KSerializer<State>,
    private val json: Json = Json,
) : StateSerializer<State> {
    override fun serialize(state: State): String = json.encodeToString(serializer, state)

    override fun deserialize(data: String): State = json.decodeFromString(serializer, data)
}

/** Creates a [KotlinxStateSerializer] for the reified `@Serializable` [State] type. */
public inline fun <reified State> KotlinxStateSerializer(json: Json = Json): KotlinxStateSerializer<State> =
    KotlinxStateSerializer(serializer<State>(), json)
