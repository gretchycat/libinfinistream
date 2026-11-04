package io.github.gretchycat.infinistream

import java.io.Closeable
import java.io.IOException

/** Extract encoded image content without depending on Android, Canvas or an application. */
interface EncodedImageExtractor : CapabilityHandler {
    override fun describe()=descriptor.copy(attributes=descriptor.attributes+("encodedImageExtractor" to "true"))
    /** Borrow input; caller closes all result readers before closing the owned result. Null means no image. */
    fun extract(source:ByteSource,targetPixels:Int,cancellation:CancellationToken=CancellationToken.NONE):ExtractedImage?
}
class ExtractedImage(val resource:SourceResource,private val release:()->Unit={}) : Closeable {
    private var closed=false
    @Synchronized override fun close(){if(!closed){closed=true;release()}}
    companion object {
        /** Takes ownership of a newly produced byte array. No implicit source download or conversion. */
        fun ownedBytes(bytes:ByteArray,mimeType:String?=null,input:StreamMetadata=StreamMetadata()):ExtractedImage {
            var content:ByteArray?=bytes
            val resource=object:SourceResource {
                override val metadata=StreamMetadata(ContentType(mimeType=mimeType),name="extracted-image",length=bytes.size.toLong(),
                    capabilities=setOf(Capability.READ,Capability.KNOWN_LENGTH,Capability.REOPEN),
                    provenance=input.provenance.copy(transformations=input.provenance.transformations+"image.extract"))
                override fun open():ByteSource=InputStreamSource((content?:throw IOException("Extracted image is closed")).inputStream(),metadata)
            }
            return ExtractedImage(resource){content=null}
        }
    }
}
