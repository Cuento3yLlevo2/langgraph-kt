@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.deeptelar.telar.checkpoint.browser

import dev.deeptelar.telar.Checkpoint
import dev.deeptelar.telar.Checkpointer
import dev.deeptelar.telar.StateSerializer
import dev.deeptelar.telar.TelarException
import dev.deeptelar.telar.serialization.CheckpointCodec
import kotlin.js.ExperimentalWasmJsInterop

/**
 * A persistent [Checkpointer] for apps that run in a browser. It stores the latest checkpoint of
 * each thread in the page's `localStorage`, so a paused run is still there after the page is
 * reloaded or the browser is closed.
 *
 * ```kotlin
 * val config = GraphConfig(
 *     threadId = "ticket-42",
 *     checkpointer = LocalStorageCheckpointer(KotlinxStateSerializer<Ticket>()),
 *     interruptBefore = setOf("pay"),
 * )
 * ```
 *
 * What to know about `localStorage`:
 *
 * - It belongs to the origin of the page (scheme, host and port). Every page of that origin reads
 *   and writes the same entries, so give [keyPrefix] a name of your own.
 * - A browser keeps about 5 MB for an origin. When it is full, [save] throws a
 *   [LocalStorageException] and the checkpoint that was stored before stays as it was.
 * - The person using the browser can read and change it. Do not keep secrets in the state.
 * - It is not available in a web worker, on Node.js, or when the browser blocks storage for the
 *   page. Every call then throws a [LocalStorageException].
 *
 * The entries have the format of [CheckpointCodec], the same as the files of `FileCheckpointer`.
 *
 * @param serializer converts the graph state to and from a string.
 * @param keyPrefix what the key of an entry starts with. The thread id follows it.
 */
public class LocalStorageCheckpointer<State>(
    serializer: StateSerializer<State>,
    private val keyPrefix: String = "langgraph.checkpoint.",
) : Checkpointer<State> {
    private val codec = CheckpointCodec(serializer)

    override suspend fun save(threadId: String, checkpoint: Checkpoint<State>) {
        val json = codec.encode(checkpoint)
        storage(threadId, "store the checkpoint (${json.length} characters). The storage of this page may be full") {
            setItem(keyPrefix + threadId, json)
        }
    }

    override suspend fun load(threadId: String): Checkpoint<State>? =
        storage(threadId, "read the checkpoint") { getItem(keyPrefix + threadId) }?.let { codec.decode(threadId, it) }

    override suspend fun delete(threadId: String) {
        storage(threadId, "remove the checkpoint") { removeItem(keyPrefix + threadId) }
    }

    /**
     * Runs [call] and reports an error of the browser as a [LocalStorageException]. A call to
     * `localStorage` returns at once and does not suspend, so no cancellation can end up here.
     */
    private inline fun <Result> storage(threadId: String, action: String, call: () -> Result): Result =
        try {
            call()
        } catch (e: Throwable) {
            throw LocalStorageException(threadId, "localStorage could not $action.", e)
        }
}

/**
 * The browser refused to read or write `localStorage` for the checkpoint of [threadId]: the storage
 * of the page is full, or the page may not use it. The error of the browser is the [cause].
 */
public class LocalStorageException(
    public val threadId: String,
    message: String,
    cause: Throwable? = null,
) : TelarException("Checkpoint of thread '$threadId': $message", cause)

private fun getItem(key: String): String? = js("globalThis.localStorage.getItem(key)")

private fun setItem(key: String, value: String): Unit = js("globalThis.localStorage.setItem(key, value)")

private fun removeItem(key: String): Unit = js("globalThis.localStorage.removeItem(key)")
