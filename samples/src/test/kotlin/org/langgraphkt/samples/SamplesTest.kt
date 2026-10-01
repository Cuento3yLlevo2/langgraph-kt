package org.langgraphkt.samples

import dev.langchain4j.data.message.UserMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import org.langgraphkt.GraphResult
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Keeps the samples (and the README snippets based on them) working. */
class SamplesTest {
    @Test
    fun `quick start revises until every note is used`() =
        runTest {
            val state = articleGraph().invoke(ArticleState(topic = "Kotlin")).state

            assertEquals(2, state.revisions)
            assertEquals("Kotlin is fast. Kotlin is safe", state.draft)
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
    fun `support agent escalates refund questions`() =
        runTest {
            val agent = supportAgent(CannedModel())

            assertTrue(agent.invoke(ChatState(listOf(UserMessage.from("I want a refund")))).state.needsEscalation)
            assertFalse(agent.invoke(ChatState(listOf(UserMessage.from("The app is frozen")))).state.needsEscalation)
        }
}
