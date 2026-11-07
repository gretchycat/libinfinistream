package io.github.gretchycat.infinistream

import java.io.Closeable
import java.io.IOException
import java.util.Locale
import java.util.ServiceLoader
import java.util.concurrent.CopyOnWriteArrayList

/** Open operation names: extensions do not need to change this library's enum. */
object Operations {
    const val DECODE = "decode"
    const val ENCODE = "encode"
    const val VIEW = "view"
    const val EDIT = "edit"
    const val THUMBNAIL = "thumbnail"
    const val IDENTIFY = "identify"
    const val METADATA = "metadata"
    const val LIST = "list"
    const val EXTRACT = "extract"
    const val READ = "read"
    const val WRITE = "write"
    const val REFORMAT = "reformat"
    const val PLAY = "play"
}

/** Immutable, serializable format facts; a signature match is not parser validation. */
data class MagicSignature(val hex: String, val offset: Long = 0, val description: String = "") {
    private val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    init { require(hex.isNotEmpty() && hex.length % 2 == 0 && hex.all { it.digitToIntOrNull(16) != null }); require(offset >= 0) }
    fun matches(prefix: ByteArray): Boolean = offset <= prefix.size && bytes.size <= prefix.size - offset &&
        bytes.indices.all { prefix[offset.toInt() + it] == bytes[it] }
}

data class FormatSupport(
    val mimeTypes: Set<String> = emptySet(), val extensions: Set<String> = emptySet(),
    val uriSchemes: Set<String> = emptySet(), val magic: List<MagicSignature> = emptyList()
) {
    fun score(resource: CapabilityResource): Int {
        val mime = resource.metadata.content.mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val name = resource.metadata.name.orEmpty().lowercase(Locale.ROOT)
        val scheme = resource.uri?.substringBefore(':', "")?.lowercase(Locale.ROOT)
        return when {
            mime in mimeTypes -> 40
            mimeTypes.any { it.endsWith("/*") && mime.startsWith(it.dropLast(1)) } -> 35
            extensions.any { name.endsWith(".$it") || name == it } -> 30
            scheme != null && scheme in uriSchemes -> 20
            else -> 0
        }
    }
}

data class HandlerDescriptor(
    val id: String, val library: String, val title: String,
    val formats: List<FormatSupport>, val operations: Set<String>,
    val metadata: Set<String> = emptySet(), val limitations: List<String> = emptyList(),
    val priority: Int = 0, val attributes: Map<String, String> = emptyMap()
) {
    init { require(id.isNotBlank() && library.isNotBlank()); require(operations.isNotEmpty()) }
}

enum class AvailabilityState { AVAILABLE, CONDITIONAL, UNAVAILABLE }
data class HandlerAvailability(val state: AvailabilityState = AvailabilityState.AVAILABLE,
    val reason: String? = null, val dependencies: Map<String, Boolean> = emptyMap()) {
    init { require(state != AvailabilityState.UNAVAILABLE || !reason.isNullOrBlank()) }
}
enum class MatchConfidence { NONE, CANDIDATE, SIGNATURE, VERIFIED }
data class HandlerProbe(val confidence: MatchConfidence, val operations: Set<String>,
    val reason: String? = null, val metadata: Map<String, String> = emptyMap())

/** Metadata is a hint. Source handles are opened/closed only during explicit probing. */
data class CapabilityResource(val metadata: StreamMetadata, val uri: String? = metadata.provenance.source,
    val source: SourceResource? = null, val containerDepth: Int = 0) {
    init { require(containerDepth >= 0) }
}

/** One shared bounded prefix per query; no hidden spool, target traversal or network discovery. */
class CapabilityProbeContext(val resource: CapabilityResource, val budget: ProbeBudget = ProbeBudget(),
    val cancellation: CancellationToken = CancellationToken.NONE) {
    private var loaded: ByteArray? = null
    private var failed: IOException? = null
    fun prefix(): ByteArray {
        cancellation.check()
        failed?.let {throw it}
        loaded?.let { return it.copyOf() }
        if (resource.containerDepth > budget.maxDepth) throw IOException("Capability probe nesting limit exceeded")
        val source = resource.source ?: return ByteArray(0)
        if (budget.maxProbes == 0) throw IOException("Capability probe budget exceeded")
        val start = System.nanoTime()
        val result = try {source.open().use { input ->
            val bytes = ByteArray(budget.maxBytes)
            var count = 0
            while (count < bytes.size) {
                cancellation.check()
                if (System.nanoTime() - start > budget.maxNanos) throw IOException("Capability probe time limit exceeded")
                val n = input.read(bytes, count, bytes.size - count)
                if (n < 0) break
                if (n <= 0 || n > bytes.size - count) throw IOException("Invalid capability probe read")
                count += n
            }
            if (System.nanoTime() - start > budget.maxNanos) throw IOException("Capability probe time limit exceeded")
            bytes.copyOf(count)
        }}catch(e:IOException){failed=e;throw e}
        loaded = result
        return result.copyOf()
    }
}

/** Services are typed library APIs, never an application singleton or opaque success stub. */
interface CapabilityHandler {
    val descriptor: HandlerDescriptor
    /** Dynamic composites may resolve delegated formats without changing stable identity. */
    fun describe(): HandlerDescriptor = descriptor
    fun availability(): HandlerAvailability = HandlerAvailability()
    fun probe(context: CapabilityProbeContext): HandlerProbe {
        val score = descriptor.formats.maxOfOrNull { it.score(context.resource) } ?: 0
        val signatures = descriptor.formats.flatMap { it.magic }
        val signature = signatures.isNotEmpty() && context.resource.source != null && signatures.any { it.matches(context.prefix()) }
        return HandlerProbe(if (signature) MatchConfidence.SIGNATURE else if (score > 0) MatchConfidence.CANDIDATE else MatchConfidence.NONE,
            descriptor.operations)
    }
    fun <T : Any> service(type: Class<T>): T? = if (type.isInstance(this)) type.cast(this) else null
}

/** Providers must be cheap to construct; no context, network, native initialization or app required. */
interface CapabilityProvider {
    val library: String
    fun handlers(): List<CapabilityHandler>
}

/** Compatibility adapter for existing typed library objects; the delegate remains executable. */
open class ServiceCapabilityHandler(override val descriptor: HandlerDescriptor, private val delegate: Any,
    private val runtime: () -> HandlerAvailability = { HandlerAvailability() }) : CapabilityHandler {
    override fun availability() = runtime()
    override fun <T : Any> service(type: Class<T>): T? = if(type.isInstance(delegate)) type.cast(delegate) else super.service(type)
}

/** Platform services are constructed on invocation, not while enumerating metadata. */
class LazyServiceCapabilityHandler(override val descriptor:HandlerDescriptor,create:()->Any):CapabilityHandler {
    private val delegate by lazy(create)
    override fun <T:Any> service(type:Class<T>):T?=if(type.isInstance(delegate))type.cast(delegate)else super.service(type)
}

data class DiscoveredCapability(val handler: CapabilityHandler, val availability: HandlerAvailability,
    val probe: HandlerProbe? = null) {
    val descriptor get() = handler.describe()
    /** Source mutation needs a writable source. EDIT/ENCODE may instead produce a separate output. */
    val operations get() = if (availability.state == AvailabilityState.UNAVAILABLE) emptySet() else probe?.operations ?: descriptor.operations
}

/** One advertised format profile with its owning handler retained for complete inventories. */
data class SupportedFormatCapability(
    val handlerId: String,
    val library: String,
    val title: String,
    val operations: Set<String>,
    val attributes: Map<String, String>,
    val profileIndex: Int,
    val format: FormatSupport
)

data class DiscoveryFailure(val provider: String, val reason: String)

/** Order is deterministic; overlaps are retained. Mutation callbacks run outside the registry lock. */
class CapabilityRegistry(val library: String? = null, private val parent: CapabilityRegistry? = null) {
    private val handlers = linkedMapOf<String, CapabilityHandler>()
    private val listeners = CopyOnWriteArrayList<(Long) -> Unit>()
    @Volatile private var localRevision: Long = 0
    val revision: Long get() = parent?.revision ?: localRevision
    private val failures = CopyOnWriteArrayList<DiscoveryFailure>()
    private val availabilityCache = java.util.concurrent.ConcurrentHashMap<CapabilityHandler,Pair<Long,HandlerAvailability>>()
    @Volatile private var fingerprintCache:Pair<Long,String>?=null
    val discoveryFailures: List<DiscoveryFailure> get() = parent?.discoveryFailures ?: failures.toList()
    private fun snapshot(): List<CapabilityHandler> = (parent?.snapshot() ?: synchronized(this) { handlers.values.toList() }).filter { library == null || it.descriptor.library == library }
    /** Identity facts only; unlike query(), this does not evaluate runtime availability. */
    fun descriptors(): List<HandlerDescriptor> = snapshot().map { it.describe() }
    /**
     * Enumerates every declared format profile, retaining overlapping handlers.
     * A library-scoped registry returns that library's profiles; the installed
     * registry returns the complete linked catalog.
     */
    fun supportedFormats(operation: String? = null): List<SupportedFormatCapability> =
        descriptors().flatMap { descriptor ->
            if (operation != null && operation !in descriptor.operations) emptyList()
            else descriptor.formats.mapIndexed { index, format ->
                SupportedFormatCapability(descriptor.id, descriptor.library, descriptor.title,
                    descriptor.operations, descriptor.attributes, index, format)
            }
        }.sortedWith(compareBy<SupportedFormatCapability> { it.library }
            .thenBy { it.handlerId }.thenBy { it.profileIndex })
    fun supportedMimeTypes(operation: String? = null): Set<String> =
        supportedFormats(operation).flatMapTo(sortedSetOf()) { it.format.mimeTypes }
    fun supportedExtensions(operation: String? = null): Set<String> =
        supportedFormats(operation).flatMapTo(sortedSetOf()) { it.format.extensions }
    fun supportedUriSchemes(operation: String? = null): Set<String> =
        supportedFormats(operation).flatMapTo(sortedSetOf()) { it.format.uriSchemes }
    /** Stable across process restarts; revision remains the in-process invalidation token. */
    fun fingerprint():String {
        val current=revision
        fingerprintCache?.takeIf{it.first==current}?.let{return it.second}
        val digest=java.security.MessageDigest.getInstance("SHA-256")
        fun field(value:String){val bytes=value.toByteArray(Charsets.UTF_8);digest.update("${bytes.size}:".toByteArray());digest.update(bytes)}
        fun fields(label:String,values:Collection<String>){field(label);field(values.size.toString());values.forEach(::field)}
        val descriptions=descriptors().sortedBy{it.id}
        field("capabilities-v1");field(descriptions.size.toString())
        descriptions.forEach { d ->
            fields("handler",listOf(d.id,d.library,d.title,d.priority.toString()))
            fields("operations",d.operations.sorted());fields("metadata",d.metadata.sorted())
            fields("limitations",d.limitations)
            field("attributes");field(d.attributes.size.toString());d.attributes.toSortedMap().forEach{(k,v)->field(k);field(v)}
            field("formats");field(d.formats.size.toString())
            d.formats.forEach { f ->
                fields("mimeTypes",f.mimeTypes.sorted());fields("extensions",f.extensions.sorted());fields("uriSchemes",f.uriSchemes.sorted())
                field("magic");field(f.magic.size.toString())
                f.magic.forEach{fields("signature",listOf(it.offset.toString(),it.hex.lowercase(Locale.ROOT),it.description))}
            }
        }
        return digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}.also{if(revision==current)fingerprintCache=current to it}
    }

    fun register(handler: CapabilityHandler): Closeable {
        require(library == null || handler.descriptor.library == library)
        parent?.let { return it.register(handler) }
        synchronized(this) {
            require(handler.descriptor.id !in handlers) { "Duplicate capability handler: ${handler.descriptor.id}" }
            handlers[handler.descriptor.id] = handler
        }
        refresh()
        return Closeable {
            val removed = synchronized(this) { if (handlers[handler.descriptor.id] === handler) handlers.remove(handler.descriptor.id) else null }
            if (removed != null) refresh()
        }
    }
    /** Availability is cached by registry revision; refresh after dependency/configuration changes. */
    fun refresh() {
        parent?.let { it.refresh(); return }
        val next = synchronized(this) { ++localRevision }
        availabilityCache.clear()
        listeners.forEach { listener ->
            try {listener(next)}catch(e:Exception){
                if(failures.size<64)failures+=DiscoveryFailure("change-listener",e.javaClass.name)
            }
        }
    }
    fun subscribe(listener: (Long) -> Unit): Closeable {
        parent?.let { return it.subscribe(listener) }
        listeners += listener
        return Closeable { listeners -= listener }
    }
    private fun evaluate(handler:CapabilityHandler):HandlerAvailability {
        parent?.let { return it.evaluate(handler) }
        val current=revision
        availabilityCache[handler]?.takeIf { it.first==current }?.let { return it.second }
        val report=try { handler.availability() } catch(e:Exception) {
            HandlerAvailability(AvailabilityState.UNAVAILABLE,e.message?:e.javaClass.name)
        } catch(e:LinkageError) { HandlerAvailability(AvailabilityState.UNAVAILABLE,e.message?:e.javaClass.name) }
        availabilityCache[handler]=current to report
        return report
    }
    fun query(operation: String? = null, includeUnavailable: Boolean = false): List<DiscoveredCapability> =
        snapshot().filter {operation==null || operation in it.describe().operations}.map { handler -> DiscoveredCapability(handler,evaluate(handler))
        }.filter { (operation == null || operation in it.descriptor.operations) &&
            (includeUnavailable || it.availability.state != AvailabilityState.UNAVAILABLE) }
            .sortedWith(compareByDescending<DiscoveredCapability> { it.descriptor.priority }.thenBy { it.descriptor.id })

    fun probe(resource: CapabilityResource, operation: String? = null, budget: ProbeBudget = ProbeBudget(),
        cancellation: CancellationToken = CancellationToken.NONE): List<DiscoveredCapability> =
        probe(CapabilityProbeContext(resource,budget,cancellation),operation)

    /** Delegated probes share the same prefix, cancellation and byte budget. */
    fun probe(context:CapabilityProbeContext,operation:String?=null):List<DiscoveredCapability> {
        val resource=context.resource
        require(resource.containerDepth <= context.budget.maxDepth) { "Capability probe nesting limit exceeded" }
        return snapshot().sortedBy {it.descriptor.id}.mapNotNull { handler ->
            context.cancellation.check()
            val description=handler.describe()
            if(operation!=null&&operation !in description.operations)return@mapNotNull null
            val match=try {handler.probe(context)}catch(cancel:java.io.InterruptedIOException){throw cancel}
                catch(_:Exception){return@mapNotNull null}catch(_:LinkageError){return@mapNotNull null}
            if(match.confidence==MatchConfidence.NONE)return@mapNotNull null
            val availability=evaluate(handler)
            if(availability.state==AvailabilityState.UNAVAILABLE)return@mapNotNull null
            val operations=match.operations.intersect(description.operations).let {
                if(Capability.WRITE in resource.metadata.capabilities)it else it-Operations.WRITE
            }
            DiscoveredCapability(handler,availability,match.copy(operations=operations))
        }.filter { operation == null || operation in it.operations }
            .sortedWith(compareByDescending<DiscoveredCapability> { it.probe?.confidence?.ordinal ?: 0 }
                .thenByDescending { it.descriptor.formats.maxOfOrNull { f -> f.score(resource) } ?: 0 }
                .thenByDescending { it.descriptor.priority }.thenBy { it.descriptor.id })
    }
    fun loadServices(loader: ClassLoader = CapabilityProvider::class.java.classLoader): CapabilityRegistry {
        val iterator = ServiceLoader.load(CapabilityProvider::class.java, loader).iterator()
        var count = 0
        while (count++ < 4096) {
            val provider = try { if (!iterator.hasNext()) break; iterator.next() }
            catch (e: java.util.ServiceConfigurationError) {
                failures += DiscoveryFailure("service-loader", e.message ?: e.javaClass.name); continue
            }
            if (library != null && provider.library != library) continue
            try { provider.handlers().forEach { register(it) } }
            catch (e: Exception) { failures += DiscoveryFailure(provider.javaClass.name, e.message ?: e.javaClass.name) }
            catch (e: LinkageError) { failures += DiscoveryFailure(provider.javaClass.name, e.message ?: e.javaClass.name) }
        }
        return this
    }
}

/** Library-scoped registries work standalone; consumers may also create isolated explicit registries. */
object RuntimeCapabilities {
    private val registries = java.util.concurrent.ConcurrentHashMap<String, CapabilityRegistry>()
    private val installed by lazy { discover() }
    fun installed(): CapabilityRegistry = installed
    fun library(id: String): CapabilityRegistry = registries.computeIfAbsent(id) { CapabilityRegistry(it, installed) }
    fun discover(loader: ClassLoader = CapabilityProvider::class.java.classLoader): CapabilityRegistry = CapabilityRegistry().loadServices(loader)
}
