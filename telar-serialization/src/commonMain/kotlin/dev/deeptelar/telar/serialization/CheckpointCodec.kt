package dev.deeptelar.telar.serialization

import dev.deeptelar.telar.Checkpoint
import dev.deeptelar.telar.CheckpointCorruptedException
import dev.deeptelar.telar.Checkpointer
import dev.deeptelar.telar.StateSerializer
import dev.deeptelar.telar.SubgraphPosition
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

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
 * `FileCheckpointer`. A checkpoint of a run that paused inside a subgraph is written as version 2,
 * which a library before subgraphs refuses to read. Every other checkpoint is still version 1.
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
                version = if (checkpoint.subgraphs.isEmpty()) 1 else SerializedCheckpoint.FORMAT_VERSION,
                state = serializer.serialize(checkpoint.state),
                nextNodes = checkpoint.nextNodes,
                step = checkpoint.step,
                interruptedBefore = checkpoint.interruptedBefore,
                subgraphs = checkpoint.subgraphs.mapValues { it.value.serialized() },
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
        return Checkpoint(
            state,
            envelope.nextNodes,
            envelope.step,
            envelope.interruptedBefore,
            envelope.subgraphs.mapValues {
                it.value.position()
            },
        )
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

/**
 * Envelope around the serialized state. [version] allows the format to evolve.
 *
 * @property subgraphs left out when it is empty, so that a checkpoint without it reads as before.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class SerializedCheckpoint(
    val version: Int = FORMAT_VERSION,
    val state: String,
    val nextNodes: List<String>,
    val step: Int = 0,
    val interruptedBefore: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val subgraphs: Map<String, SerializedPosition> = emptyMap(),
) {
    companion object {
        /** Version 2 added [subgraphs]. */
        const val FORMAT_VERSION = 2
    }
}

/** A [SubgraphPosition] in the envelope. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class SerializedPosition(
    val nextNodes: List<String>,
    val step: Int = 0,
    val interruptedBefore: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val subgraphs: Map<String, SerializedPosition> = emptyMap(),
)

private fun SubgraphPosition.serialized(): SerializedPosition =
    SerializedPosition(nextNodes, step, interruptedBefore, subgraphs.mapValues { it.value.serialized() })

private fun SerializedPosition.position(): SubgraphPosition =
    SubgraphPosition(nextNodes, step, interruptedBefore, subgraphs.mapValues { it.value.position() })
