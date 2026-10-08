package dev.deeptelar.telar

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** [approved] is what a person answers to the node "review". */
private data class Draft(
    val text: String = "",
    val approved: Boolean? = null,
    val sent: String = "",
)

class HistoryTest {
    /** Writes, lets a person review, and sends or shelves. */
    private val app =
        StateGraph<Draft> {
            val write = node("write") { it.copy(text = it.text + "!") }
            val review = node("review") { if (it.approved == null) interrupt(it) else it }
            val send = node("send") { it.copy(sent = "sent ${it.text}") }
            val shelve = node("shelve") { it.copy(sent = "shelved") }

            START then write then review
            conditionalEdge(review, targets = setOf(send, shelve)) { if (it.approved == true) send else shelve }
        }.compile()

    private val checkpointer = MemoryCheckpointer<Draft>()
    private val config = GraphConfig(threadId = "t", checkpointer = checkpointer)

    @Test
    fun `a thread has the checkpoint of its start and of every step`() =
        runTest {
            val counter =
                StateGraph<TestState> {
                    val a = node("a") { it.copy(count = it.count + 1) }
                    val b = node("b") { it.copy(count = it.count + 10) }
                    START then a then b then END
                }.compile()
            val config = GraphConfig(checkpointer = MemoryCheckpointer<TestState>())

            counter.invoke(TestState(0), config)

            assertEquals(
                listOf(
                    Checkpoint(TestState(0), listOf("a"), step = 0),
                    Checkpoint(TestState(1), listOf("b"), step = 1),
                    Checkpoint(TestState(11), emptyList(), step = 2),
                ),
                counter.history(config),
            )
        }

    @Test
    fun `a pause is the checkpoint of its step and not one more`() =
        runTest {
            app.invoke(Draft("Hi"), config)

            assertEquals(
                listOf(
                    Checkpoint(Draft("Hi"), listOf("write"), step = 0),
                    Checkpoint(Draft("Hi!"), listOf("review"), step = 1, interruptedBefore = true),
                ),
                app.history(config),
            )

            // Nobody answered, so the node asks again, at the same place.
            app.resume(config)
            assertEquals(listOf(0, 1), app.history(config).map { it.step })

            app.resume(config) { it.copy(approved = true) }
            assertEquals(listOf(0, 1, 2, 3), app.history(config).map { it.step })
            assertEquals(listOf("send"), app.history(config)[2].nextNodes)
        }

    @Test
    fun `a pause before a node is the checkpoint of its step too`() =
        runTest {
            val pausing = config.copy(interruptBefore = setOf("write"))

            app.invoke(Draft("Hi"), pausing)

            assertEquals(listOf(Checkpoint(Draft("Hi"), listOf("write"), step = 0, interruptedBefore = true)), app.history(pausing))
        }

    @Test
    fun `invoke starts the history of a thread again`() =
        runTest {
            app.invoke(Draft("Hi"), config)
            app.resume(config) { it.copy(approved = true) }

            app.invoke(Draft("Again"), config)

            assertEquals(listOf(Draft("Again"), Draft("Again!")), app.history(config).map { it.state })
        }

    @Test
    fun `a thread without checkpoints has an empty history and history needs a checkpointer`() =
        runTest {
            assertEquals(emptyList(), app.history(config))
            assertFailsWith<GraphValidationException> { app.history(GraphConfig()) }
        }

    @Test
    fun `fork continues from an earlier checkpoint on a new thread and leaves the original`() =
        runTest {
            app.invoke(Draft("Hi"), config)
            app.resume(config) { it.copy(approved = true) }
            val original = app.history(config)
            val atReview = original.first { it.nextNodes == listOf("review") }

            val forked = app.fork(atReview, config.copy(threadId = "retry")) { it.copy(approved = false) }

            assertEquals(GraphResult.Completed(Draft("Hi!", approved = false, sent = "shelved")), forked)
            assertEquals(original, app.history(config))
            assertEquals("sent Hi!", app.lastResult(config)?.state?.sent)
            assertEquals(
                listOf(
                    Checkpoint(Draft("Hi!", approved = false), listOf("review"), step = 1, interruptedBefore = true),
                    Checkpoint(Draft("Hi!", approved = false), listOf("shelve"), step = 2),
                    Checkpoint(Draft("Hi!", approved = false, sent = "shelved"), emptyList(), step = 3),
                ),
                app.history(config.copy(threadId = "retry")),
            )
        }

    @Test
    fun `fork from the start runs the whole graph again with another input`() =
        runTest {
            app.invoke(Draft("Hi"), config)
            val start = app.history(config).first()

            val forked = app.fork(start, config.copy(threadId = "other")) { it.copy(text = "Hello", approved = true) }

            assertEquals(GraphResult.Completed(Draft("Hello!", approved = true, sent = "sent Hello!")), forked)
        }

    @Test
    fun `a fork can pause and is resumed like any thread`() =
        runTest {
            app.invoke(Draft("Hi"), config)
            val retry = config.copy(threadId = "retry")

            val paused = app.fork(app.history(config).first(), retry)

            assertEquals(GraphResult.Interrupted(Draft("Hi!"), listOf("review")), paused)
            assertEquals("sent Hi!", app.resume(retry) { it.copy(approved = true) }.state.sent)
        }

    @Test
    fun `streamFork has the events of the steps it runs`() =
        runTest {
            app.invoke(Draft("Hi"), config)
            val atReview = app.history(config).last()

            val events = app.streamFork(atReview, config.copy(threadId = "retry")) { it.copy(approved = true) }.toList()

            assertEquals(
                listOf(2 to listOf("review"), 3 to listOf("send")),
                events.filterIsInstance<GraphEvent.StepCompleted<Draft>>().map { it.step to it.nodes },
            )
            assertEquals(GraphEvent.Completed(Draft("Hi!", approved = true, sent = "sent Hi!")), events.last())
        }

    @Test
    fun `fork does not overwrite a thread that has a checkpoint`() =
        runTest {
            app.invoke(Draft("Hi"), config)
            val atReview = app.history(config).last()

            val failure = assertFailsWith<ThreadAlreadyExistsException> { app.fork(atReview, config) }

            assertEquals("t", failure.threadId)
            assertEquals(GraphResult.Interrupted(Draft("Hi!"), listOf("review")), app.lastResult(config))
        }

    @Test
    fun `fork checks the checkpoint and the config before it saves anything`() =
        runTest {
            app.invoke(Draft("Hi"), config)
            app.resume(config) { it.copy(approved = true) }
            val retry = config.copy(threadId = "retry")
            val finished = app.history(config).last()
            val atReview = app.history(config)[1]

            assertFailsWith<GraphValidationException> { app.fork(finished, retry) }
            assertFailsWith<GraphValidationException> { app.fork(atReview.copy(nextNodes = listOf("gone")), retry) }
            assertFailsWith<GraphValidationException> { app.fork(atReview, GraphConfig(threadId = "retry")) }
            assertNull(checkpointer.load("retry"))
        }

    @Test
    fun `a fork continues inside the subgraph the run had paused in`() =
        runTest {
            var visits = 0
            val inner =
                StateGraph<TestState> {
                    val first =
                        node("first") {
                            visits++
                            it.copy(count = it.count + 1)
                        }
                    val ask = node("ask") { if (it.count < 100) interrupt(it) else it }
                    START then first then ask then END
                }.compile()
            val outer = StateGraph<TestState> { START then subgraph("inner", inner) then END }.compile()
            val config = GraphConfig(checkpointer = MemoryCheckpointer<TestState>())
            outer.invoke(TestState(0), config)

            val forked = outer.fork(outer.history(config).last(), config.copy(threadId = "retry")) { it.copy(count = 100) }

            assertEquals(GraphResult.Completed(TestState(100)), forked)
            assertEquals(1, visits)
        }

    @Test
    fun `a checkpointer that keeps only the latest checkpoint has a history of one`() =
        runTest {
            val latestOnly =
                object : Checkpointer<Draft> {
                    var stored: Checkpoint<Draft>? = null

                    override suspend fun save(threadId: String, checkpoint: Checkpoint<Draft>) {
                        stored = checkpoint
                    }

                    override suspend fun load(threadId: String): Checkpoint<Draft>? = stored

                    override suspend fun delete(threadId: String) {
                        stored = null
                    }
                }
            val config = GraphConfig(checkpointer = latestOnly)
            assertEquals(emptyList(), app.history(config))

            app.invoke(Draft("Hi"), config)

            assertEquals(listOf(Checkpoint(Draft("Hi!"), listOf("review"), step = 1, interruptedBefore = true)), app.history(config))
        }
}
