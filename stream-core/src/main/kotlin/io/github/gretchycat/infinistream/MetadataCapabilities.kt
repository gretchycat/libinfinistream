package io.github.gretchycat.infinistream

import java.io.Closeable
import java.io.IOException

/** Bounded reader supplied to metadata parsers. It never materializes the whole resource. */
class MetadataReadContext(
    val resource: CapabilityResource,
    val maxBytes: Long = 1024 * 1024,
    val maxReads: Int = 256,
    val maxNanos: Long = 1_000_000_000,
    val cancellation: CancellationToken = CancellationToken.NONE
) : Closeable {
    private val input: ByteSource
    private val positional: PositionalReadable?
    private val started = System.nanoTime()
    private var bytesRead = 0L
    private var sequentialPosition = 0L
    private var readsRemaining = maxReads
    private var closed = false

    init {
        require(maxBytes in 0..(64L * 1024 * 1024) && maxReads in 0..10000 && maxNanos in 0..60_000_000_000L)
        input = resource.source?.open() ?: throw IOException("Metadata extraction requires a readable source")
        positional = input as? PositionalReadable
    }

    val length: Long? get() = input.metadata.length ?: resource.metadata.length
    val bytesConsumed: Long get() = bytesRead

    /** Reads a bounded range; sequential-only sources may be read only in ascending order. */
    @Throws(IOException::class)
    fun readAt(position: Long, length: Int): ByteArray {
        check(!closed) { "Metadata reader is closed" }
        if (position < 0 || length < 0 || position > Long.MAX_VALUE - length) throw IOException("Invalid metadata range")
        cancellation.check()
        if (readsRemaining-- <= 0 || System.nanoTime() - started > maxNanos)
            throw IOException("Metadata read budget exceeded")
        val knownLength = this.length
        if (knownLength != null && position >= knownLength) return ByteArray(0)
        val availableByLength = if (knownLength == null) length.toLong() else minOf(length.toLong(), knownLength - position)
        if (availableByLength > maxBytes - bytesRead) throw IOException("Metadata byte budget exceeded")
        val wanted = availableByLength.toInt()
        if (wanted == 0) return ByteArray(0)
        val result = ByteArray(wanted)
        if (positional != null) {
            var total = 0
            while (total < wanted) {
                checkBudget()
                ensureCanCharge((wanted - total).toLong())
                val count = positional.readAt(position + total, result, total, wanted - total)
                if (count == -1) break
                if (count <= 0 || count > wanted - total) throw IOException("Invalid metadata read result: $count")
                charge(count.toLong())
                total += count
            }
            return result.copyOf(total)
        }
        if (position < sequentialPosition) throw IOException("Metadata source is sequential and cannot seek backwards")
        skipTo(position)
        var total = 0
        while (total < wanted) {
            checkBudget()
            ensureCanCharge((wanted - total).toLong())
            val count = input.read(result, total, wanted - total)
            if (count == -1) break
            if (count <= 0 || count > wanted - total) throw IOException("Invalid metadata read result: $count")
            charge(count.toLong())
            sequentialPosition += count
            total += count
        }
        return result.copyOf(total)
    }

    private fun skipTo(position: Long) {
        val scratch = ByteArray(8192)
        while (sequentialPosition < position) {
            checkBudget()
            val requested = minOf(scratch.size.toLong(), position - sequentialPosition).toInt()
            ensureCanCharge(requested.toLong())
            val count = input.read(scratch, 0, requested)
            if (count == -1) throw IOException("Metadata range is beyond end of sequential source")
            if (count <= 0 || count > requested) throw IOException("Invalid metadata skip result: $count")
            charge(count.toLong())
            sequentialPosition += count
        }
    }

    private fun charge(count: Long) {
        if (count < 0 || count > maxBytes - bytesRead) throw IOException("Metadata byte budget exceeded")
        bytesRead += count
    }

    private fun ensureCanCharge(count: Long) {
        if (count < 0 || count > maxBytes - bytesRead) throw IOException("Metadata byte budget exceeded")
    }

    private fun checkBudget() {
        cancellation.check()
        if (System.nanoTime() - started > maxNanos) throw IOException("Metadata time budget exceeded")
    }

    override fun close() {
        if (!closed) {
            closed = true
            input.close()
        }
    }
}

/** Normalized facts from one real format parser. All fields are display-ready but remain unlocalized. */
data class MetadataRecord(
    val formatId: String,
    val mimeType: String,
    val title: String? = null,
    val fields: Map<String, String> = emptyMap(),
    val warnings: List<String> = emptyList()
) {
    init { require(formatId.isNotBlank() && mimeType.isNotBlank()) }
}

/** Implemented by format handlers that can parse metadata without opening a viewer. */
interface MetadataCapabilityHandler : CapabilityHandler {
    override fun extractMetadata(context: MetadataReadContext): MetadataRecord?
}

