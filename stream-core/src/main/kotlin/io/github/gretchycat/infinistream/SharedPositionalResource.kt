package io.github.gretchycat.infinistream

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicBoolean

/** Owns one demonstrated, known-length positional handle. Reopened views borrow it
 * with independent cursors; close the resource after all its consumers finish.
 * Reads are serialized for providers with a single underlying cursor/connection.
 */
class SharedPositionalResource(private val source: ByteSource,
    description: StreamMetadata? = null) : SourceResource, Closeable {
    private val positional = source as? PositionalReadable ?: throw IOException("Positional source required")
    private val snapshot = source.metadata
    private val size = snapshot.length ?: throw IOException("Known source length required")
    private val closed = AtomicBoolean(false)
    private val lock = Any()
    override val metadata = (description ?: snapshot).copy(length = size,
        capabilities = setOf(Capability.READ, Capability.POSITIONAL_READ, Capability.KNOWN_LENGTH, Capability.REOPEN),
        ownership = Ownership.BORROWED, concurrency = Concurrency.SERIAL)

    private fun checkOpen() {
        if (closed.get() || Thread.currentThread().isInterrupted)
            throw InterruptedIOException("Shared source closed or cancelled")
    }
    override fun open(): ByteSource {
        checkOpen()
        return object : ByteSource, PositionalReadable {
            override val metadata = this@SharedPositionalResource.metadata.copy(capabilities = this@SharedPositionalResource.metadata.capabilities - Capability.REOPEN)
            private val viewClosed = AtomicBoolean(false)
            private var cursor = 0L
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int = synchronized(lock) {
                requireRange(position, buffer, offset, length); checkOpen()
                if (viewClosed.get()) throw IOException("Borrowed source view is closed")
                if (length == 0) return@synchronized 0
                if (position >= size) return@synchronized -1
                val wanted = minOf(length.toLong(), size - position).toInt()
                val count = positional.readAt(position, buffer, offset, wanted)
                checkOpen()
                if (count < 0) throw EOFException("Positional source ended before its declared length")
                if (count == 0 || count > wanted) throw IOException("Invalid positional source read: $count")
                count
            }
            @Synchronized override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                readAt(cursor, buffer, offset, length).also { if (it > 0) cursor += it }
            override fun close() { viewClosed.set(true) }
        }
    }
    override fun close() {
        // Signal cancellation first. Provider close/timeouts govern an already blocked read.
        if (closed.compareAndSet(false, true)) source.close()
    }
}
