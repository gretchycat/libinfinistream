package io.github.gretchycat.infinistream

import org.junit.Assert.*
import org.junit.Test
import java.io.*

class ProcessingTest {
    @Test fun wrappersAdvertiseDemonstratedCapabilitiesAndOwnership() {
        val hints = StreamMetadata(length = 10, capabilities = Capability.entries.toSet(), concurrency = Concurrency.POSITIONAL_READ_WRITE)
        val source = InputStreamSource(ByteArrayInputStream(byteArrayOf()), hints, ownsInput = false)
        assertEquals(setOf(Capability.READ, Capability.KNOWN_LENGTH), source.metadata.capabilities)
        assertEquals(Ownership.BORROWED, source.metadata.ownership)
        assertEquals(Concurrency.SERIAL, source.metadata.concurrency)
        val sink = OutputStreamSink(ByteArrayOutputStream(), hints, ownsOutput = false)
        assertEquals(setOf(Capability.WRITE, Capability.FLUSH), sink.metadata.capabilities)
        assertEquals(Ownership.BORROWED, sink.metadata.ownership)
        val positional = object : PositionalReadable { override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int) = -1 }
        val cursor = SourceCursor(positional, StreamMetadata(length = 10))
        assertEquals(setOf(Capability.READ, Capability.SEEK, Capability.KNOWN_LENGTH), cursor.metadata.capabilities)
    }
    private fun registration(id: String, from: DataKind, to: DataKind, closes: MutableList<String>, bad: Boolean = false): ProcessorRegistration {
        val processor = object : StreamProcessor {
            override val descriptor = ProcessorDescriptor(id, listOf(PortDescriptor("input", setOf(from))), listOf(PortDescriptor("output", setOf(to))))
            override fun open(inputs: Map<String, List<StreamHandle>>, options: ProcessorOptions): ProcessorSession {
                val output = object : StreamHandle {
                    override val metadata = StreamMetadata(ContentType(if (bad) from else to), attributes = mapOf("sampleRate" to "48000"))
                    override fun close() { closes += id }
                }
                return object : ProcessorSession {
                    override val outputs = mapOf("output" to listOf(output))
                    override fun close() = output.close()
                }
            }
        }
        return ProcessorRegistration(processor) { _, _ -> mapOf("output" to listOf(StreamMetadata(ContentType(to), attributes = mapOf("sampleRate" to "48000")))) }
    }
    @Test fun actualPullPipelineValidatesMetadataAndClosesInReverseOrder() {
        val closes = mutableListOf<String>()
        val registry = ProcessorRegistry(listOf(registration("decode", DataKind.BYTES, DataKind.PCM_AUDIO, closes), registration("events", DataKind.PCM_AUDIO, DataKind.EVENTS, closes)))
        val input = object : StreamHandle {
            override val metadata = StreamMetadata()
            override fun close() { closes += "input" }
        }
        val target = PortDescriptor("events", setOf(DataKind.EVENTS), attributes = mapOf("sampleRate" to "48000"))
        val session = registry.openPipeline(input, target)
        assertEquals(DataKind.EVENTS, session.outputs.getValue("events").single().metadata.content.kind)
        session.close(); session.close(); assertEquals(listOf("events", "decode"), closes)
        assertNull(registry.plan(input.metadata, target.copy(attributes = mapOf("sampleRate" to "96000"))))
    }
    @Test fun failingActualOutputClosesOpenedStagesAndNeverClosesBorrowedInput() {
        val closes = mutableListOf<String>()
        val registry = ProcessorRegistry(listOf(registration("first", DataKind.BYTES, DataKind.PCM_AUDIO, closes), registration("bad", DataKind.PCM_AUDIO, DataKind.EVENTS, closes, true)))
        val source = object : StreamHandle { override val metadata = StreamMetadata(); override fun close() { closes += "input" } }
        assertThrows(IOException::class.java) { registry.openPipeline(source, PortDescriptor("target", setOf(DataKind.EVENTS))) }
        assertEquals(listOf("bad", "first"), closes)
    }
    @Test fun compatiblePassthroughBorrowsTheOriginalAndInvalidEofIsAnError() {
        var closed = false
        val input = object : ByteSource {
            override val metadata = StreamMetadata()
            override fun read(buffer: ByteArray, offset: Int, length: Int) = -2
            override fun close() { closed = true }
        }
        ProcessorRegistry(emptyList()).openPipeline(input, PortDescriptor("bytes", setOf(DataKind.BYTES))).close()
        assertFalse(closed)
        assertThrows(IOException::class.java) { copy(input, OutputStreamSink(ByteArrayOutputStream())) }
    }
    @Test fun legacyInputStreamRoundTripPreservesMetadataWithoutInventingPositionalAccess() {
        val input = InputStreamSource("bytes".byteInputStream(), StreamMetadata(name = "original", length = 5,
            provenance = Provenance(source = "fixture://source"), warnings = listOf("unverified source")))
        val legacy = input.asInputStream(ownsSource = false)
        val restored = InputStreamSource(legacy, ownsInput = false)
        assertEquals("fixture://source", restored.metadata.provenance.source)
        assertEquals(listOf("unverified source"), restored.metadata.warnings)
        assertEquals(setOf(Capability.READ, Capability.KNOWN_LENGTH), restored.metadata.capabilities)
        assertEquals("bytes", restored.asInputStream(false).readBytes().decodeToString())
        restored.close(); input.close()
    }

}
