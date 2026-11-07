package io.github.gretchycat.infinistream

/** Dependency-free schema export. Build-time identity snapshots never claim runtime availability. */
object CapabilityJson {
    fun snapshot(registry:CapabilityRegistry,evaluateAvailability:Boolean=true):String {
        val entries=if(evaluateAvailability)registry.query(includeUnavailable=true).associateBy{it.descriptor.id}else emptyMap()
        val handlers=registry.descriptors().sortedBy{it.id}.map { d ->
            val availability=entries[d.id]?.availability
            mapOf("id" to d.id,"library" to d.library,"title" to d.title,
                "operations" to d.operations.sorted(),"metadata" to d.metadata.sorted(),"limitations" to d.limitations,
                "priority" to d.priority,"attributes" to d.attributes.toSortedMap(),
                "formats" to d.formats.map { f ->mapOf("mimeTypes" to f.mimeTypes.sorted(),"extensions" to f.extensions.sorted(),
                    "uriSchemes" to f.uriSchemes.sorted(),"magic" to f.magic.map { m ->mapOf("hex" to m.hex,"offset" to m.offset,"description" to m.description) }) },
                "availability" to mapOf("state" to (availability?.state?.name?:"NOT_EVALUATED"),"reason" to availability?.reason,
                    "dependencies" to availability?.dependencies.orEmpty()))
        }
        val supportedFormats=registry.supportedFormats().map { item ->
            val format=item.format
            mapOf("handlerId" to item.handlerId,"library" to item.library,"title" to item.title,
                "operations" to item.operations.sorted(),"attributes" to item.attributes.toSortedMap(),
                "profileIndex" to item.profileIndex,"mimeTypes" to format.mimeTypes.sorted(),
                "extensions" to format.extensions.sorted(),"uriSchemes" to format.uriSchemes.sorted(),
                "magic" to format.magic.map { m ->mapOf("hex" to m.hex,"offset" to m.offset,"description" to m.description) })
        }
        return json(mapOf("schemaVersion" to 1,"fingerprint" to registry.fingerprint(),"handlers" to handlers,
            "supportedFormats" to supportedFormats,
            "failures" to registry.discoveryFailures.map{mapOf("provider" to it.provider,"reason" to it.reason)}))
    }
    private fun json(value:Any?):String=when(value){
        null->"null"
        is Boolean,is Number->value.toString()
        is String->buildString {
            append('"');value.forEach { c ->when(c){
                '"'->append("\\\"");'\\'->append("\\\\");'\n'->append("\\n");'\r'->append("\\r");'\t'->append("\\t")
                else->if(c<' '||c.isSurrogate())append("\\u%04x".format(c.code))else append(c)
            } };append('"')
        }
        is Map<*,*>->value.entries.joinToString(",","{","}"){json(it.key.toString())+":"+json(it.value)}
        is Iterable<*>->value.joinToString(",","[","]"){json(it)}
        else->throw IllegalArgumentException("Unsupported capability JSON value")
    }
}

/** Can run on a standalone library's classpath; no Android application is initialized. */
object CapabilityCatalogMain {
    @JvmStatic fun main(args:Array<String>) {
        require(args.size==1){"Pass the output JSON filename"}
        val registry=RuntimeCapabilities.installed()
        val file=java.io.File(args.single());file.parentFile?.mkdirs()
        file.writeText(CapabilityJson.snapshot(registry,evaluateAvailability=false))
        check(registry.discoveryFailures.isEmpty()) {"Capability provider registration failed: ${registry.discoveryFailures}"}
        println("Capability catalog: ${registry.descriptors().size} handlers; ${registry.fingerprint()}")
    }
}
