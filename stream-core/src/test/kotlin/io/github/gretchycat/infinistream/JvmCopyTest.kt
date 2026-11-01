package io.github.gretchycat.infinistream

import java.io.*
import org.junit.Assert.*
import org.junit.Test

class JvmCopyTest {
    private class Input(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closes = 0
        override fun close() { closes++ }
        override fun read(buffer: ByteArray, offset: Int, length: Int) = super.read(buffer, offset, minOf(length, 3))
    }
    private class Output : ByteArrayOutputStream() {
        var closes = 0
        override fun close() { closes++ }
    }
    @Test fun borrowedStreamsRetainOwnershipAndShortReadsReportExactProgress() {
        val input = Input("abcdefgh".toByteArray())
        val output = Output()
        val progress = mutableListOf<Long>()
        assertEquals(8L, copy(input, output, progress = ProgressObserver { progress += it.completed }))
        assertEquals("abcdefgh", output.toString())
        assertEquals(listOf(3L, 6L, 8L), progress)
        assertEquals(0, input.closes)
        assertEquals(0, output.closes)
    }
    @Test fun zeroProgressFailsWithoutClosingBorrowedStreams() {
        val input = object : InputStream() {
            override fun read() = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int) = 0
        }
        val output = Output()
        assertThrows(IOException::class.java) { copy(input, output) }
        assertEquals(0, output.size())
        assertEquals(0, output.closes)
    }
    @Test fun cancelledCopyDoesNotWriteOrCloseTheCallersHandles() {
        val input = Input(byteArrayOf(1, 2))
        val output = Output()
        assertThrows(InterruptedIOException::class.java) { copy(input, output, CancellationToken { true }) }
        assertEquals(0, output.size())
        assertEquals(0, input.closes)
        assertEquals(0, output.closes)
    }
    @Test fun destinationFailurePropagatesAndLeavesOwnershipWithTheCaller() {
        val input = Input(byteArrayOf(1, 2))
        val output = object : OutputStream() { override fun write(value: Int) { throw IOException("full") } }
        assertEquals("full", assertThrows(IOException::class.java) { copy(input, output) }.message)
        assertEquals(0, input.closes)
    }
    @Test fun boundedMaterializationAcceptsExactAndEmptyInputs() {
        val input = Input("abcdef".toByteArray())
        assertArrayEquals("abcdef".toByteArray(), readBytes(input, 6))
        assertEquals(0, input.closes)
        assertArrayEquals(byteArrayOf(), readBytes(Input(byteArrayOf()), 0))
    }
    @Test fun limitsRejectOverflowInsteadOfReturningTruncatedSuccess() {
        val input = Input("abcdefg".toByteArray())
        val error = assertThrows(StreamLimitExceededException::class.java) { readBytes(input, 6) }
        assertEquals(6L, error.maxBytes)
        assertEquals(0, input.closes)
        assertThrows(StreamLimitExceededException::class.java) { readBytes(Input(byteArrayOf(1)), 0) }
        assertThrows(IllegalArgumentException::class.java) { readBytes(Input(byteArrayOf()), -1) }
    }
    @Test fun originalCompiledCopyEntryPointRemainsAvailable() {
        val method = Class.forName("io.github.gretchycat.infinistream.StreamsKt").getMethod("copy",
            ByteSource::class.java, ByteSink::class.java, CancellationToken::class.java,
            ProgressObserver::class.java, Int::class.javaPrimitiveType)
        assertEquals(java.lang.Long.TYPE, method.returnType)
    }
}
