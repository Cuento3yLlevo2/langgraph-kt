package org.langgraphkt.serialization

import org.langgraphkt.Checkpoint
import org.langgraphkt.CheckpointCorruptedException
import org.langgraphkt.StateSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CheckpointCodecTest {
    private val codec = CheckpointCodec<ChatState>()

    @Test
    fun `round-trips every field of a checkpoint`() {
        val checkpoint = Checkpoint(ChatState(listOf("hi"), turn = 1), listOf("a", "b"), step = 3, interruptedBefore = true)

        assertEquals(checkpoint, codec.decode("t", codec.encode(checkpoint)))
    }

    @Test
    fun `writes the format version`() {
        assertTrue(codec.encode(Checkpoint(ChatState(), emptyList())).startsWith("""{"version":1,"""))
    }

    @Test
    fun `reads a checkpoint that lacks the optional fields`() {
        val decoded = codec.decode("t", """{"state":"{\"turn\":4}","nextNodes":["a"]}""")

        assertEquals(Checkpoint(ChatState(turn = 4), listOf("a"), step = 0, interruptedBefore = false), decoded)
    }

    @Test
    fun `data that is not a checkpoint is reported with the thread id`() {
        val error = assertFailsWith<CheckpointCorruptedException> { codec.decode("thread-7", "not json") }

        assertEquals("thread-7", error.threadId)
    }

    @Test
    fun `a newer format version is rejected`() {
        assertFailsWith<CheckpointCorruptedException> { codec.decode("t", """{"version":99,"state":"{}","nextNodes":[]}""") }
    }

    @Test
    fun `a state the serializer cannot read is reported as corrupted`() {
        val error = assertFailsWith<CheckpointCorruptedException> { codec.decode("t", """{"state":"{\"turn\":\"x\"}","nextNodes":[]}""") }

        assertIs<IllegalArgumentException>(error.cause)
    }

    @Test
    fun `works with a StateSerializer that does not use kotlinx serialization`() {
        val numbers =
            CheckpointCodec(
                object : StateSerializer<Int> {
                    override fun serialize(state: Int): String = state.toString()

                    override fun deserialize(data: String): Int = data.toInt()
                },
            )

        assertEquals(Checkpoint(42, listOf("a")), numbers.decode("t", numbers.encode(Checkpoint(42, listOf("a")))))
        assertFailsWith<CheckpointCorruptedException> { numbers.decode("t", """{"state":"forty-two","nextNodes":[]}""") }
    }
}
