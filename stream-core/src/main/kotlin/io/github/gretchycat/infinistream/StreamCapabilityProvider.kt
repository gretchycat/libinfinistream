package io.github.gretchycat.infinistream

interface ByteTransferService {
    fun transfer(source:ByteSource,target:ByteSink,cancellation:CancellationToken=CancellationToken.NONE):Long
    fun materialize(source:ByteSource,maxBytes:Int):ByteArray
}
class StreamCapabilityProvider : CapabilityProvider {
    override val library="stream"
    override fun handlers():List<CapabilityHandler> = listOf(object:CapabilityHandler,ByteTransferService {
        override val descriptor=HandlerDescriptor("stream.bytes",library,"Bounded byte transfers",emptyList(),setOf("copy","materialize"),
            metadata=setOf("length","capabilities","progress"),limitations=listOf("Borrowed endpoints; caller owns publication, flushing and closure", "Materialization requires an explicit byte budget"))
        override fun probe(context:CapabilityProbeContext)=HandlerProbe(
            if(Capability.READ in context.resource.metadata.capabilities)MatchConfidence.VERIFIED else MatchConfidence.NONE,descriptor.operations)
        override fun transfer(source:ByteSource,target:ByteSink,cancellation:CancellationToken)=copy(source,target,cancellation)
        override fun materialize(source:ByteSource,maxBytes:Int)=readBytes(source,maxBytes)
    })
}
