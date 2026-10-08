package dev.deeptelar.telar.checkpoint.file

import dev.deeptelar.telar.Checkpoint
import dev.deeptelar.telar.Checkpointer
import dev.deeptelar.telar.GraphValidationException
import dev.deeptelar.telar.StateSerializer
import dev.deeptelar.telar.serialization.CheckpointCodec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString

/**
 * A persistent [Checkpointer] that stores the checkpoints of each thread in one file inside
 * [directory]: one line of JSON for each step of the thread, oldest first.
 *
 * Works on every target that has a file system: JVM/Android, Apple, Linux and Windows native, and
 * JS/Wasm running on Node.js. It is not available in browsers.
 *
 * Writes are atomic: a checkpoint is written to a temporary file and then moved into place, so a
 * crash in the middle of a save leaves the previous checkpoints intact. One instance is safe to
 * share between coroutines; do not point several instances or processes at the same directory.
 *
 * A save writes the file of its thread again, with the whole history. Give a thread that runs for
 * hundreds of steps a [maxHistory].
 *
 * @param directory where checkpoint files are stored. Created if it does not exist.
 * @param serializer converts the graph state to and from a string.
 * @param fileSystem the file system to use; replaceable in tests.
 * @param maxHistory how many checkpoints a thread keeps, counted from the latest. The default keeps
 * all of them; `1` keeps only the latest.
 * @throws GraphValidationException if [maxHistory] is not positive.
 */
public class FileCheckpointer<State>(
    private val directory: Path,
    serializer: StateSerializer<State>,
    private val fileSystem: FileSystem = SystemFileSystem,
    private val maxHistory: Int = Int.MAX_VALUE,
) : Checkpointer<State> {
    private val codec = CheckpointCodec(serializer)
    private val writeLock = Mutex()

    init {
        if (maxHistory <= 0) throw GraphValidationException("maxHistory must be positive, was $maxHistory.")
        fileSystem.createDirectories(directory)
    }

    override suspend fun save(threadId: String, checkpoint: Checkpoint<State>) {
        val target = fileFor(threadId)
        val temporary = Path(directory, "${target.name}.tmp")

        writeLock.withLock {
            withContext(ioDispatcher) {
                val history = codec.append(read(target), checkpoint, maxHistory)
                fileSystem.sink(temporary).buffered().use { it.writeString(history) }
                fileSystem.atomicMove(temporary, target)
            }
        }
    }

    override suspend fun load(threadId: String): Checkpoint<State>? =
        withContext(ioDispatcher) { read(fileFor(threadId)) }?.let { codec.decode(threadId, it) }

    override suspend fun history(threadId: String): List<Checkpoint<State>> =
        withContext(ioDispatcher) { read(fileFor(threadId)) }?.let { codec.decodeHistory(threadId, it) }.orEmpty()

    private fun read(file: Path): String? =
        if (fileSystem.exists(file)) fileSystem.source(file).buffered().use { it.readString() } else null

    override suspend fun delete(threadId: String) {
        writeLock.withLock {
            withContext(ioDispatcher) { fileSystem.delete(fileFor(threadId), mustExist = false) }
        }
    }

    private fun fileFor(threadId: String): Path = Path(directory, "${encodeFileName(threadId)}.json")
}

/**
 * Maps a thread id to a file name that is safe on every platform and unique per id.
 *
 * Lowercase ASCII letters, digits, `_` and `-` are kept; every other character is written as the
 * `%XX` hex of its UTF-8 bytes. Uppercase letters are escaped too, so ids that differ only by case
 * do not collide on case-insensitive file systems (macOS, Windows).
 */
internal fun encodeFileName(threadId: String): String =
    buildString {
        for (byte in threadId.encodeToByteArray()) {
            val char = byte.toInt().toChar()
            if (char in 'a'..'z' || char in '0'..'9' || char == '_' || char == '-') {
                append(char)
            } else {
                append('%').append((byte.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0'))
            }
        }
    }
