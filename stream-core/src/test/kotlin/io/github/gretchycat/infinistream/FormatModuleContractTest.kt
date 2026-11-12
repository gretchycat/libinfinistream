package io.github.gretchycat.infinistream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatModuleContractTest {
    @Test fun standardMethodsExposeDeclaredFormatFactsAndNormalizedIdentity() {
        val signature = MagicSignature("52494646", description = "RIFF")
        val handler = object : CapabilityHandler {
            override val descriptor = HandlerDescriptor(
                id = "test.video.mp4",
                library = "test-video",
                title = "MP4 video",
                formats = listOf(FormatSupport(setOf("video/mp4"), setOf("mp4"), magic = listOf(signature))),
                operations = setOf(Operations.IDENTIFY, Operations.PLAY, Operations.THUMBNAIL),
                metadata = setOf("duration", "width", "height"),
                attributes = mapOf("viewerTarget" to "infinimedia")
            )

            override fun probe(context: CapabilityProbeContext) = HandlerProbe(
                MatchConfidence.VERIFIED,
                setOf(Operations.IDENTIFY, Operations.PLAY, Operations.THUMBNAIL),
                "Container parsed",
                mapOf("mimeType" to "video/mp4")
            )
        }
        val resource = CapabilityResource(StreamMetadata(
            name = "trailer.MP4",
            content = ContentType(mimeType = "video/mp4")
        ))
        val normalized = handler.normalize(CapabilityProbeContext(resource))!!

        assertEquals(setOf("video/mp4"), handler.mimeTypes())
        assertEquals(setOf("mp4"), handler.extensions())
        assertEquals(listOf(signature), handler.magicSignatures())
        assertEquals("video/mp4", normalized.mimeType)
        assertEquals("mp4", normalized.extension)
        assertEquals(MatchConfidence.VERIFIED, normalized.confidence)
        assertEquals(ViewerTarget.INFINIMEDIA, normalized.viewerTarget)
        assertTrue(normalized.thumbnailSupported)
        assertTrue(handler.supportsThumbnail())
        assertEquals(setOf("duration", "width", "height"), handler.metadataFields())
        assertNull(handler.thumbnail(ThumbnailRequest(resource, 256)))
    }

    @Test fun viewerTargetsAcceptLegacyPresentationDuringModuleMigration() {
        assertEquals(ViewerTarget.INFINISCRIBE, ViewerTarget.from(mapOf("presentation" to "markdown")))
        assertEquals(ViewerTarget.INFINIMEDIA, ViewerTarget.from(mapOf("presentation" to "audio")))
        assertEquals(ViewerTarget.INFINIFOLDER, ViewerTarget.from(mapOf("presentation" to "image")))
        assertEquals(ViewerTarget.EXTERNAL, ViewerTarget.from(mapOf("viewerTarget" to "external")))
        assertNull(ViewerTarget.from(emptyMap()))
    }

    @Test fun normalizationRetainsCompoundExtensions() {
        val handler = object : CapabilityHandler {
            override val descriptor = HandlerDescriptor(
                "test.audio.cdda", "test-sound", "CDDA big-endian",
                listOf(FormatSupport(setOf("audio/x-cdda"), setOf("cdda", "cdda-be"))),
                setOf(Operations.IDENTIFY)
            )
        }
        val context = CapabilityProbeContext(CapabilityResource(StreamMetadata(
            name = "track.cdda-be",
            content = ContentType(mimeType = "application/octet-stream")
        )))

        assertEquals("cdda-be", handler.normalize(context)?.extension)
    }

    @Test fun extensionMagicAndMetadataMethodsAreIndependent() {
        val bytes = "ID3metadata".encodeToByteArray()
        var opens = 0
        val metadata = StreamMetadata(name = "track.bin")
        val source = object : SourceResource {
            override val metadata = metadata
            override fun open(): ByteSource {
                opens++
                return InputStreamSource(bytes.inputStream(), metadata)
            }
        }
        val handler = object : MetadataCapabilityHandler {
            override val descriptor = HandlerDescriptor(
                "test.audio.mp3", "test-audio", "MP3 audio",
                listOf(FormatSupport(setOf("audio/mpeg"), setOf("mp3"), magic = listOf(MagicSignature("494433")))),
                setOf(Operations.IDENTIFY, Operations.METADATA), metadata = setOf("header")
            )

            override fun extractMetadata(context: MetadataReadContext): MetadataRecord = MetadataRecord(
                descriptor.id,
                "audio/mpeg",
                fields = mapOf("header" to context.readAt(0, 3).decodeToString())
            )
        }

        // Metadata parsing is called directly, without a preceding extension or signature match.
        val resource = CapabilityResource(metadata, source = source)
        MetadataReadContext(resource).use { context ->
            assertEquals("ID3", handler.extractMetadata(context)?.fields?.get("header"))
        }
        assertEquals(1, opens)

        // Extension lookup sees only the supplied name, even if resource metadata disagrees.
        assertEquals("mp3", handler.detectExtensions("track.MP3").single().extension)
        assertTrue(handler.detectExtensions("track.bin").isEmpty())
        assertEquals(1, opens)

        // Byte validation sees only the bytes, even when the file extension is unrelated.
        assertEquals("mp3", handler.validateMagicBytes(bytes).single().format.extensions.single())
        assertTrue(handler.validateMagicBytes("nope".encodeToByteArray()).isEmpty())
        assertEquals(1, opens)
    }

    @Test fun aMagicMatchSelectsItsOwnFormatProfileWhenHintsAreMissing() {
        val handler = object : CapabilityHandler {
            override val descriptor = HandlerDescriptor(
                "test.multi-format", "test", "Multiple formats",
                listOf(
                    FormatSupport(setOf("application/x-alpha"), setOf("alpha"), magic = listOf(MagicSignature("414c5048"))),
                    FormatSupport(setOf("image/x-beta"), setOf("beta"), magic = listOf(MagicSignature("42455441")))
                ),
                setOf(Operations.IDENTIFY)
            )
        }
        val metadata = StreamMetadata(name = "unknown.bin")
        val source = object : SourceResource {
            override val metadata = metadata
            override fun open(): ByteSource = InputStreamSource(byteArrayOf(0x42, 0x45, 0x54, 0x41).inputStream(), metadata)
        }
        val result = handler.normalize(CapabilityProbeContext(CapabilityResource(metadata, source = source)))!!

        assertEquals(MatchConfidence.SIGNATURE, result.confidence)
        assertEquals("image/x-beta", result.mimeType)
        assertEquals(setOf("beta"), result.extensions)
    }

    @Test fun serviceAdaptersPreserveTheirDelegatesCommonMethods() {
        val descriptorFacts = HandlerDescriptor("test.metadata", "test", "Test metadata",
            listOf(FormatSupport(setOf("application/x-test"), setOf("test"))),
            setOf(Operations.IDENTIFY, Operations.METADATA), metadata = setOf("label"))
        val delegate = object : MetadataCapabilityHandler {
            override val descriptor = descriptorFacts
            override fun probe(context: CapabilityProbeContext) = HandlerProbe(
                MatchConfidence.VERIFIED, descriptorFacts.operations, "delegate parser", mapOf("mimeType" to "application/x-test"))
            override fun extractMetadata(context: MetadataReadContext) =
                MetadataRecord(descriptorFacts.id, "application/x-test", fields = mapOf("label" to "parsed"))
        }
        val wrapper = ServiceCapabilityHandler(descriptorFacts, delegate)
        val metadata = StreamMetadata(name = "sample.test")
        val source = object : SourceResource {
            override val metadata = metadata
            override fun open(): ByteSource = InputStreamSource(byteArrayOf(1).inputStream(), metadata)
        }
        val resource = CapabilityResource(metadata, source = source)

        assertEquals(MatchConfidence.VERIFIED, wrapper.normalize(CapabilityProbeContext(resource))?.confidence)
        MetadataReadContext(resource).use { context ->
            assertEquals("parsed", wrapper.extractMetadata(context)?.fields?.get("label"))
        }
    }

    @Test fun formatsWithoutDeclaredThumbnailsReturnNull() {
        val handler = object : CapabilityHandler {
            override val descriptor = HandlerDescriptor(
                "test.unknown", "test", "Unknown", listOf(FormatSupport(setOf("application/x-test"), setOf("test"))),
                setOf(Operations.IDENTIFY)
            )
        }
        val request = ThumbnailRequest(CapabilityResource(StreamMetadata(name = "sample.test")), 64)

        assertFalse(handler.supportsThumbnail())
        assertNull(handler.thumbnail(request))
        assertEquals(MatchConfidence.CANDIDATE,
            handler.normalize(CapabilityProbeContext(request.resource))?.confidence)
    }
}
