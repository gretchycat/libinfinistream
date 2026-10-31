package io.github.gretchycat.infinistream

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class SharedPositionalResourceTest {
    private open class Input : ByteSource, PositionalReadable {
        var closes = 0
        var metadataReads = 0
        override val metadata get() = StreamMetadata(name = "sample", length = 6,
            provenance = Provenance(source = "test:original"), warnings = listOf("source warning")).also { metadataReads++ }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = error("Shared views must use positional reads")
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            val count = minOf(length, 2, 6 - position.toInt())
            "abcdef".toByteArray().copyInto(buffer, offset, position.toInt(), position.toInt() + count)
            return count
        }
        override fun close() { closes++ }
    }
    @Test fun reopenedViewsHaveIndependentCursorsAndBorrowTheOwner() {
        val input = Input()
        SharedPositionalResource(input).use { resource ->
            val a = resource.open(); val b = resource.open()
            val bytes = ByteArray(2)
            a.read(bytes); assertEquals("ab", bytes.decodeToString())
            a.read(bytes); assertEquals("cd", bytes.decodeToString())
            b.read(bytes); assertEquals("ab", bytes.decodeToString())
            (a as PositionalReadable).readAt(0, bytes); assertEquals("ab", bytes.decodeToString())
            a.read(bytes); assertEquals("ef", bytes.decodeToString())
            a.close(); a.close(); assertEquals(0, input.closes)
            assertThrows(IOException::class.java) { a.read(bytes) }
            assertEquals(2, b.read(bytes)); b.close()
        }
        assertEquals(1, input.closes)
    }
    @Test fun actualLengthCapabilitiesAndMetadataComeFromOneSnapshot() {
        val input = Input()
        SharedPositionalResource(input).use { resource ->
            assertEquals(1, input.metadataReads)
            assertEquals(6L, resource.metadata.length)
            assertEquals("test:original", resource.metadata.provenance.source)
            assertEquals(listOf("source warning"), resource.metadata.warnings)
            assertTrue(Capability.REOPEN in resource.metadata.capabilities)
            resource.open().use { view ->
                assertTrue(view is PositionalReadable)
                assertEquals(Ownership.BORROWED, view.metadata.ownership)
                assertFalse(Capability.REOPEN in view.metadata.capabilities)
                assertFalse(Capability.WRITE in view.metadata.capabilities)
            }
            assertEquals(1, input.metadataReads)
        }
    }
    @Test fun eofShortReadsAndZeroLengthFollowTheContract() {
        SharedPositionalResource(Input()).use { resource ->
            resource.open().use { view ->
                val p = view as PositionalReadable
                assertEquals(1, p.readAt(5, ByteArray(10)))
                assertEquals(-1, p.readAt(1L shl 40, ByteArray(1)))
                assertEquals(0, p.readAt(Long.MAX_VALUE, ByteArray(0)))
                assertThrows(IOException::class.java) { p.readAt(-1, ByteArray(1)) }
                assertThrows(IOException::class.java) { p.readAt(Long.MAX_VALUE, ByteArray(1)) }
            }
        }
    }
    @Test fun unsupportedSourcesStayOwnedByTheCallerOnConstructionFailure() {
        val input = object : ByteSource {
            override val metadata = StreamMetadata(length = 6)
            var closed = false
            override fun read(buffer: ByteArray, offset: Int, length: Int) = -1
            override fun close() { closed = true }
        }
        assertThrows(IOException::class.java) { SharedPositionalResource(input) }
        assertFalse(input.closed)
        val unknown = object : Input() { override val metadata = StreamMetadata() }
        assertThrows(IOException::class.java) { SharedPositionalResource(unknown) }
        assertEquals(0, unknown.closes)
    }
    @Test fun invalidReadsAndErrorsAreNeverConvertedToEof() {
        for (result in listOf(-1, 0, 3)) {
            val input = object : Input() {
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int) = result
            }
            SharedPositionalResource(input).use { resource ->
                resource.open().use { view -> assertThrows(IOException::class.java) { view.read(ByteArray(2)) } }
            }
        }
        val input = object : Input() {
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int = throw IOException("network error")
        }
        SharedPositionalResource(input).use { resource -> resource.open().use { view ->
            assertEquals("network error", assertThrows(IOException::class.java) { view.read(ByteArray(1)) }.message)
        } }
    }
    @Test fun ownerCloseInvalidatesAllViewsAndIsIdempotent() {
        val input = Input(); val owner = SharedPositionalResource(input); val view = owner.open()
        owner.close(); owner.close(); view.close()
        assertEquals(1, input.closes)
        assertThrows(IOException::class.java) { owner.open() }
        assertThrows(IOException::class.java) { view.read(ByteArray(1)) }
    }
    @Test fun interruptionDoesNotCloseTheOwnerOrConsumeBytes() {
        SharedPositionalResource(Input()).use { owner ->
            val view = owner.open()
            Thread.currentThread().interrupt()
            try { assertThrows(InterruptedIOException::class.java) { view.read(ByteArray(1)) } }
            finally { Thread.interrupted() }
            val bytes = ByteArray(1); assertEquals(1, view.read(bytes)); assertEquals('a'.code.toByte(), bytes[0])
            view.close()
        }
    }
    @Test fun providerReadsAreSerializedAcrossViews() {
        val active = AtomicInteger(); val maximum = AtomicInteger()
        val input = object : Input() {
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                val count = active.incrementAndGet(); maximum.accumulateAndGet(count, ::maxOf)
                try { Thread.yield(); return super.readAt(position, buffer, offset, length) }
                finally { active.decrementAndGet() }
            }
        }
        SharedPositionalResource(input).use { owner ->
            val executor = Executors.newFixedThreadPool(4)
            try { (0 until 20).map { executor.submit { owner.open().use { view -> repeat(100) { (view as PositionalReadable).readAt(1, ByteArray(1)) } } } }.forEach { it.get(5, TimeUnit.SECONDS) } }
            finally { executor.shutdownNow() }
        }
        assertEquals(1, maximum.get())
    }
}
