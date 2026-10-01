package org.langgraphkt.checkpoint.file

import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import org.langgraphkt.Checkpoint
import org.langgraphkt.CheckpointCorruptedException
import org.langgraphkt.END
import org.langgraphkt.GraphConfig
import org.langgraphkt.GraphResult
import org.langgraphkt.START
import org.langgraphkt.StateGraph
import org.langgraphkt.serialization.KotlinxStateSerializer
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Serializable
data class SerializableState(
    val count: Int = 0,
)

class FileCheckpointerTest {
    private val tempDir = Path(SystemTemporaryDirectory, "langgraph-kt-test-${Random.nextLong().toULong()}")
    private val serializer = KotlinxStateSerializer<SerializableState>()

    private fun newCheckpointer() = FileCheckpointer(tempDir, serializer)

    private fun files(): List<String> = SystemFileSystem.list(tempDir).map { it.name }.sorted()

    @AfterTest
    fun cleanUp() {
        deleteRecursively(tempDir)
    }

    @Test
    fun `saves and loads a checkpoint`() =
        runTest {
            val checkpointer = newCheckpointer()
            val checkpoint = Checkpoint(SerializableState(42), listOf("nodeA", "nodeB"), step = 3, interruptedBefore = true)

            checkpointer.save("thread-x", checkpoint)

            assertEquals(listOf("thread-x.json"), files())
            assertEquals(checkpoint, checkpointer.load("thread-x"))
        }

    @Test
    fun `load returns null for an unknown thread`() =
        runTest {
            assertNull(newCheckpointer().load("nobody"))
        }

    @Test
    fun `save replaces the previous checkpoint and leaves no temporary file`() =
        runTest {
            val checkpointer = newCheckpointer()

            checkpointer.save("t", Checkpoint(SerializableState(1), listOf("a"), step = 1))
            checkpointer.save("t", Checkpoint(SerializableState(2), emptyList(), step = 2))

            assertEquals(Checkpoint(SerializableState(2), emptyList(), step = 2), checkpointer.load("t"))
            assertEquals(listOf("t.json"), files())
        }

    @Test
    fun `delete removes the checkpoint and ignores unknown threads`() =
        runTest {
            val checkpointer = newCheckpointer()
            checkpointer.save("t", Checkpoint(SerializableState(1), emptyList()))

            checkpointer.delete("t")
            checkpointer.delete("never-existed")

            assertNull(checkpointer.load("t"))
            assertTrue(files().isEmpty())
        }

    @Test
    fun `thread ids that look alike are stored separately`() =
        runTest {
            val checkpointer = newCheckpointer()
            val ids = listOf("a/b", "a_b", "a b", "A_B", "../a_b", "ä_b")

            ids.forEachIndexed { index, id -> checkpointer.save(id, Checkpoint(SerializableState(index), emptyList())) }

            ids.forEachIndexed { index, id -> assertEquals(index, checkpointer.load(id)?.state?.count, "thread id '$id'") }
            assertEquals(ids.size, files().size)
        }

    @Test
    fun `file names never escape the directory`() {
        assertEquals("%2E%2E%2Fetc%2Fpasswd", encodeFileName("../etc/passwd"))
        assertEquals("user-42_chat", encodeFileName("user-42_chat"))
        assertEquals("%41bc", encodeFileName("Abc"))
        assertEquals("%C3%A4", encodeFileName("ä"))
    }

    @Test
    fun `a damaged file is reported as CheckpointCorruptedException`() =
        runTest {
            val checkpointer = newCheckpointer()
            SystemFileSystem.sink(Path(tempDir, "broken.json")).buffered().use { it.writeString("{ not json") }

            assertEquals("broken", assertFailsWith<CheckpointCorruptedException> { checkpointer.load("broken") }.threadId)
        }

    @Test
    fun `a file from a newer format version is rejected`() =
        runTest {
            val checkpointer = newCheckpointer()
            val json = """{"version":99,"state":"{}","nextNodes":[]}"""
            SystemFileSystem.sink(Path(tempDir, "future.json")).buffered().use { it.writeString(json) }

            assertFailsWith<CheckpointCorruptedException> { checkpointer.load("future") }
        }

    @Test
    fun `a paused graph resumes from disk with a new checkpointer instance`() =
        runTest {
            val app =
                StateGraph<SerializableState> {
                    node("draft") { it.copy(count = it.count + 1) }
                    node("publish") { it.copy(count = it.count + 10) }
                    edge(START, "draft")
                    edge("draft", "publish")
                    edge("publish", END)
                }.compile()

            val firstProcess = GraphConfig(threadId = "doc-1", checkpointer = newCheckpointer(), interruptBefore = setOf("publish"))
            assertEquals(GraphResult.Interrupted(SerializableState(1), listOf("publish")), app.invoke(SerializableState(), firstProcess))

            // A new instance reading the same directory stands in for a restarted process.
            val secondProcess = firstProcess.copy(checkpointer = newCheckpointer())
            assertEquals(GraphResult.Completed(SerializableState(11)), app.resume(secondProcess))
        }

    private fun deleteRecursively(path: Path) {
        if (!SystemFileSystem.exists(path)) return
        if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
            SystemFileSystem.list(path).forEach(::deleteRecursively)
        }
        SystemFileSystem.delete(path)
    }
}
