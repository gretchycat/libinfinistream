package io.github.gretchycat.infinistream

import java.io.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

class InputStreamSource(private val input: InputStream,
    override val metadata: StreamMetadata = StreamMetadata(capabilities = setOf(Capability.READ)),
    private val ownsInput: Boolean = true,
    private val cancellation: CancellationToken = CancellationToken.NONE) : ByteSource {
    private var closed = false
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (closed) throw IOException("Source is closed")
        requireRange(0, buffer, offset, length); cancellation.check()
        if (length == 0) return 0
        val n = input.read(buffer, offset, length)
        if (n == 0) throw IOException("Input stream made no progress")
        return n
    }
    override fun close() { if (!closed) { closed = true; if (ownsInput) input.close() } }
}
class OutputStreamSink(private val output: OutputStream,
    override val metadata: StreamMetadata = StreamMetadata(capabilities = setOf(Capability.WRITE, Capability.FLUSH)),
    private val ownsOutput: Boolean = true) : ByteSink, FlushableSink {
    private var closed = false
    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (closed) throw IOException("Sink is closed")
        requireRange(0, buffer, offset, length); output.write(buffer, offset, length)
    }
    override fun flush() { if (closed) throw IOException("Sink is closed"); output.flush() }
    override fun close() { if (!closed) { closed = true; if (ownsOutput) output.close() } }
}
fun ByteSource.asInputStream(ownsSource: Boolean = true): InputStream = object : InputStream() {
    private var closed = false
    override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) == -1) -1 else b[0].toInt() and 255 }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (closed) throw IOException("Stream is closed")
        return this@asInputStream.read(b, off, len)
    }
    override fun close() { if (!closed) { closed = true; if (ownsSource) this@asInputStream.close() } }
}
class FileSourceResource(private val file: File, private val content: ContentType = ContentType()) : SourceResource {
    override val metadata get() = StreamMetadata(content, file.name, file.length(), Finiteness.FINITE,
        setOf(Capability.READ, Capability.POSITIONAL_READ, Capability.SEEK, Capability.KNOWN_LENGTH, Capability.REOPEN),
        concurrency = Concurrency.POSITIONAL_READS, provenance = Provenance(source = file.toURI().toString()))
    override fun open(): ByteSource = FileSource(file, metadata)
}
private class FileSource(file: File, override val metadata: StreamMetadata) : ByteSource, PositionalReadable {
    private val channel: FileChannel = FileInputStream(file).channel
    private var position = 0L
    @Synchronized override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val n = readAt(position, buffer, offset, length); if (n > 0) position += n; return n
    }
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        requireRange(position, buffer, offset, length)
        if (length == 0) return 0
        return channel.read(ByteBuffer.wrap(buffer, offset, length), position)
    }
    override fun close() { channel.close() }
}
