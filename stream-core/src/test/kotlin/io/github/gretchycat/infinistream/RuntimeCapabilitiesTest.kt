package io.github.gretchycat.infinistream

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

fun interface TextConversion { fun convert(source:ByteSource):String }
class TestTextProvider : CapabilityProvider {
    override val library="test-text"
    override fun handlers():List<CapabilityHandler> = listOf(ServiceCapabilityHandler(
        HandlerDescriptor("test.uppercase",library,"Uppercase",listOf(FormatSupport(setOf("text/x-upper"),setOf("upper"))),setOf(Operations.DECODE)),
        TextConversion { input -> readBytes(input,1024).decodeToString().uppercase(java.util.Locale.ROOT) }))
}
class RuntimeCapabilitiesTest {
    private fun source(bytes:ByteArray,name:String="value.upper")=object:SourceResource {
        override val metadata=StreamMetadata(name=name,length=bytes.size.toLong(),capabilities=setOf(Capability.READ))
        override fun open():ByteSource=InputStreamSource(bytes.inputStream(),metadata)
    }
    @Test fun serviceLoaderFindsAndExecutesARealTransformationWithoutAHost() {
        val registry=RuntimeCapabilities.discover()
        val input=source("hello capability".toByteArray())
        val selected=registry.probe(CapabilityResource(input.metadata,source=input),Operations.DECODE)
            .single { it.descriptor.id=="test.uppercase" }
        input.open().use { assertEquals("HELLO CAPABILITY",selected.handler.service(TextConversion::class.java)!!.convert(it)) }
    }
    @Test fun registrationOverlapAndRemovalPreserveBothHandlersAndCallbacks() {
        val registry=CapabilityRegistry("test-text")
        val events=mutableListOf<Long>()
        registry.subscribe { events+=it }.use {
            registry.register(TestTextProvider().handlers().single()).use {
                val second=ServiceCapabilityHandler(HandlerDescriptor("test.reverse","test-text","Reverse",
                    listOf(FormatSupport(setOf("text/x-upper"),setOf("upper"))),setOf(Operations.DECODE)),
                    TextConversion { input -> readBytes(input,1024).decodeToString().reversed() })
                registry.register(second).use {
                    val found=registry.probe(CapabilityResource(StreamMetadata(name="value.upper")),Operations.DECODE)
                    assertEquals(2,found.size)
                    val bytes=source("abc".toByteArray())
                    bytes.open().use { assertEquals("cba",found.single { it.descriptor.id=="test.reverse" }.handler.service(TextConversion::class.java)!!.convert(it)) }
                }
                assertEquals(1,registry.query().size)
            }
        }
        assertTrue(registry.query().isEmpty());assertEquals(listOf(1L,2L,3L,4L),events)
    }
    @Test fun missingDependenciesAreReportedAndRefreshChangesAvailability() {
        val isolated=java.net.URLClassLoader(emptyArray(),null)
        var loader:ClassLoader=isolated
        val dependency=TestTextProvider::class.java.name
        val handler=ServiceCapabilityHandler(HandlerDescriptor("optional","test","Optional",emptyList(),setOf(Operations.DECODE)),
            TextConversion { input ->readBytes(input,1024).decodeToString() },runtime={
                Class.forName(dependency,false,loader)
                HandlerAvailability(dependencies=mapOf(dependency to true))
            })
        val registry=CapabilityRegistry();registry.register(handler)
        assertTrue(registry.query().isEmpty())
        val unavailable=registry.query(includeUnavailable=true).single().availability
        assertEquals(AvailabilityState.UNAVAILABLE,unavailable.state)
        assertTrue(unavailable.reason!!.contains(dependency))
        loader=TestTextProvider::class.java.classLoader
        assertTrue(registry.query().isEmpty()) // Cached until the configuration refresh.
        registry.refresh();assertEquals(1,registry.query().size)
        assertEquals(true,registry.query().single().availability.dependencies[dependency])
        source("actual backend".toByteArray()).open().use {
            assertEquals("actual backend",registry.query().single().handler.service(TextConversion::class.java)!!.convert(it))
        }
        isolated.close()
    }
    @Test fun sharedPrefixIsReadOnceAndReadonlyResourcesNeverOfferMutation() {
        var opened=0;var read=0
        val registry=CapabilityRegistry()
        repeat(2) { n -> registry.register(object:CapabilityHandler {
            override val descriptor=HandlerDescriptor("prefix.$n","test","Signature",listOf(FormatSupport(magic=listOf(MagicSignature("4142")))),setOf(Operations.READ,Operations.WRITE))
        }) }
        val resource=object:SourceResource {
            override val metadata=StreamMetadata(capabilities=setOf(Capability.READ))
            override fun open():ByteSource {
                opened++
                val input="ABCDE".byteInputStream()
                return object:ByteSource {
                    override val metadata=resourceMetadata()
                    override fun read(buffer:ByteArray,offset:Int,length:Int):Int=input.read(buffer,offset,length).also { if(it>0)read+=it }
                    override fun close()=input.close()
                }
            }
            fun resourceMetadata()=metadata
        }
        val found=registry.probe(CapabilityResource(resource.metadata,source=resource),budget=ProbeBudget(maxBytes=3,maxNanos=1_000_000_000))
        assertEquals(2,found.size);assertEquals(1,opened);assertEquals(3,read)
        assertTrue(found.all { it.probe!!.confidence==MatchConfidence.SIGNATURE && Operations.WRITE !in it.operations })
    }
    @Test fun nestedDepthAndCancellationAreEnforcedBeforeReading() {
        val registry=CapabilityRegistry()
        assertThrows(IllegalArgumentException::class.java) { registry.probe(CapabilityResource(StreamMetadata(),containerDepth=2),budget=ProbeBudget(maxDepth=1)) }
        registry.register(TestTextProvider().handlers().single())
        assertThrows(java.io.InterruptedIOException::class.java) {
            registry.probe(CapabilityResource(StreamMetadata(name="x.upper")),cancellation=CancellationToken {true})
        }
    }
    @Test fun failedSourcesAreNotReopenedForEveryHandler() {
        var opened=0
        val source=object:SourceResource {
            override val metadata=StreamMetadata()
            override fun open():ByteSource {opened++;throw java.io.IOException("Denied")}
        }
        val context=CapabilityProbeContext(CapabilityResource(source.metadata,source=source))
        repeat(3){assertThrows(java.io.IOException::class.java){context.prefix()}}
        assertEquals(1,opened)
    }
    @Test fun fingerprintIncludesPriorityAndFieldBoundaries() {
        val descriptor=TestTextProvider().handlers().single().descriptor
        fun hash(d:HandlerDescriptor)=CapabilityRegistry().also {it.register(ServiceCapabilityHandler(d,Any()))}.fingerprint()
        assertNotEquals(hash(descriptor),hash(descriptor.copy(priority=1)))
        assertNotEquals(hash(descriptor.copy(operations=setOf("read"),metadata=setOf("write"))),
            hash(descriptor.copy(operations=setOf("read","write"),metadata=emptySet())))
        assertEquals(hash(descriptor),hash(descriptor))
    }

}
