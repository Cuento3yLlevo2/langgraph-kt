@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.deeptelar.telar.checkpoint.browser

import dev.deeptelar.telar.Checkpoint
import dev.deeptelar.telar.Checkpointer
import dev.deeptelar.telar.GraphValidationException
import dev.deeptelar.telar.StateSerializer
import dev.deeptelar.telar.TelarException
import dev.deeptelar.telar.serialization.CheckpointCodec
import kotlin.js.ExperimentalWasmJsInterop

/**
 * A persistent [Checkpointer] for apps that run in a browser. It stores the checkpoints of each
 * thread in the page's `localStorage`, so a paused run is still there after the page is reloaded
 * or the browser is closed.
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
 *   [LocalStorageException] and the checkpoints that were stored before stay as they were. For
 *   that reason a thread keeps only its latest checkpoint unless you raise [maxHistory].
 * - The person using the browser can read and change it. Do not keep secrets in the state.
 * - It is not available in a web worker, on Node.js, or when the browser blocks storage for the
 *   page. Every call then throws a [LocalStorageException].
 *
 * The entries have the format of [CheckpointCodec], the same as the files of `FileCheckpointer`.
 *
 * @param serializer converts the graph state to and from a string.
 * @param keyPrefix what the key of an entry starts with. The thread id follows it.
 * @param maxHistory how many checkpoints a thread keeps, counted from the latest, for
 * `CompiledGraph.history` and `fork`. The default keeps only the latest.
 * @throws GraphValidationException if [maxHistory] is not positive.
 */
public class LocalStorageCheckpointer<State>(
    serializer: StateSerializer<State>,
    private val keyPrefix: String = "telar.checkpoint.",
    private val maxHistory: Int = 1,
) : Checkpointer<State> {
    private val codec = CheckpointCodec(serializer)

    init {
        if (maxHistory <= 0) throw GraphValidationException("maxHistory must be positive, was $maxHistory.")
    }

    override suspend fun save(threadId: String, checkpoint: Checkpoint<State>) {
        val stored = if (maxHistory == 1) null else storage(threadId, "read the checkpoints") { getItem(keyPrefix + threadId) }
        val history = codec.append(stored, checkpoint, maxHistory)
        storage(threadId, "store the checkpoint (${history.length} characters). The storage of this page may be full") {
            setItem(keyPrefix + threadId, history)
        }
    }

    override suspend fun load(threadId: String): Checkpoint<State>? =
        storage(threadId, "read the checkpoint") { getItem(keyPrefix + threadId) }?.let { codec.decode(threadId, it) }

    override suspend fun history(threadId: String): List<Checkpoint<State>> =
        storage(threadId, "read the checkpoints") { getItem(keyPrefix + threadId) }?.let { codec.decodeHistory(threadId, it) }.orEmpty()

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
