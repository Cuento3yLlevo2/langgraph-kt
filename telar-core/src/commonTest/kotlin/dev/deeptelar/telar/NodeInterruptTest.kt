package dev.deeptelar.telar

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** A refund that a person has to approve. [question] is what the node asks, [approved] the answer. */
private data class Refund(
    val amount: Int,
    val question: String? = null,
    val approved: Boolean? = null,
    val paid: Int = 0,
    val notified: Boolean = false,
)

class NodeInterruptTest {
    /** How often the node "pay" started. */
    private var starts = 0

    /** "prepare" doubles the amount, and "pay" asks before it pays what "prepare" worked out. */
    private val app =
        StateGraph<Refund> {
            val prepare = node("prepare") { it.copy(amount = it.amount * 2) }
            val pay =
                node("pay") { refund ->
                    starts++
                    if (refund.approved == null) interrupt(refund.copy(question = "Refund ${refund.amount}?"))
                    refund.copy(question = null, paid = if (refund.approved) refund.amount else 0)
                }

            START then prepare then pay then END
        }.compile()

    private val checkpointer = MemoryCheckpointer<Refund>()
    private val config = GraphConfig(threadId = "t", checkpointer = checkpointer)

    @Test
    fun `interrupt pauses the run inside the node and saves the state it is given`() =
        runTest {
            val asked = Refund(amount = 50, question = "Refund 50?")

            assertEquals(GraphResult.Interrupted(asked, listOf("pay")), app.invoke(Refund(25), config))
            // The step of "pay" did not finish, so the thread still stands after step 1.
            assertEquals(Checkpoint(asked, listOf("pay"), step = 1, interruptedBefore = true), checkpointer.load("t"))
        }

    @Test
    fun `resume runs the node again from its first line with the answer in the state`() =
        runTest {
            app.invoke(Refund(25), config)

            val finished = app.resume(config) { it.copy(approved = true) }

            assertEquals(GraphResult.Completed(Refund(amount = 50, approved = true, paid = 50)), finished)
            assertEquals(2, starts)
            assertEquals(2, checkpointer.load("t")?.step)
        }

    @Test
    fun `resume without an answer pauses at the same place again`() =
        runTest {
            val paused = app.invoke(Refund(25), config)

            assertEquals(paused, app.resume(config))
            assertEquals(0, app.resume(config) { it.copy(approved = false) }.state.paid)
        }

    @Test
    fun `lastResult shows the question of a paused run`() =
        runTest {
            app.invoke(Refund(25), config)

            assertEquals(GraphResult.Interrupted(Refund(amount = 50, question = "Refund 50?"), listOf("pay")), app.lastResult(config))
        }

    @Test
    fun `a stream ends with Interrupted and the step keeps its number after resume`() =
        runTest {
            val asked = Refund(amount = 50, question = "Refund 50?")
            val done = Refund(amount = 50, approved = true, paid = 50)

            assertEquals(
                listOf(
                    GraphEvent.NodeStarted(1, "prepare", Refund(25)),
                    GraphEvent.NodeCompleted(1, "prepare", Refund(50)),
                    GraphEvent.StepCompleted(1, listOf("prepare"), Refund(50)),
                    GraphEvent.NodeStarted(2, "pay", Refund(50)),
                    GraphEvent.Interrupted(asked, listOf("pay")),
                ),
                app.stream(Refund(25), config).toList(),
            )
            assertEquals(
                listOf(
                    GraphEvent.NodeStarted(2, "pay", asked.copy(approved = true)),
                    GraphEvent.NodeCompleted(2, "pay", done),
                    GraphEvent.StepCompleted(2, listOf("pay"), done),
                    GraphEvent.Completed(done),
                ),
                app.streamResume(config) { it.copy(approved = true) }.toList(),
            )
        }

    @Test
    fun `a node can pause more than once`() =
        runTest {
            val twice =
                StateGraph<TestState> {
                    START then
                        node("ask") {
                            if (it.count < 1) interrupt(it.copy(count = -1))
                            if (it.count < 2) interrupt(it.copy(count = -2))
                            it.copy(count = it.count * 10)
                        } then END
                }.compile()
            val config = GraphConfig(checkpointer = MemoryCheckpointer<TestState>())

            assertEquals(GraphResult.Interrupted(TestState(-1), listOf("ask")), twice.invoke(TestState(0), config))
            assertEquals(GraphResult.Interrupted(TestState(-2), listOf("ask")), twice.resume(config) { it.copy(count = 1) })
            assertEquals(GraphResult.Completed(TestState(20)), twice.resume(config) { it.copy(count = 2) })
        }

    @Test
    fun `interrupt in the first step keeps the thread at step 0`() =
        runTest {
            val first =
                StateGraph<TestState> {
                    START then node("ask") { if (it.count == 0) interrupt(it.copy(count = -1)) else it } then END
                }.compile()
            val checkpointer = MemoryCheckpointer<TestState>()

            first.invoke(TestState(0), GraphConfig(checkpointer = checkpointer))

            assertEquals(Checkpoint(TestState(-1), listOf("ask"), step = 0, interruptedBefore = true), checkpointer.load("default"))
        }

    @Test
    fun `a run that paused in a node does not pause before it again`() =
        runTest {
            val both = config.copy(interruptBefore = setOf("pay"))

            // First the pause that the config asks for, then the one that the node asks for.
            assertEquals(GraphResult.Interrupted(Refund(50), listOf("pay")), app.invoke(Refund(25), both))
            assertEquals(GraphResult.Interrupted(Refund(50, question = "Refund 50?"), listOf("pay")), app.resume(both))
            assertEquals(50, app.resume(both) { it.copy(approved = true) }.state.paid)
            assertEquals(2, starts)
        }

    @Test
    fun `the other nodes of the step are cancelled and run again after resume`() =
        runTest {
            var notifyStarts = 0
            val notifyBusy = CompletableDeferred<Unit>()
            val notifyCancelled = CompletableDeferred<Unit>()
            val parallel =
                StateGraph<Refund> {
                    val pay =
                        node("pay", work = { refund ->
                            if (refund.approved == null) {
                                notifyBusy.await()
                                interrupt(refund.copy(question = "Refund ${refund.amount}?"))
                            }
                            if (refund.approved) refund.amount else 0
                        }) { refund, paid -> refund.copy(question = null, paid = paid) }
                    val notify =
                        node("notify", work = { refund ->
                            notifyStarts++
                            // Before the answer this node is still busy when "pay" pauses the run.
                            if (refund.approved == null) {
                                notifyBusy.complete(Unit)
                                try {
                                    awaitCancellation()
                                } finally {
                                    notifyCancelled.complete(Unit)
                                }
                            }
                        }) { refund, _ -> refund.copy(notified = true) }

                    START then pay
                    START then notify
                }.compile()

            val asked = Refund(amount = 25, question = "Refund 25?")
            assertEquals(GraphResult.Interrupted(asked, listOf("pay", "notify")), parallel.invoke(Refund(25), config))
            notifyCancelled.await()
            assertEquals(Checkpoint(asked, listOf("pay", "notify"), step = 0, interruptedBefore = true), checkpointer.load("t"))

            val finished = parallel.resume(config) { it.copy(approved = true) }

            assertEquals(GraphResult.Completed(Refund(amount = 25, approved = true, paid = 25, notified = true)), finished)
            assertEquals(2, notifyStarts)
        }

    @Test
    fun `when two nodes of a step pause the second one asks after the next resume`() =
        runTest {
            val two =
                StateGraph<TestState> {
                    // Each node asks for its own digit of the count.
                    val ones =
                        node("ones", work = {
                            if (it.count % 10 ==
                                0
                            ) {
                                interrupt(it.copy(count = it.count - 1))
                            }
                        }) { state, _ -> state }
                    val tens =
                        node("tens", work = {
                            if (it.count / 10 ==
                                0
                            ) {
                                interrupt(it.copy(count = it.count - 2))
                            }
                        }) { state, _ -> state }

                    START then ones
                    START then tens
                }.compile()
            val config = GraphConfig(checkpointer = MemoryCheckpointer<TestState>())

            assertEquals(GraphResult.Interrupted(TestState(-1), listOf("ones", "tens")), two.invoke(TestState(0), config))
            assertEquals(GraphResult.Interrupted(TestState(3), listOf("ones", "tens")), two.resume(config) { TestState(5) })
            assertEquals(GraphResult.Completed(TestState(15)), two.resume(config) { TestState(15) })
        }

    @Test
    fun `interrupt works from a coroutine that the node starts`() =
        runTest {
            val nested =
                StateGraph<TestState> {
                    START then
                        node("ask") { state ->
                            if (state.count == 0) {
                                coroutineScope { launch { withContext(Dispatchers.Default) { interrupt(state.copy(count = -1)) } } }
                            }
                            state.copy(count = state.count + 1)
                        } then END
                }.compile()
            val config = GraphConfig(checkpointer = MemoryCheckpointer<TestState>())

            assertEquals(GraphResult.Interrupted(TestState(-1), listOf("ask")), nested.invoke(TestState(0), config))
            assertEquals(GraphResult.Completed(TestState(6)), nested.resume(config) { TestState(5) })
        }

    @Test
    fun `a node that catches exceptions still pauses`() =
        runTest {
            val careful =
                StateGraph<TestState> {
                    START then
                        node("ask") { state ->
                            try {
                                if (state.count == 0) interrupt(state.copy(count = -1))
                                state.copy(count = state.count + 1)
                            } catch (_: Exception) {
                                state.copy(count = 1000)
                            }
                        } then END
                }.compile()

            val paused = careful.invoke(TestState(0), GraphConfig(checkpointer = MemoryCheckpointer<TestState>()))

            assertEquals(GraphResult.Interrupted(TestState(-1), listOf("ask")), paused)
        }

    @Test
    fun `a graph that a node runs pauses by itself`() =
        runTest {
            val inner =
                StateGraph<TestState> {
                    START then node("ask") { if (it.count == 0) interrupt(it.copy(count = -1)) else it } then END
                }.compile()
            val innerConfig = GraphConfig(threadId = "inner", checkpointer = MemoryCheckpointer<TestState>())
            val outer =
                StateGraph<TestState> {
                    START then
                        node("call") { state ->
                            // The inner run pauses, and this node goes on with what it got back.
                            val result = inner.invoke(state, innerConfig)
                            assertIs<GraphResult.Interrupted<TestState>>(result)
                            state.copy(count = result.state.count * 10)
                        } then END
                }.compile()

            assertEquals(GraphResult.Completed(TestState(-10)), outer.invoke(TestState(0)))
        }

    @Test
    fun `interrupt without a checkpointer fails the run`() =
        runTest {
            val exception = assertFailsWith<GraphValidationException> { app.invoke(Refund(25)) }

            assertEquals(
                "Node 'pay' called interrupt(), which needs a GraphConfig with a checkpointer to save the paused run.",
                exception.message,
            )
        }

    @Test
    fun `interrupt outside a node fails`() =
        runTest {
            assertFailsWith<GraphValidationException> { interrupt(TestState(0)) }
        }

    @Test
    fun `interrupt from the update of a parallel node is a failure of that node`() =
        runTest {
            val late =
                StateGraph<TestState> {
                    val add = node("add", work = { }) { state, _ -> state.copy(count = state.count + 1) }
                    // An update runs a second time, outside its node, when the results of the step are
                    // combined. Only then has "add" raised the count.
                    val ask = node("ask", work = { }) { state, _ -> if (state.count == 1) interrupt(state) else state }

                    START then add
                    START then ask
                }.compile()

            val exception =
                assertFailsWith<NodeExecutionException> {
                    late.invoke(TestState(0), GraphConfig(checkpointer = MemoryCheckpointer<TestState>()))
                }

            assertEquals("ask", exception.nodeName)
            assertIs<GraphValidationException>(exception.cause)
        }
}
