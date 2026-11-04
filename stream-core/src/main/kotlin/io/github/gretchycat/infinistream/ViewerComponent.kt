package io.github.gretchycat.infinistream

/** Presentation registration is independent of application UI code and Android framework types.
 * The receiving Android host resolves this class name only when launching the installed component.
 * Decoders and source access remain services of the component's declared library dependencies. */
interface ViewerComponent : CapabilityHandler { val activityClassName:String }
