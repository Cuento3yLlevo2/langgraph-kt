package org.langgraphkt.samples.tutorial

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.langgraphkt.GraphConfig
import org.langgraphkt.GraphResult
import org.langgraphkt.GraphValidationException
import org.langgraphkt.MaxIterationsExceededException
import org.langgraphkt.MemoryCheckpointer
import org.langgraphkt.NodeExecutionException
import org.langgraphkt.samples.tutorial.level6.describe
import org.langgraphkt.samples.tutorial.level7.PAY
import org.langgraphkt.samples.tutorial.level8.PretendModel
import org.langgraphkt.samples.tutorial.level9.endlessLoop
import org.langgraphkt.samples.tutorial.level9.forgottenArrow
import org.langgraphkt.samples.tutorial.level9.missingReducer
import org.langgraphkt.samples.tutorial.level9.misspelledNode
import org.langgraphkt.states
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import org.langgraphkt.samples.tutorial.level1.Ticket as Ticket1
import org.langgraphkt.samples.tutorial.level1.helpDesk as helpDesk1
import org.langgraphkt.samples.tutorial.level10.PAY as PAY10
import org.langgraphkt.samples.tutorial.level10.Ticket as Ticket10
import org.langgraphkt.samples.tutorial.level10.helpDesk as helpDesk10
import org.langgraphkt.samples.tutorial.level2.Ticket as Ticket2
import org.langgraphkt.samples.tutorial.level2.helpDesk as helpDesk2
import org.langgraphkt.samples.tutorial.level3.Ticket as Ticket3
import org.langgraphkt.samples.tutorial.level3.helpDesk as helpDesk3
import org.langgraphkt.samples.tutorial.level4.Ticket as Ticket4
import org.langgraphkt.samples.tutorial.level4.helpDesk as helpDesk4
import org.langgraphkt.samples.tutorial.level5.Ticket as Ticket5
import org.langgraphkt.samples.tutorial.level5.helpDesk as helpDesk5
import org.langgraphkt.samples.tutorial.level7.Ticket as Ticket7
import org.langgraphkt.samples.tutorial.level7.helpDesk as helpDesk7
import org.langgraphkt.samples.tutorial.level8.Ticket as Ticket8
import org.langgraphkt.samples.tutorial.level8.helpDesk as helpDesk8
import org.langgraphkt.samples.tutorial.level9.Ticket as Ticket9
import org.langgraphkt.samples.tutorial.level9.helpDesk as helpDesk9

/** Keeps the code and the output shown in the tutorial in `docs/` true. */
class TutorialTest {
    @Test
    fun `level 1 greets the customer`() =
        runTest {
            val state = helpDesk1().invoke(Ticket1("Ana", "Where is my pizza?")).state

            assertEquals("Hi Ana, thanks for writing to Pixel Pizza!", state.reply)
        }

    @Test
    fun `level 2 passes the topic from one node to the next`() =
        runTest {
            val state = helpDesk2().invoke(Ticket2("Ana", "Where is my pizza?")).state

            assertEquals("delivery", state.topic)
            assertEquals("Hi Ana, we got your delivery question.", state.reply)
        }

    @Test
    fun `level 3 takes a different path for each topic`() =
        runTest {
            val graph = helpDesk3()
            val replies =
                listOf(
                    "Where is my pizza?",
                    "I want a refund",
                    "Do you sell salad?",
                ).map { graph.invoke(Ticket3("Ana", it)).state.reply }

            assertEquals(
                listOf(
                    "Your pizza left the oven and is on its way.",
                    "We are sorry. Your money is on its way back.",
                    "Thanks for your message. A human will reply soon.",
                ),
                replies,
            )
        }

    @Test
    fun `level 4 rewrites the reply until it passes the check`() =
        runTest {
            val state = helpDesk4().invoke(Ticket4("Ana", "My pizza is late!")).state

            assertEquals(3, state.attempts)
            assertEquals("Sorry Ana, your pizza is late. It arrives in 10 minutes.", state.reply)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `level 5 runs both lookups at the same time`() =
        runTest {
            val state = helpDesk5().invoke(Ticket5("Ana", "Where is my pizza?")).state

            assertEquals("Hi Ana, your pizza left the oven and the driver is 5 minutes away.", state.reply)
            assertEquals(1_000, currentTime)
        }

    @Test
    fun `level 6 reports every node and step`() =
        runTest {
            val events = helpDesk5().stream(Ticket5("Ana", "Where is my pizza?")).map { describe(it) }.toList()

            assertEquals(
                listOf(
                    "step 1: kitchen started",
                    "step 1: driver started",
                    "step 1: kitchen finished",
                    "step 1: driver finished",
                    "step 1 done, facts so far: 2",
                    "step 2: answer started",
                    "step 2: answer finished",
                    "step 2 done, facts so far: 2",
                    "finished: Hi Ana, your pizza left the oven and the driver is 5 minutes away.",
                ),
                events,
            )
            assertEquals(
                2,
                helpDesk5()
                    .stream(Ticket5("Ana", "Where is my pizza?"))
                    .states()
                    .toList()
                    .size,
            )
        }

    @Test
    fun `level 7 pauses before paying and continues with the decision`() =
        runTest {
            val graph = helpDesk7()
            val config = GraphConfig(threadId = "ticket-42", checkpointer = MemoryCheckpointer<Ticket7>(), interruptBefore = setOf(PAY))

            val paused = graph.invoke(Ticket7("Ana", "My pizza arrived cold. I want a refund."), config)
            assertIs<GraphResult.Interrupted<Ticket7>>(paused)
            assertEquals(listOf(PAY), paused.nextNodes)
            assertEquals(12, paused.state.refund)
            assertEquals(paused, graph.lastResult(config))

            val finished = graph.resume(config) { it.copy(approved = true) }
            assertIs<GraphResult.Completed<Ticket7>>(finished)
            assertEquals("Sorry Ana! We sent you 12 euros.", finished.state.reply)
        }

    @Test
    fun `level 7 keeps tickets apart by thread id`() =
        runTest {
            val graph = helpDesk7()
            val checkpointer = MemoryCheckpointer<Ticket7>()
            val ana = GraphConfig(threadId = "ticket-42", checkpointer = checkpointer, interruptBefore = setOf(PAY))
            val ben = ana.copy(threadId = "ticket-43")

            graph.invoke(Ticket7("Ana", "Cold pizza"), ana)
            graph.invoke(Ticket7("Ben", "Wrong pizza"), ben)

            assertEquals("Sorry Ben, we cannot refund this order.", graph.resume(ben).state.reply)
            assertEquals("Sorry Ana! We sent you 12 euros.", graph.resume(ana) { it.copy(approved = true) }.state.reply)
        }

    @Test
    fun `level 8 stores the reply of the model`() =
        runTest {
            val state = helpDesk8(PretendModel()).invoke(Ticket8("Ana", "Where is my pizza?")).state

            assertEquals("Thanks for your patience! Your pizza is on its way.", state.reply)
        }

    @Test
    fun `level 9 mistakes are reported with the messages shown in the tutorial`() =
        runTest {
            assertEquals(
                "Edge references unknown to-node: anwser",
                assertFailsWith<GraphValidationException> { misspelledNode() }.message,
            )
            assertEquals(
                "Node 'check' is not reachable from START",
                assertFailsWith<GraphValidationException> { forgottenArrow() }.message,
            )
            assertEquals(
                "Node 'read' fans out to several nodes that return a whole state, so compile() needs a Reducer, or those nodes need a work and an update",
                assertFailsWith<GraphValidationException> { missingReducer() }.message,
            )
            assertEquals(
                "Graph execution exceeded max iterations (25). Possible infinite loop.",
                assertFailsWith<MaxIterationsExceededException> { endlessLoop().invoke(Ticket9("Ana", "Hello")) }.message,
            )
        }

    @Test
    fun `level 9 retries a failed node with resume`() =
        runTest {
            var calls = 0
            val graph = helpDesk9 { if (++calls == 1) error("the kitchen phone is busy") else "Your pizza is in the oven." }
            val config = GraphConfig(threadId = "ticket-42", checkpointer = MemoryCheckpointer<Ticket9>())

            val failure = assertFailsWith<NodeExecutionException> { graph.invoke(Ticket9("Ana", "Where is my pizza?"), config) }
            assertEquals("Node 'kitchen' failed: the kitchen phone is busy", failure.message)
            assertEquals("kitchen", failure.nodeName)

            assertEquals("Hi Ana! Your pizza is in the oven.", graph.resume(config).state.reply)
        }

    @Test
    fun `level 10 handles a delivery question, a refund and anything else`() =
        runTest {
            val graph = helpDesk10()
            val checkpointer = MemoryCheckpointer<Ticket10>()

            fun config(threadId: String) = GraphConfig(threadId = threadId, checkpointer = checkpointer, interruptBefore = setOf(PAY10))

            val delivery = graph.invoke(Ticket10("Ana", "Where is my pizza?"), config("ticket-1"))
            assertEquals("Hi Ana, your pizza left the oven and the driver is 5 minutes away.", delivery.state.reply)
            assertEquals(2, delivery.state.attempts)

            val refund = graph.invoke(Ticket10("Ana", "My pizza arrived cold. I want a refund."), config("ticket-2"))
            assertIs<GraphResult.Interrupted<Ticket10>>(refund)
            assertEquals("Sorry Ana! We sent you 12 euros.", graph.resume(config("ticket-2")) { it.copy(approved = true) }.state.reply)

            val other = graph.invoke(Ticket10("Ana", "Do you sell salad?"), config("ticket-3"))
            assertEquals("Hi Ana, a colleague will reply soon.", other.state.reply)
        }
}
