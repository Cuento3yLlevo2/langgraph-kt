package org.langgraphkt

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable
data class SerializableState(val count: Int = 0)

class FileCheckpointerTest {

    @Test
    fun `file checkpointer saves and loads state successfully`() = runTest {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "checkpointer_test")
        tempDir.deleteRecursively()
        
        val serializer = object : StateSerializer<SerializableState> {
            override fun serialize(state: SerializableState) = Json.encodeToString(state)
            override fun deserialize(data: String) = Json.decodeFromString<SerializableState>(data)
        }
        
        val checkpointer = FileCheckpointer(tempDir, serializer)
        
        // Save
        val checkpoint = Checkpoint(SerializableState(42), listOf("nodeA", "nodeB"))
        checkpointer.save("thread-x", checkpoint)
        
        assertTrue(File(tempDir, "thread-x.json").exists())
        
        // Load
        val loaded = checkpointer.load("thread-x")
        requireNotNull(loaded)
        
        assertEquals(42, loaded.state.count)
        assertEquals(2, loaded.nextNodes.size)
        assertEquals("nodeA", loaded.nextNodes[0])
        
        tempDir.deleteRecursively()
    }
}
