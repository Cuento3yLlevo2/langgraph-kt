@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.deeptelar.telar.checkpoint.browser

import dev.deeptelar.telar.Checkpoint
import dev.deeptelar.telar.CheckpointCorruptedException
import dev.deeptelar.telar.END
import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.GraphResult
import dev.deeptelar.telar.GraphValidationException
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import dev.deeptelar.telar.StateSerializer
import dev.deeptelar.telar.serialization.KotlinxStateSerializer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Serializable
data class Ticket(
    val customer: String,
    val refund: Int = 0,
    val approved: Boolean = false,
    val reply: String = "",
)

/** Runs in a real browser, against its `localStorage`. */
class LocalStorageCheckpointerTest {
    private fun checkpointer(keyPrefix: String = "test.") = LocalStorageCheckpointer(KotlinxStateSerializer<Ticket>(), keyPrefix)

    private val paused = Checkpoint(Ticket("Ana", refund = 12), nextNodes = listOf("pay"), step = 1, interruptedBefore = true)

    @BeforeTest
    @AfterTest
    fun emptyTheStorage() = clear()

    @Test
    fun aCheckpointIsReadBackAsItWasSaved() =
        runTest {
            val checkpointer = checkpointer()

            checkpointer.save("ticket-42", paused)

            assertEquals(paused, checkpointer.load("ticket-42"))
        }

    @Test
    fun aThreadWithoutACheckpointLoadsNothing() =
        runTest {
            assertNull(checkpointer().load("ticket-42"))
        }

    @Test
    fun aCheckpointIsStillThereForANewCheckpointer() =
        runTest {
            checkpointer().save("ticket-42", paused)

            // A page that was reloaded creates its checkpointer again.
            assertEquals(paused, checkpointer().load("ticket-42"))
        }

    @Test
    fun aThreadKeepsOnlyItsLatestCheckpointUnlessAskedForMore() =
        runTest {
            val latestOnly = checkpointer()
            val lastTwo = LocalStorageCheckpointer(KotlinxStateSerializer<Ticket>(), "history.", maxHistory = 2)
            val steps = (0..3).map { Checkpoint(Ticket("Ana", refund = it), nextNodes = listOf("pay"), step = it) }
            assertEquals(emptyList(), lastTwo.history("ticket-42"))

            steps.forEach {
                latestOnly.save("ticket-42", it)
                lastTwo.save("ticket-42", it)
            }
            // A pause at the last step is saved again, in its place.
            lastTwo.save("ticket-42", steps.last().copy(interruptedBefore = true))

            assertEquals(listOf(steps.last()), latestOnly.history("ticket-42"))
            assertEquals(listOf(steps[2], steps.last().copy(interruptedBefore = true)), lastTwo.history("ticket-42"))
            assertEquals(steps.last().copy(interruptedBefore = true), lastTwo.load("ticket-42"))

            lastTwo.delete("ticket-42")
            assertEquals(emptyList(), lastTwo.history("ticket-42"))
            assertFailsWith<GraphValidationException> { LocalStorageCheckpointer(KotlinxStateSerializer<Ticket>(), maxHistory = 0) }
        }

    @Test
    fun aNewCheckpointReplacesTheOneBefore() =
        runTest {
            val checkpointer = checkpointer()
            val finished = Checkpoint(Ticket("Ana", refund = 12, approved = true, reply = "Paid."), nextNodes = emptyList(), step = 2)

            checkpointer.save("ticket-42", paused)
            checkpointer.save("ticket-42", finished)

            assertEquals(finished, checkpointer.load("ticket-42"))
            assertEquals(1, length())
        }

    @Test
    fun threadsAreKeptApart() =
        runTest {
            val checkpointer = checkpointer()
            val other = paused.copy(state = Ticket("Ben", refund = 5))

            checkpointer.save("ticket-42", paused)
            checkpointer.save("ticket-43", other)

            assertEquals(paused, checkpointer.load("ticket-42"))
            assertEquals(other, checkpointer.load("ticket-43"))
        }

    @Test
    fun deleteRemovesOnlyTheCheckpointOfItsThread() =
        runTest {
            val checkpointer = checkpointer()
            checkpointer.save("ticket-42", paused)
            checkpointer.save("ticket-43", paused)

            checkpointer.delete("ticket-42")
            // A thread that has no checkpoint is not an error.
            checkpointer.delete("ticket-99")

            assertNull(checkpointer.load("ticket-42"))
            assertNotNull(checkpointer.load("ticket-43"))
        }

    @Test
    fun theKeyIsThePrefixAndTheThreadId() =
        runTest {
            checkpointer(keyPrefix = "pizza.save.").save("ticket-42", paused)
            LocalStorageCheckpointer(KotlinxStateSerializer<Ticket>()).save("ticket-42", paused)

            assertNotNull(item("pizza.save.ticket-42"))
            assertNotNull(item("telar.checkpoint.ticket-42"))
            // Two graphs on one page do not read each other's runs when their prefixes differ.
            assertNull(checkpointer(keyPrefix = "other.").load("ticket-42"))
        }

    @Test
    fun anyThreadIdCanBeUsed() =
        runTest {
            val checkpointer = checkpointer()
            val threadId = "user 7 / ticket #42 ñ 🍕"

            checkpointer.save(threadId, paused)

            assertEquals(paused, checkpointer.load(threadId))
        }

    @Test
    fun anEntryThatIsNotACheckpointIsReportedAsCorrupted() =
        runTest {
            put("test.ticket-42", "not a checkpoint")

            val failure = assertFailsWith<CheckpointCorruptedException> { checkpointer().load("ticket-42") }

            assertEquals("ticket-42", failure.threadId)
        }

    @Test
    fun aFullStorageIsReportedAndKeepsTheCheckpointBefore() =
        runTest {
            val checkpointer = checkpointer()
            checkpointer.save("ticket-42", paused)
            fillTheStorage()
            // Less than 4,096 characters are free. This reply alone is longer.
            val tooLarge = paused.copy(state = Ticket("Ana", reply = "pizza ".repeat(1_000)))

            val failure = assertFailsWith<LocalStorageException> { checkpointer.save("ticket-42", tooLarge) }

            assertEquals("ticket-42", failure.threadId)
            assertTrue(
                failure.message!!.startsWith("Checkpoint of thread 'ticket-42': localStorage could not store the checkpoint (6"),
                failure.message,
            )
            assertNotNull(failure.cause)
            assertEquals(paused, checkpointer.load("ticket-42"))
        }

    @Test
    fun aPausedRunContinuesAfterThePageWasReloaded() =
        runTest {
            fun helpDesk() =
                StateGraph<Ticket> {
                    val prepare = node("prepare") { it.copy(refund = 12) }
                    val pay = node("pay") { it.copy(reply = if (it.approved) "We sent you ${it.refund} euros." else "No refund.") }

                    START then prepare then pay then END
                }.compile()

            fun config() = GraphConfig(threadId = "ticket-42", checkpointer = checkpointer(), interruptBefore = setOf("pay"))

            val before = helpDesk().invoke(Ticket("Ana"), config())
            assertIs<GraphResult.Interrupted<Ticket>>(before)

            // After a reload nothing of the first run is left in memory: a new graph, a new checkpointer.
            val waiting = helpDesk().lastResult(config())
            assertIs<GraphResult.Interrupted<Ticket>>(waiting)
            assertEquals(listOf("pay"), waiting.nextNodes)

            val after = helpDesk().resume(config()) { it.copy(approved = true) }
            assertIs<GraphResult.Completed<Ticket>>(after)
            assertEquals("We sent you 12 euros.", after.state.reply)
        }

    @Test
    fun aSerializerOfYourOwnCanBeUsed() =
        runTest {
            val names =
                object : StateSerializer<String> {
                    override fun serialize(state: String): String = state.reversed()

                    override fun deserialize(data: String): String = data.reversed()
                }
            val checkpointer = LocalStorageCheckpointer(names, keyPrefix = "test.")

            checkpointer.save("thread", Checkpoint("Ana", nextNodes = listOf("next")))

            assertEquals("Ana", checkpointer.load("thread")?.state)
            assertTrue(item("test.thread")!!.contains("anA"))
        }
}

private fun clear(): Unit = js("globalThis.localStorage.clear()")

private fun length(): Int = js("globalThis.localStorage.length")

private fun item(key: String): String? = js("globalThis.localStorage.getItem(key)")

private fun put(key: String, value: String): Unit = js("globalThis.localStorage.setItem(key, value)")

/**
 * Fills the storage of the page until less than 4,096 characters are free.
 *
 * The browser does the work, with pieces that get smaller. A text of several megabytes built in
 * Kotlin took longer than the two seconds a test has when the machine was busy.
 */
private fun fillTheStorage(): Unit =
    js(
        """
        (function () {
            var count = 0;
            [1048576, 65536, 4096].forEach(function (size) {
                try {
                    while (true) globalThis.localStorage.setItem('filler.' + count++, 'x'.repeat(size));
                } catch (full) {
                }
            });
        })()
        """,
    )
