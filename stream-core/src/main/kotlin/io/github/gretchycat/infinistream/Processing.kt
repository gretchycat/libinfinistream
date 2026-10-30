package io.github.gretchycat.infinistream

import java.io.Closeable
import java.io.IOException

enum class FlowMode { PULL, PUSH }
data class PortDescriptor(val name: String, val kinds: Set<DataKind>, val formats: Set<String> = emptySet(),
    val capabilities: Set<Capability> = emptySet(), val multiple: Boolean = false) {
    fun accepts(metadata: StreamMetadata) = metadata.content.kind in kinds &&
        (formats.isEmpty() || metadata.content.format in formats) && metadata.capabilities.containsAll(capabilities)
}
data class ProcessorDescriptor(val id: String, val inputs: List<PortDescriptor>, val outputs: List<PortDescriptor>,
    val modes: Set<FlowMode> = setOf(FlowMode.PULL), val options: Set<String> = emptySet()) {
    init { require(id.isNotBlank()); require(inputs.map { it.name }.distinct().size == inputs.size); require(outputs.map { it.name }.distinct().size == outputs.size) }
}
data class ProcessorOptions(val values: Map<String, String> = emptyMap(), val mode: FlowMode = FlowMode.PULL,
    val cancellation: CancellationToken = CancellationToken.NONE)
sealed interface StreamEvent {
    data class MetadataUpdated(val port: String, val metadata: StreamMetadata) : StreamEvent
    data class Progress(val progress: StreamProgress) : StreamEvent
    data class Warning(val message: String) : StreamEvent
}
interface ProcessorSession : Closeable {
    /** Actual negotiated metadata belongs to each output handle, not the static descriptor. */
    val outputs: Map<String, List<StreamHandle>>
    fun pollEvents(): List<StreamEvent> = emptyList()
}
interface StreamProcessor {
    val descriptor: ProcessorDescriptor
    /** Inputs are borrowed; session owns outputs. Closing outputs must not close borrowed inputs. */
    fun open(inputs: Map<String, List<StreamHandle>>, options: ProcessorOptions = ProcessorOptions()): ProcessorSession
}
/** Push is opt-in and blocking: return only when the chunk is consumed, never retain its mutable buffer. */
fun interface ChunkConsumer { fun accept(buffer: ByteArray, offset: Int, length: Int) }
fun push(source: ByteSource, consumer: ChunkConsumer, cancellation: CancellationToken = CancellationToken.NONE,
    chunkSize: Int = 64 * 1024): Long = copy(source, object : ByteSink {
        override val metadata = StreamMetadata(capabilities = setOf(Capability.WRITE))
        override fun write(buffer: ByteArray, offset: Int, length: Int) = consumer.accept(buffer, offset, length)
        override fun close() {}
    }, cancellation, bufferSize = chunkSize)

/** No processor is opened during planning. Caller-supplied negotiation derives honest output metadata. */
class ProcessorRegistration(val processor: StreamProcessor,
    val negotiate: (Map<String, List<StreamMetadata>>, ProcessorOptions) -> Map<String, List<StreamMetadata>>)
data class PlanStep(val processor: StreamProcessor, val inputs: Map<String, List<StreamMetadata>>,
    val outputs: Map<String, List<StreamMetadata>>)
class ProcessorRegistry(registrations: List<ProcessorRegistration>) {
    val registrations = registrations.toList()
    init { require(registrations.map { it.processor.descriptor.id }.distinct().size == registrations.size) }
    val descriptors get() = registrations.map { it.processor.descriptor }
    /** Bounded pull search for a single resource route. Multi-port processors require explicit bindings. */
    fun plan(input: StreamMetadata, target: PortDescriptor, options: ProcessorOptions = ProcessorOptions(),
        allowedBackends: Set<String>? = null, maxDepth: Int = 8, maxStates: Int = 128): List<PlanStep>? {
        require(maxDepth in 0..64 && maxStates in 1..10000)
        data class State(val metadata: StreamMetadata, val path: List<PlanStep>)
        val queue = java.util.ArrayDeque<State>(); queue.add(State(input, emptyList()))
        val visited = mutableSetOf<StreamMetadata>(); var explored = 0
        while (queue.isNotEmpty() && explored++ < maxStates) {
            options.cancellation.check()
            val state = queue.removeFirst()
            if (target.accepts(state.metadata)) return state.path
            if (!visited.add(state.metadata) || state.path.size >= maxDepth) continue
            for (entry in registrations) {
                val d = entry.processor.descriptor
                if (options.mode !in d.modes || (allowedBackends != null && d.id !in allowedBackends) || d.inputs.size != 1 || d.inputs[0].multiple) continue
                if (state.path.any { it.processor.descriptor.id == d.id } || !d.inputs[0].accepts(state.metadata)) continue
                val bindings = mapOf(d.inputs[0].name to listOf(state.metadata))
                val outputs = entry.negotiate(bindings, options)
                if (outputs.keys != d.outputs.map { it.name }.toSet()) throw IOException("Negotiator returned invalid output ports: ${d.id}")
                outputs.forEach { (port, values) ->
                    val declared = d.outputs.single { it.name == port }
                    if ((!declared.multiple && values.size != 1) || values.any { !declared.accepts(it) }) throw IOException("Negotiated output violates descriptor: ${d.id}/$port")
                    for (value in values) { if (queue.size + explored >= maxStates) break; queue.add(State(value, state.path + PlanStep(entry.processor, bindings, outputs))) }
                }
            }
        }
        return null
    }
    fun open(processorId: String, inputs: Map<String, List<StreamHandle>>, options: ProcessorOptions = ProcessorOptions()): ProcessorSession {
        options.cancellation.check()
        val registration = registrations.singleOrNull { it.processor.descriptor.id == processorId } ?: throw IOException("Unknown processor: $processorId")
        val descriptor = registration.processor.descriptor
        if (options.mode !in descriptor.modes || options.values.keys.any { it !in descriptor.options }) throw IOException("Unsupported processing mode/options")
        if (inputs.keys != descriptor.inputs.map { it.name }.toSet()) throw IOException("Input ports do not match")
        descriptor.inputs.forEach { port ->
            val handles = inputs.getValue(port.name)
            if (handles.isEmpty() || (!port.multiple && handles.size != 1) || handles.any { !port.accepts(it.metadata) }) throw IOException("Incompatible input: ${port.name}")
        }
        return registration.processor.open(inputs, options)
    }
}
