package org.langgraphkt

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProgressTest {
    private val collected = mutableListOf<Boolean>()

    /** One node that reports twice and tells whether anybody collects what it reports. */
    private val app =
        StateGraph<TestState> {
            START then
                node("count") {
                    collected += isProgressCollected()
                    reportProgress("one")
                    reportProgress(2)
                    it.copy(count = it.count + 1)
                } then END
        }.compile()

    @Test
    fun `what a node reports arrives between its start and its completion`() =
        runTest {
            assertEquals(
                listOf(
                    GraphEvent.NodeStarted(1, "count", TestState(0)),
                    GraphEvent.NodeProgress(1, "count", "one", TestState(0)),
                    GraphEvent.NodeProgress(1, "count", 2, TestState(0)),
                    GraphEvent.NodeCompleted(1, "count", TestState(1)),
                    GraphEvent.StepCompleted(1, listOf("count"), TestState(1)),
                    GraphEvent.Completed(TestState(1)),
                ),
                app.stream(TestState(0)).toList(),
            )
            assertEquals(listOf(true), collected)
        }

    @Test
    fun `a run that is not collected ignores what a node reports`() =
        runTest {
            assertEquals(GraphResult.Completed(TestState(1)), app.invoke(TestState(0)))
            assertEquals(listOf(false), collected)
        }

    @Test
    fun `progress is not collected outside a node`() =
        runTest {
            reportProgress("nobody listens")

            assertFalse(isProgressCollected())
        }

    @Test
    fun `a resumed stream collects progress and a resumed run does not`() =
        runTest {
            val config = GraphConfig(checkpointer = MemoryCheckpointer<TestState>(), interruptBefore = setOf("count"))

            app.invoke(TestState(0), config)
            val events = app.streamResume(config).toList()
            app.invoke(TestState(0), config)
            app.resume(config)

            assertEquals(listOf<Any>("one", 2), events.filterIsInstance<GraphEvent.NodeProgress<TestState>>().map { it.value })
            assertEquals(listOf(true, false), collected)
        }

    @Test
    fun `progress is delivered while the node is still running`() =
        runTest {
            val slow =
                StateGraph<TestState> {
                    START then
                        node("slow") {
                            delay(100)
                            reportProgress("halfway")
                            delay(900)
                            it
                        } then END
                }.compile()

            val arrival = mutableMapOf<String, Long>()
            slow.stream(TestState(0)).collect { event ->
                when (event) {
                    is GraphEvent.NodeProgress -> arrival["progress"] = currentTime
                    is GraphEvent.NodeCompleted -> arrival["completed"] = currentTime
                    else -> Unit
                }
            }

            assertEquals(mapOf("progress" to 100L, "completed" to 1000L), arrival)
        }

    @Test
    fun `parallel nodes report under their own names`() =
        runTest {
            val parallel =
                StateGraph<TestState> {
                    val web = node("web", work = { reportProgress("searching the web") }) { state, _ -> state }
                    val docs = node("docs", work = { reportProgress("reading the docs") }) { state, _ -> state }
                    START then web then END
                    START then docs then END
                }.compile()

            val progress = parallel.stream(TestState(0)).filterIsInstance<GraphEvent.NodeProgress<TestState>>().toList()

            assertEquals(mapOf("web" to "searching the web", "docs" to "reading the docs"), progress.associate { it.node to it.value })
        }

    @Test
    fun `a node that reports more than the collector has read waits and loses nothing`() =
        runTest {
            val chatty =
                StateGraph<TestState> {
                    START then
                        node("chatty") {
                            repeat(1000) { reportProgress(it) }
                            it
                        } then END
                }.compile()

            val values =
                chatty
                    .stream(TestState(0))
                    .filterIsInstance<GraphEvent.NodeProgress<TestState>>()
                    .toList()
                    .map { it.value }

            assertEquals(List<Any>(1000) { it }, values)
        }

    @Test
    fun `a graph that a node runs reports to the stream of the outer run`() =
        runTest {
            val outer =
                StateGraph<TestState> {
                    START then node("kitchen") { app.invoke(it).state } then END
                }.compile()

            val progress = outer.stream(TestState(0)).filterIsInstance<GraphEvent.NodeProgress<TestState>>().toList()

            assertEquals(listOf("kitchen" to "one", "kitchen" to 2), progress.map { it.node to it.value })
        }

    @Test
    fun `a failing node ends the stream after what it reported`() =
        runTest {
            val broken =
                StateGraph<TestState> {
                    START then
                        node("broken") {
                            reportProgress("so far so good")
                            error("boom")
                        } then END
                }.compile()

            val seen = mutableListOf<GraphEvent<TestState>>()
            val failure = assertFailsWith<NodeExecutionException> { broken.stream(TestState(0)).collect { seen += it } }

            assertEquals("broken", failure.nodeName)
            assertEquals(GraphEvent.NodeProgress(1, "broken", "so far so good", TestState(0)), seen.last())
        }

    @Test
    fun `a coroutine that outlives its node reports to nobody`() =
        runTest {
            val runIsOver = CompletableDeferred<Unit>()
            var late: Job? = null
            val leaky =
                StateGraph<TestState> {
                    START then
                        node("leaky") {
                            // Keeps the node's context, but is not a child of the node.
                            late =
                                CoroutineScope(currentCoroutineContext().minusKey(Job)).launch {
                                    runIsOver.await()
                                    reportProgress("too late")
                                }
                            it
                        } then END
                }.compile()

            val events = leaky.stream(TestState(0)).toList()
            runIsOver.complete(Unit)
            late?.join()

            assertTrue(events.none { it is GraphEvent.NodeProgress })
            assertTrue(late?.isCancelled == false)
        }
}
