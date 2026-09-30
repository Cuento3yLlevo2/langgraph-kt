package org.langgraphkt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class SerializedCheckpoint(
    val stateJson: String,
    val nextNodes: List<String>
)

/**
 * A persistent checkpointer that saves graph state to local files.
 * Perfect for JVM/Backend offline persistence.
 */
class FileCheckpointer<State>(
    private val directory: File,
    private val serializer: StateSerializer<State>
) : Checkpointer<State> {

    init {
        if (!directory.exists()) {
            directory.mkdirs()
        }
    }

    private fun getFile(threadId: String): File {
        val safeName = threadId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(directory, "$safeName.json")
    }

    override suspend fun save(threadId: String, checkpoint: Checkpoint<State>) {
        withContext(Dispatchers.IO) {
            val stateJson = serializer.serialize(checkpoint.state)
            val serialized = SerializedCheckpoint(stateJson, checkpoint.nextNodes)
            val json = Json.encodeToString(serialized)
            getFile(threadId).writeText(json)
        }
    }

    override suspend fun load(threadId: String): Checkpoint<State>? {
        return withContext(Dispatchers.IO) {
            val file = getFile(threadId)
            if (!file.exists()) return@withContext null

            val json = file.readText()
            val serialized = Json.decodeFromString<SerializedCheckpoint>(json)
            val state = serializer.deserialize(serialized.stateJson)
            
            Checkpoint(state, serialized.nextNodes)
        }
    }
}
