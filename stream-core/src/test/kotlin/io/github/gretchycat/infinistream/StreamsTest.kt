package io.github.gretchycat.infinistream

import org.junit.Assert.*
import org.junit.Test
import java.io.*

class StreamsTest {
    private fun metadata(format: String = "raw", caps: Set<Capability> = setOf(Capability.READ)) =
        StreamMetadata(ContentType(format = format), capabilities = caps)
    private class Bytes(val bytes: ByteArray) : PositionalReadable {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            requireRange(position, buffer, offset, length)
            if (length == 0) return 0
            if (position >= bytes.size) return -1
            val n = minOf(length, 2, bytes.size - position.toInt())
            bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + n); return n
        }
    }
    @Test fun independentCursorsHandleShortReadsAndEof() {
        val bytes = Bytes("abcdef".toByteArray())
        val a = SourceCursor(bytes, metadata()); val b = SourceCursor(bytes, metadata())
        a.seek(3); assertEquals("def", a.asInputStream(false).readBytes().decodeToString())
        assertEquals("abcdef", b.asInputStream().readBytes().decodeToString())
        assertEquals(-1, a.read(ByteArray(1))); assertEquals(0, a.read(ByteArray(0)))
    }
    @Test fun readsAtHugeOffsetsNeverWrap() {
        val bytes = Bytes(byteArrayOf(4))
        assertEquals(-1, bytes.readAt(1L shl 40, ByteArray(1)))
        assertThrows(IOException::class.java) { bytes.readAt(Long.MAX_VALUE, ByteArray(2)) }
        assertThrows(IOException::class.java) { bytes.readAt(-1, ByteArray(1)) }
        assertThrows(IOException::class.java) { bytes.readAt(0, ByteArray(1), 1, 1) }
    }
    @Test fun cursorOwnershipIsExplicitAndCloseIsIdempotent() {
        var closed = 0
        val bytes = Bytes(byteArrayOf(1)); val owner = Closeable { closed++ }
        SourceCursor(bytes, metadata()).close(); assertEquals(0, closed)
        val owned = SourceCursor(bytes, metadata(), owner)
        owned.close(); owned.close(); assertEquals(1, closed)
        assertThrows(IOException::class.java) { owned.read(ByteArray(1)) }
    }
    @Test fun unknownLengthSurvivesSequentialCopyAndCancellation() {
        val source = InputStreamSource(ByteArrayInputStream("abc".toByteArray()))
        val output = ByteArrayOutputStream(); val progress = mutableListOf<StreamProgress>()
        assertEquals(3L, copy(source, OutputStreamSink(output), progress = ProgressObserver { progress.add(it) }, bufferSize = 1))
        assertNull(progress.last().total); assertEquals("abc", output.toString())
        assertThrows(InterruptedIOException::class.java) {
            copy(InputStreamSource(ByteArrayInputStream(byteArrayOf(1))), OutputStreamSink(output), CancellationToken { true })
        }
    }
    @Test fun errorsPropagateAndZeroProgressIsRejected() {
        val failed = object : InputStream() { override fun read(): Int = throw IOException("network failed") }
        assertEquals("network failed", assertThrows(IOException::class.java) { InputStreamSource(failed).read(ByteArray(1)) }.message)
        val stalled = object : InputStream() { override fun read() = 0; override fun read(b: ByteArray, o: Int, n: Int) = 0 }
        assertThrows(IOException::class.java) { InputStreamSource(stalled).read(ByteArray(1)) }
    }
    @Test fun probingIsBoundedAndDoesNotMoveACursor() {
        val bytes = Bytes("xxfLaCpayload".toByteArray()); val cursor = SourceCursor(bytes, metadata())
        val detector = FormatDetector(listOf(SignatureProbe("fLaC".toByteArray(), ContentType(format = "flac"), 2)))
        val detected = detector.detect(ProbeReader(bytes, metadata(), ProbeBudget(maxNanos = Long.MAX_VALUE)))
        assertEquals("flac", detected.single().content.format); assertEquals(0L, cursor.position)
        assertThrows(IOException::class.java) { ProbeReader(bytes, metadata(), ProbeBudget(maxBytes = 1)).bytes(0, 2) }
        assertThrows(IllegalArgumentException::class.java) { ProbeReader(bytes, metadata(), depth = 9) }
    }
    @Test fun ambiguousMagicRemainsMultipleCandidatesAndHeaderlessIsUnknown() {
        val bytes = Bytes("ABCD".toByteArray())
        val signatures = listOf("one", "two").map { SignatureProbe("AB".toByteArray(), ContentType(format = it)) }
        assertEquals(2, FormatDetector(signatures).detect(ProbeReader(bytes, metadata())).size)
        assertTrue(FormatDetector(signatures).detect(ProbeReader(Bytes("raw".toByteArray()), metadata())).isEmpty())
    }
    @Test fun sequentialProbingReplaysExactlyAndDoesNotInventSeekability() {
        ReplayableSource(InputStreamSource(ByteArrayInputStream("abcdef".toByteArray())), 4).use {
            assertEquals("abc", it.peek(3).decodeToString()); assertEquals("abcd", it.peek(4).decodeToString())
            assertEquals("abcdef", it.asInputStream(false).readBytes().decodeToString())
            assertFalse(Capability.POSITIONAL_READ in it.metadata.capabilities)
        }
    }
    @Test fun sparseLargeFileReadsArePositionalAndLazy() {
        val file = File.createTempFile("infinistream", ".data")
        try {
            RandomAccessFile(file, "rw").use { it.setLength(5L shl 30); it.seek(4L shl 30); it.write(42) }
            FileSourceResource(file).open().use {
                val b = ByteArray(1); assertEquals(1, (it as PositionalReadable).readAt(4L shl 30, b)); assertEquals(42, b[0].toInt())
                assertEquals(5L shl 30, it.metadata.length)
            }
        } finally { file.delete() }
    }
    @Test fun pushHasOneChunkInFlightAndHonorsCancellation() {
        val events = mutableListOf<String>(); var cancelled = false
        val input = object : InputStream() {
            private var left = 3
            override fun read(): Int { events += "read"; return if (left-- > 0) 1 else -1 }
        }
        assertThrows(InterruptedIOException::class.java) {
            push(InputStreamSource(input), ChunkConsumer { _, _, _ -> events += "consume"; cancelled = true }, CancellationToken { cancelled }, 1)
        }
        assertEquals(listOf("read", "consume"), events)
    }
    private fun processor(id: String, input: PortDescriptor, output: PortDescriptor) = object : StreamProcessor {
        override val descriptor = ProcessorDescriptor(id, listOf(input), listOf(output))
        override fun open(inputs: Map<String, List<StreamHandle>>, options: ProcessorOptions): ProcessorSession = error("Planning must be lazy")
    }
    @Test fun plannerMatchesCapabilitiesAndDerivesActualOutputMetadata() {
        val decoder = processor("decode", PortDescriptor("input", setOf(DataKind.BYTES), setOf("flac"), setOf(Capability.POSITIONAL_READ)), PortDescriptor("pcm", setOf(DataKind.PCM_AUDIO)))
        val registry = ProcessorRegistry(listOf(ProcessorRegistration(decoder) { _, _ -> mapOf("pcm" to listOf(
            StreamMetadata(ContentType(DataKind.PCM_AUDIO, format = "pcm-s32"), capabilities = setOf(Capability.READ), attributes = mapOf("sampleRate" to "48000", "channels" to "2")))) }))
        val target = PortDescriptor("output", setOf(DataKind.PCM_AUDIO))
        assertNull(registry.plan(metadata("flac"), target))
        val input = metadata("flac", setOf(Capability.READ, Capability.POSITIONAL_READ))
        val path = registry.plan(input, target)!!
        assertEquals("48000", path.single().outputs.getValue("pcm").single().attributes["sampleRate"])
        assertFalse(Capability.POSITIONAL_READ in path.single().outputs.getValue("pcm").single().capabilities)
        assertNull(registry.plan(input, target.copy(capabilities = setOf(Capability.POSITIONAL_READ))))
        assertNull(registry.plan(input, target, allowedBackends = emptySet()))
    }
    @Test fun registryDescribesZipAndMixerWithoutForcingBytes() {
        val zip = ProcessorDescriptor("zip", listOf(PortDescriptor("archive", setOf(DataKind.BYTES))),
            listOf(PortDescriptor("entries", setOf(DataKind.DIRECTORY), multiple = true)))
        val mixer = ProcessorDescriptor("mixer", listOf(PortDescriptor("tracks", setOf(DataKind.PCM_AUDIO), multiple = true)),
            listOf(PortDescriptor("mix", setOf(DataKind.PCM_AUDIO))))
        assertTrue(zip.outputs.single().multiple); assertTrue(mixer.inputs.single().multiple)
        val update = StreamEvent.MetadataUpdated("mix", StreamMetadata(ContentType(DataKind.PCM_AUDIO), revision = 1))
        assertEquals(1L, update.metadata.revision)
    }
    @Test fun plannerBoundsCyclesAndDoesNotOpenProcessing() {
        val port = PortDescriptor("data", setOf(DataKind.BYTES))
        val loop = processor("loop", port, port)
        val registry = ProcessorRegistry(listOf(ProcessorRegistration(loop) { inputs, _ -> inputs }))
        assertNull(registry.plan(metadata(), PortDescriptor("target", setOf(DataKind.TEXT)), maxStates = 2))
    }
}
