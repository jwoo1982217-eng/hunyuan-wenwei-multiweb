# Ktor only probes these desktop-JVM management classes to detect an attached
# IntelliJ debugger. Android does not provide them and release runtime does not
# need that optional probe.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
