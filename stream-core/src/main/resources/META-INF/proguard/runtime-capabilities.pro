# ServiceLoader providers and typed library service contracts are public extension points.
-keep class * implements io.github.gretchycat.infinistream.CapabilityProvider { public <init>(); public *; }
-keep interface * extends io.github.gretchycat.infinistream.CapabilityHandler { *; }
-keep interface io.github.gretchycat.infinistream.CapabilityHandler { *; }
-keepnames class io.github.gretchycat.infinistream.**
