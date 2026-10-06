package dev.deeptelar.telar.checkpoint.file

import dev.deeptelar.telar.Checkpoint
import dev.deeptelar.telar.Checkpointer
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
 * A persistent [Checkpointer] that stores the latest checkpoint of each thread as one JSON file
 * inside [directory].
 *
 * Works on every target that has a file system: JVM/Android, Apple, Linux and Windows native, and
 * JS/Wasm running on Node.js. It is not available in browsers.
 *
 * Writes are atomic: a checkpoint is written to a temporary file and then moved into place, so a
 * crash in the middle of a save leaves the previous checkpoint intact. One instance is safe to share
 * between coroutines; do not point several instances or processes at the same directory.
 *
 * @param directory where checkpoint files are stored. Created if it does not exist.
 * @param serializer converts the graph state to and from a string.
 * @param fileSystem the file system to use; replaceable in tests.
 */
public class FileCheckpointer<State>(
    private val directory: Path,
    serializer: StateSerializer<State>,
    private val fileSystem: FileSystem = SystemFileSystem,
) : Checkpointer<State> {
    private val codec = CheckpointCodec(serializer)
    private val writeLock = Mutex()

    init {
        fileSystem.createDirectories(directory)
    }

    override suspend fun save(threadId: String, checkpoint: Checkpoint<State>) {
        val json = codec.encode(checkpoint)
        val target = fileFor(threadId)
        val temporary = Path(directory, "${target.name}.tmp")

        writeLock.withLock {
            withContext(ioDispatcher) {
                fileSystem.sink(temporary).buffered().use { it.writeString(json) }
                fileSystem.atomicMove(temporary, target)
            }
        }
    }

    override suspend fun load(threadId: String): Checkpoint<State>? {
        val json =
            withContext(ioDispatcher) {
                val file = fileFor(threadId)
                if (fileSystem.exists(file)) fileSystem.source(file).buffered().use { it.readString() } else null
            } ?: return null
        return codec.decode(threadId, json)
    }

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
