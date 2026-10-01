package org.langgraphkt.checkpoint.file

import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.Serializable
import org.langgraphkt.Checkpoint
import org.langgraphkt.serialization.KotlinxStateSerializer
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable
data class SerializableState(
    val count: Int = 0,
)

class FileCheckpointerTest {
    private val tempDir = Path(SystemTemporaryDirectory, "langgraph-kt-test-${Random.nextLong().toULong()}")

    private val serializer = KotlinxStateSerializer<SerializableState>()

    @AfterTest
    fun cleanUp() {
        deleteRecursively(tempDir)
    }

    @Test
    fun `file checkpointer saves and loads state successfully`() =
        runTest {
            val checkpointer = FileCheckpointer(tempDir, serializer)

            val checkpoint = Checkpoint(SerializableState(42), listOf("nodeA", "nodeB"), step = 3)
            checkpointer.save("thread-x", checkpoint)

            assertTrue(SystemFileSystem.exists(Path(tempDir, "thread-x.json")))

            val loaded = checkpointer.load("thread-x")
            requireNotNull(loaded)

            assertEquals(42, loaded.state.count)
            assertEquals(listOf("nodeA", "nodeB"), loaded.nextNodes)
            assertEquals(3, loaded.step)
        }

    private fun deleteRecursively(path: Path) {
        if (!SystemFileSystem.exists(path)) return
        if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
            SystemFileSystem.list(path).forEach(::deleteRecursively)
        }
        SystemFileSystem.delete(path)
    }
}
