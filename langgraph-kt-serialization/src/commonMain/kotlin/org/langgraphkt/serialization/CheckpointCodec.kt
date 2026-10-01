package org.langgraphkt.serialization

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.langgraphkt.Checkpoint
import org.langgraphkt.CheckpointCorruptedException
import org.langgraphkt.Checkpointer
import org.langgraphkt.StateSerializer

/**
 * Converts a [Checkpoint] to and from a JSON string, so a [Checkpointer] for any storage only has
 * to read and write strings:
 *
 * ```kotlin
 * class PreferencesCheckpointer<State>(
 *     private val preferences: Preferences,
 *     private val codec: CheckpointCodec<State>,
 * ) : Checkpointer<State> {
 *     override suspend fun save(threadId: String, checkpoint: Checkpoint<State>) =
 *         preferences.put(threadId, codec.encode(checkpoint))
 *
 *     override suspend fun load(threadId: String): Checkpoint<State>? =
 *         preferences.get(threadId)?.let { codec.decode(threadId, it) }
 *
 *     override suspend fun delete(threadId: String) = preferences.remove(threadId)
 * }
 * ```
 *
 * The format is versioned and shared by every checkpointer built on this class, including
 * `FileCheckpointer`.
 *
 * @param serializer converts the graph state to and from a string.
 */
public class CheckpointCodec<State>(
    private val serializer: StateSerializer<State>,
) {
    /** Returns [checkpoint] as a JSON string. */
    public fun encode(checkpoint: Checkpoint<State>): String =
        format.encodeToString(
            SerializedCheckpoint(
                state = serializer.serialize(checkpoint.state),
                nextNodes = checkpoint.nextNodes,
                step = checkpoint.step,
                interruptedBefore = checkpoint.interruptedBefore,
            ),
        )

    /**
     * Reads a checkpoint written by [encode].
     *
     * @param threadId the thread the data belongs to; only used in the error.
     * @throws CheckpointCorruptedException if [data] is not a checkpoint, was written in a newer
     * format, or holds a state that [serializer] cannot read.
     */
    public fun decode(threadId: String, data: String): Checkpoint<State> {
        val envelope =
            try {
                format.decodeFromString<SerializedCheckpoint>(data)
            } catch (e: SerializationException) {
                throw CheckpointCorruptedException(threadId, "the stored checkpoint is not valid", e)
            }
        if (envelope.version > SerializedCheckpoint.FORMAT_VERSION) {
            throw CheckpointCorruptedException(threadId, "format version ${envelope.version} is newer than this library supports")
        }
        val state =
            try {
                serializer.deserialize(envelope.state)
            } catch (e: IllegalArgumentException) {
                // SerializationException is an IllegalArgumentException, as are most parsing failures.
                throw CheckpointCorruptedException(threadId, "the stored state does not match the state type", e)
            }
        return Checkpoint(state, envelope.nextNodes, envelope.step, envelope.interruptedBefore)
    }

    private companion object {
        val format =
            Json {
                ignoreUnknownKeys = true
                // Without this the version, which has a default, would be left out of the output.
                encodeDefaults = true
            }
    }
}

/** Creates a [CheckpointCodec] for the reified `@Serializable` [State] type. */
public inline fun <reified State> CheckpointCodec(json: Json = Json): CheckpointCodec<State> =
    CheckpointCodec(KotlinxStateSerializer<State>(json))

/** Envelope around the serialized state. [version] allows the format to evolve. */
@Serializable
internal data class SerializedCheckpoint(
    val version: Int = FORMAT_VERSION,
    val state: String,
    val nextNodes: List<String>,
    val step: Int = 0,
    val interruptedBefore: Boolean = false,
) {
    companion object {
        const val FORMAT_VERSION = 1
    }
}
