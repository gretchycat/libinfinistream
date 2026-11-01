package io.github.gretchycat.infinistream

import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException

/** Capability claims describe this handle, never an optimistic backend-wide promise. */
enum class Capability { READ, POSITIONAL_READ, WRITE, POSITIONAL_WRITE, SEEK, KNOWN_LENGTH, RESIZE, TRUNCATE, FLUSH, APPEND, ATOMIC_COMMIT, SPARSE, REOPEN }
enum class DataKind { BYTES, ENCODED_AUDIO, PCM_AUDIO, IMAGE_FRAME, VIDEO_FRAME, TEXT, EVENTS, DIRECTORY, STRUCTURED }
enum class Finiteness { FINITE, LIVE, UNKNOWN }
enum class Ownership { OWNED, BORROWED }
enum class Concurrency { SERIAL, POSITIONAL_READS, POSITIONAL_READ_WRITE }
enum class Verification { UNKNOWN, PROVISIONAL, VERIFIED }

data class ContentType(val kind: DataKind = DataKind.BYTES, val mimeType: String? = null,
    val format: String? = null, val encoding: String? = null, val byteOrder: String? = null)
data class Provenance(val source: String? = null, val offset: Long? = null, val length: Long? = null,
    val transformations: List<String> = emptyList(), val checksums: Map<String, String> = emptyMap())
/** Immutable snapshot by contract: callers must not mutate supplied collections. Unknown != zero. */
data class StreamMetadata(val content: ContentType = ContentType(), val name: String? = null,
    val length: Long? = null, val finiteness: Finiteness = Finiteness.UNKNOWN,
    val capabilities: Set<Capability> = emptySet(), val ownership: Ownership = Ownership.OWNED,
    val concurrency: Concurrency = Concurrency.SERIAL, val attributes: Map<String, String> = emptyMap(),
    val provenance: Provenance = Provenance(), val verification: Verification = Verification.UNKNOWN,
    val warnings: List<String> = emptyList(), val revision: Long = 0) {
    init { require(length == null || length >= 0); require(revision >= 0) }
}

/** All typed handles carry actual output metadata; no CPU byte conversion is required. */
interface StreamMetadataCarrier { val metadata: StreamMetadata }
interface StreamHandle : Closeable, StreamMetadataCarrier { override val metadata: StreamMetadata }
interface ByteSource : StreamHandle {
    /** Implementations advertise READ. -1 at EOF; zero only for length=0. Positive short reads are legal. */
    fun read(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset): Int
}
interface PositionalReadable {
    /** Independent 64-bit offset; never changes a sequential cursor. Same EOF rules as ByteSource. */
    fun readAt(position: Long, buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset): Int
}
interface ByteSink : StreamHandle {
    /** Writes the complete requested range or throws; errors are never converted to EOF. */
    fun write(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset)
}
interface PositionalWritable { fun writeAt(position: Long, buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset) }
interface MutableLength { fun resize(length: Long) }
interface Truncatable { fun truncate(length: Long) }
interface FlushableSink { fun flush() }
interface AppendableSink { fun append(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset) }
/** close discards unpublished work; commit is explicit, implementation-specific atomicity is metadata. */
interface AtomicCommit { fun commit() }
/** Sparse ranges are hints; gaps are not silently interpreted as readable zeroes. */
interface SparseRanges { fun allocatedRanges(position: Long, length: Long): List<LongRange> }
interface SourceResource { val metadata: StreamMetadata; fun open(): ByteSource }
fun interface CancellationToken {
    fun isCancelled(): Boolean
    fun check() { if (isCancelled() || Thread.currentThread().isInterrupted) throw InterruptedIOException("Stream operation cancelled") }
    companion object { val NONE = CancellationToken { false } }
}
data class StreamProgress(val completed: Long, val total: Long? = null)
fun interface ProgressObserver { fun update(progress: StreamProgress) }

fun requireRange(position: Long, buffer: ByteArray, offset: Int, length: Int) {
    if (position < 0 || length < 0 || offset < 0 || offset > buffer.size - length || position > Long.MAX_VALUE - length)
        throw IOException("Invalid stream byte range")
}

/** Independent cursor borrowing positional reads. close closes owner only when explicitly requested. */
class SourceCursor(private val input: PositionalReadable, metadata: StreamMetadata,
    private val owner: Closeable? = null) : ByteSource {
    override val metadata = metadata.copy(
        capabilities = setOf(Capability.READ, Capability.SEEK) + if (metadata.length != null) setOf(Capability.KNOWN_LENGTH) else emptySet(),
        ownership = if (owner == null) Ownership.BORROWED else Ownership.OWNED, concurrency = Concurrency.SERIAL)
    var position: Long = 0
        private set
    private var closed = false
    fun seek(position: Long) { checkOpen(); require(position >= 0); this.position = position }
    private fun checkOpen() { if (closed) throw IOException("Cursor is closed") }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkOpen(); requireRange(position, buffer, offset, length)
        if (length == 0) return 0
        val n = input.readAt(position, buffer, offset, length)
        if (n == 0 || n < -1 || n > length) throw IOException("Invalid positional read result: $n")
        if (n > 0) position += n
        return n
    }
    override fun close() { if (!closed) { closed = true; owner?.close() } }
}

/** Blocking pull copy: at most one chunk in flight, preserving downstream backpressure. */
fun copy(source: ByteSource, sink: ByteSink, cancellation: CancellationToken = CancellationToken.NONE,
    progress: ProgressObserver? = null, bufferSize: Int = 64 * 1024, maxBytes: Long? = null): Long {
    require(maxBytes == null || maxBytes >= 0)
    require(bufferSize in 1..(1024 * 1024))
    val buffer = ByteArray(bufferSize); var total = 0L
    while (true) {
        cancellation.check()
        val n = source.read(buffer)
        if (n == -1) break
        if (n < -1 || n == 0 || n > buffer.size) throw IOException("Source made no progress")
        if (total > Long.MAX_VALUE - n) throw IOException("Stream byte count overflow")
        if (maxBytes != null && total + n > maxBytes) throw StreamLimitExceededException(maxBytes)
        cancellation.check(); sink.write(buffer, 0, n); total += n
        progress?.update(StreamProgress(total, source.metadata.length))
    }
    return total
}

/** A bounded operation fails explicitly rather than publishing a truncated success. */
class StreamLimitExceededException(val maxBytes: Long) : IOException("Input exceeds $maxBytes bytes")
