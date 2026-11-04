package io.github.gretchycat.infinistream

/** A handler exposes the existing processor and its authoritative negotiator; no second pipeline engine. */
class ProcessorCapabilityHandler(override val descriptor:HandlerDescriptor,val registration:ProcessorRegistration,
    private val runtime:()->HandlerAvailability={HandlerAvailability()}):CapabilityHandler {
    override fun describe()=descriptor.copy(operations=descriptor.operations+"process",attributes=descriptor.attributes+("pipeline" to "true"))
    override fun availability()=runtime()
    override fun <T:Any> service(type:Class<T>):T?=when {
        type.isInstance(registration)->type.cast(registration)
        type.isInstance(registration.processor)->type.cast(registration.processor)
        else->super.service(type)
    }
}
object RuntimeProcessors {
    /** Fresh snapshot after registration/configuration changes. Execution and port checks use ProcessorRegistry. */
    fun registry(capabilities:CapabilityRegistry=RuntimeCapabilities.installed()):ProcessorRegistry = ProcessorRegistry(
        capabilities.query("process").mapNotNull {it.handler.service(ProcessorRegistration::class.java)}.distinctBy{it.processor.descriptor.id})
}
