package dev.deeptelar.telar.samples

import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.GraphResult
import dev.deeptelar.telar.MemoryCheckpointer
import dev.deeptelar.telar.agent.AgentState
import dev.deeptelar.telar.agent.ChatMessage
import dev.langchain4j.data.message.UserMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Keeps the samples (and the README snippets based on them) working. */
class SamplesTest {
    @Test
    fun `quick start answers each email according to its category`() =
        runTest {
            val graph = emailSupportGraph()

            val refund = graph.invoke(SupportEmail("Ana", "I was charged twice, I would like a refund.")).state
            assertEquals(Category.REFUND, refund.category)
            assertEquals("Hi Ana, your refund is on its way. It takes 3 to 5 days.", refund.reply)

            val technical = graph.invoke(SupportEmail("Ben", "The app shows an error when I log in.")).state
            assertEquals(Category.TECHNICAL, technical.category)
            assertEquals("Hi Ben, please update the app and try again. Here is our guide.", technical.reply)

            val angry = graph.invoke(SupportEmail("Cleo", "This is unacceptable, third time I write to you!!")).state
            assertEquals(Category.ESCALATION, angry.category)
            assertEquals("Hi Cleo, a colleague from our team will reply to you personally today.", angry.reply)
        }

    @Test
    fun `quick start escalates emails that are complex or unclear`() {
        assertEquals(Category.ESCALATION, categoryOf("The app keeps crashing and I want my money back"))
        assertEquals(Category.ESCALATION, categoryOf("Do you sell gift cards?"))
    }

    @Test
    fun `refund waits for approval and survives a restart`() =
        runTest {
            val directory = Path(Files.createTempDirectory("refunds").toString())

            val paused = refundGraph().invoke(RefundState("1001", 250), refundConfig(directory, "order-1001"))
            assertIs<GraphResult.Interrupted<RefundState>>(paused)
            assertEquals(listOf(ISSUE_REFUND), paused.nextNodes)

            // New graph and checkpointer instances, as after a process restart.
            val finished = refundGraph().resume(refundConfig(directory, "order-1001")) { it.copy(approved = true) }
            assertEquals("Refund issued", finished.state.log.last())
        }

    @Test
    fun `a small payout does not pause`() =
        runTest {
            val directory = Path(Files.createTempDirectory("payouts").toString())

            val result = payoutGraph().invoke(Payout("Ana", items = listOf(15, 25)), payoutConfig(directory, "payout-1"))

            assertEquals(GraphResult.Completed(Payout("Ana", items = listOf(15, 25), log = listOf("Paid 40 to Ana"))), result)
        }

    @Test
    fun `a large payout asks from inside its node and survives a restart`() =
        runTest {
            val directory = Path(Files.createTempDirectory("payouts").toString())

            val paused = payoutGraph().invoke(Payout("Ben", items = listOf(200, 50)), payoutConfig(directory, "payout-2"))
            assertIs<GraphResult.Interrupted<Payout>>(paused)
            assertEquals("Pay 250 to Ben? That is over the limit of 100.", paused.state.question)
            assertEquals(listOf(PAY), paused.nextNodes)

            // New graph and checkpointer instances, as after a process restart.
            assertEquals(paused, payoutGraph().lastResult(payoutConfig(directory, "payout-2")))
            val finished = payoutGraph().resume(payoutConfig(directory, "payout-2")) { it.copy(approved = true) }
            assertEquals(listOf("Paid 250 to Ben"), finished.state.log)
            assertEquals(null, finished.state.question)

            val rejected = payoutGraph().invoke(Payout("Cleo", items = listOf(300)), payoutConfig(directory, "payout-3"))
            assertIs<GraphResult.Interrupted<Payout>>(rejected)
            val closed = payoutGraph().resume(payoutConfig(directory, "payout-3")) { it.copy(approved = false) }
            assertEquals(listOf("Payout of 300 rejected"), closed.state.log)
        }

    @Test
    fun `a decision model routes an email and an unsure decision goes to a person`() =
        runTest {
            val graph = routedSupport(scriptedDecisions)

            val categories =
                listOf("I would like a refund.", "The app shows an error.", "This is unacceptable!!").map {
                    graph.invoke(SupportEmail(sender = "Ana", body = it)).state.category
                }

            assertEquals(listOf(Category.REFUND, Category.TECHNICAL, Category.ESCALATION), categories)
        }

    @Test
    fun `a return pauses inside its payout subgraph and continues there after a restart`() =
        runTest {
            val directory = Path(Files.createTempDirectory("returns").toString())

            val paused = returnsGraph().invoke(ReturnCase("Ben", items = listOf(200, 0, 50)), returnsConfig(directory, "return-7"))
            assertIs<GraphResult.Interrupted<ReturnCase>>(paused)
            assertEquals(listOf(PAYOUT), paused.nextNodes)
            assertEquals("Pay 250 to Ben? That is over the limit of 100.", paused.state.payout?.question)

            // New graph and checkpointer instances, as after a process restart.
            assertEquals(paused, returnsGraph().lastResult(returnsConfig(directory, "return-7")))
            val finished =
                returnsGraph().resume(returnsConfig(directory, "return-7")) { it.copy(payout = it.payout?.copy(approved = true)) }
            assertIs<GraphResult.Completed<ReturnCase>>(finished)
            assertEquals("Dear Ben: Paid 250 to Ben.", finished.state.reply)
        }

    @Test
    fun `a small return runs through its subgraph without a pause`() =
        runTest {
            val result = returnsGraph().invoke(ReturnCase("Ana", items = listOf(15, 25)))

            assertEquals("Dear Ana: Paid 40 to Ana.", result.state.reply)
        }

    @Test
    fun `announcement is redrafted until the reviewer approves`() =
        runTest {
            val graph = announcementGraph()
            val config = GraphConfig(checkpointer = MemoryCheckpointer<AnnouncementState>(), interruptBefore = setOf(REVIEW))

            val first = graph.invoke(AnnouncementState(topic = "the 1.0 release"), config)
            assertEquals("Announcing the 1.0 release", first.state.draft)

            val second = graph.resume(config) { it.copy(approved = false, feedback = "mention Wasm") }
            assertIs<GraphResult.Interrupted<AnnouncementState>>(second)
            assertEquals("Announcing the 1.0 release (mention Wasm)", second.state.draft)
            assertEquals(second, graph.lastResult(config))

            val finished = graph.resume(config) { it.copy(approved = true) }
            assertIs<GraphResult.Completed<AnnouncementState>>(finished)
            assertTrue(finished.state.published)
            assertEquals(2, finished.state.revisions)
        }

    @Test
    fun `support agent continues a conversation from the last result`() =
        runTest {
            val agent = supportAgent(CannedModel())
            val config = GraphConfig(threadId = "customer-7", checkpointer = MemoryCheckpointer<ChatState>())

            for (question in listOf("The app is frozen", "I want a refund")) {
                val history =
                    agent
                        .lastResult(config)
                        ?.state
                        ?.messages
                        .orEmpty()
                agent.invoke(ChatState(history + UserMessage.from(question)), config)
            }

            val state = agent.lastResult(config)?.state
            assertEquals(4, state?.messages?.size)
            assertEquals(true, state?.needsEscalation)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `research sources run in parallel`() =
        runTest {
            val state = researchGraph(sourceDelayMillis = 300).invoke(ResearchState("coroutines")).state

            assertEquals(3, state.findings.size)
            assertEquals("3 sources agree.", state.summary)
            assertEquals(300, currentTime)
        }

    @Test
    fun `help desk agent looks up what the customer asks for`() =
        runTest {
            val agent = helpDeskAgent(scriptedModel)

            val state = agent.invoke(AgentState("I'm Ana. Where is my pizza, and how much is a cola?")).state

            assertEquals(
                "The pizza for Ana left the oven and the driver is 5 minutes away. One cola costs 2 euros.",
                state.answer,
            )
            assertEquals(2, state.messages.filterIsInstance<ChatMessage.ToolResult>().size)
        }

    @Test
    fun `help desk agent continues a conversation from the last result`() =
        runTest {
            val agent = helpDeskAgent(scriptedModel)
            val config = GraphConfig(threadId = "customer-7", checkpointer = MemoryCheckpointer<AgentState>())

            for (question in listOf("How much is a cola?", "And a salad?")) {
                val history = agent.lastResult(config)?.state ?: AgentState()
                agent.invoke(history.withUserMessage(question), config)
            }

            val state = agent.lastResult(config)?.state
            assertEquals("One salad costs 6 euros.", state?.answer)
            assertEquals(8, state?.messages?.size)
        }

    @Test
    fun `help desk agent tells the model when a tool fails`() =
        runTest {
            val state = helpDeskAgent(scriptedModel).invoke(AgentState("Do you sell tiramisu?")).state

            assertTrue(
                state.messages
                    .filterIsInstance<ChatMessage.ToolResult>()
                    .single()
                    .isError,
            )
            assertEquals("We do not sell tiramisu.", state.answer)
        }

    @Test
    fun `support agent escalates refund questions`() =
        runTest {
            val agent = supportAgent(CannedModel())

            assertTrue(agent.invoke(ChatState(listOf(UserMessage.from("I want a refund")))).state.needsEscalation)
            assertFalse(agent.invoke(ChatState(listOf(UserMessage.from("The app is frozen")))).state.needsEscalation)
        }
}
