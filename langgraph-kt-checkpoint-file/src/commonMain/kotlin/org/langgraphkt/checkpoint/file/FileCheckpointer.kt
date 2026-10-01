package org.langgraphkt.checkpoint.file

import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.langgraphkt.Checkpoint
import org.langgraphkt.Checkpointer
import org.langgraphkt.StateSerializer

@Serializable
internal data class SerializedCheckpoint(
    val stateJson: String,
    val nextNodes: List<String>,
)

/**
 * A persistent checkpointer that saves graph state as one JSON file per thread inside [directory].
 *
 * Works on every target that has a file system: JVM/Android, Apple, Linux and Windows native, and
 * JS/Wasm running on Node.js. It is not available in browsers.
 */
public class FileCheckpointer<State>(
    private val directory: Path,
    private val serializer: StateSerializer<State>,
    private val fileSystem: FileSystem = SystemFileSystem,
) : Checkpointer<State> {
    init {
        fileSystem.createDirectories(directory)
    }

    private fun getFile(threadId: String): Path {
        val safeName = threadId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return Path(directory, "$safeName.json")
    }

    override suspend fun save(threadId: String, checkpoint: Checkpoint<State>) {
        withContext(ioDispatcher) {
            val stateJson = serializer.serialize(checkpoint.state)
            val serialized = SerializedCheckpoint(stateJson, checkpoint.nextNodes)
            val json = Json.encodeToString(serialized)
            fileSystem.sink(getFile(threadId)).buffered().use { it.writeString(json) }
        }
    }

    override suspend fun load(threadId: String): Checkpoint<State>? =
        withContext(ioDispatcher) {
            val file = getFile(threadId)
            if (!fileSystem.exists(file)) return@withContext null

            val json = fileSystem.source(file).buffered().use { it.readString() }
            val serialized = Json.decodeFromString<SerializedCheckpoint>(json)
            val state = serializer.deserialize(serialized.stateJson)

            Checkpoint(state, serialized.nextNodes)
        }
}
