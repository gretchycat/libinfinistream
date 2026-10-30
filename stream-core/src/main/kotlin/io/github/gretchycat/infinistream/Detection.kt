package io.github.gretchycat.infinistream

import java.io.IOException

/** Each probe is charged by requested bytes, including repeats; absolute time and nesting are bounded. */
data class ProbeBudget(val maxBytes: Int = 64 * 1024, val maxProbes: Int = 64,
    val maxNanos: Long = 100_000_000, val maxDepth: Int = 8) {
    init { require(maxBytes in 0..(1024 * 1024) && maxProbes in 0..10000 && maxNanos >= 0 && maxDepth in 0..64) }
}
class ProbeReader(private val input: PositionalReadable, val metadata: StreamMetadata,
    private val budget: ProbeBudget = ProbeBudget(), depth: Int = 0,
    private val cancellation: CancellationToken = CancellationToken.NONE) {
    private var remaining = budget.maxBytes
    private var probes = budget.maxProbes
    private val started = System.nanoTime()
    init { require(depth in 0..budget.maxDepth) }
    fun bytes(position: Long, length: Int): ByteArray {
        cancellation.check()
        if (length < 0 || length > remaining || probes-- <= 0 || System.nanoTime() - started > budget.maxNanos)
            throw IOException("Probe budget exceeded")
        val bytes = ByteArray(length); requireRange(position, bytes, 0, length); remaining -= length
        var total = 0
        while (total < length) {
            cancellation.check()
            if (System.nanoTime() - started > budget.maxNanos) throw IOException("Probe time budget exceeded")
            val n = input.readAt(position + total, bytes, total, length - total)
            if (n < 0) break
            if (n == 0 || n > length - total) throw IOException("Probe source made no progress")
            total += n
        }
        return bytes.copyOf(total)
    }
}
data class FormatCandidate(val content: ContentType, val confidence: Double, val evidence: String) {
    init { require(confidence in 0.0..1.0) }
}
fun interface FormatProbe { fun detect(reader: ProbeReader): List<FormatCandidate> }
class SignatureProbe(private val signature: ByteArray, private val content: ContentType,
    private val offset: Long = 0) : FormatProbe {
    init { require(signature.isNotEmpty()); require(offset >= 0) }
    override fun detect(reader: ProbeReader): List<FormatCandidate> =
        if (reader.bytes(offset, signature.size).contentEquals(signature))
            listOf(FormatCandidate(content, 0.7, "Signature at byte $offset; structural validation required")) else emptyList()
}
class FormatDetector(private val probes: List<FormatProbe>) {
    fun detect(reader: ProbeReader): List<FormatCandidate> = probes.flatMap { it.detect(reader) }.sortedByDescending { it.confidence }
}
/** Sequential probes borrow this handle's buffered prefix; later reads replay it exactly once. */
class ReplayableSource(private val input: ByteSource, maxPeek: Int = 64 * 1024) : ByteSource {
    private val prefix: ByteArray
    private var count = 0
    private var cursor = 0
    private var readStarted = false
    private var closed = false
    init { require(maxPeek in 0..(1024 * 1024)); prefix = ByteArray(maxPeek) }
    override val metadata get() = input.metadata.copy(capabilities = input.metadata.capabilities - setOf(Capability.SEEK, Capability.POSITIONAL_READ), concurrency = Concurrency.SERIAL)
    fun peek(length: Int): ByteArray {
        if (closed) throw IOException("Source is closed")
        check(!readStarted) { "Probe must precede sequential consumption" }; require(length in 0..prefix.size)
        while (count < length) {
            val n = input.read(prefix, count, length - count)
            if (n < 0) break
            if (n == 0 || n > length - count) throw IOException("Peek source made no progress")
            count += n
        }
        return prefix.copyOf(minOf(count, length))
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (closed) throw IOException("Source is closed")
        requireRange(0, buffer, offset, length); if (length == 0) return 0
        readStarted = true
        if (cursor < count) { val n = minOf(length, count - cursor); prefix.copyInto(buffer, offset, cursor, cursor + n); cursor += n; return n }
        return input.read(buffer, offset, length)
    }
    override fun close() { if (!closed) { closed = true; input.close() } }
}
