package dev.appbuilder

enum class OomKind { JAVA_HEAP, GC_OVERHEAD, METASPACE, DAEMON_DISAPPEARED, KILLED, NONE }

class MemoryAdvice(val title: String, val steps: List<String>, val newHeapMb: Int?, val disableKotlinDaemon: Boolean)

object MemoryPlan {
    const val MIN_MB = 768
    const val MAX_MB = 3072

    /** About 40% of the free RAM, never above 55% of all RAM, between 768 and 3072 MB, in steps of 128. */
    fun gradleHeapMb(availRamMb: Long, totalRamMb: Long): Int {
        val base = if (availRamMb > 0) availRamMb * 0.40 else totalRamMb / 4.0
        val cap = if (totalRamMb > 0) totalRamMb * 0.55 else Double.MAX_VALUE
        val v = minOf(base, cap).toInt().coerceIn(MIN_MB, MAX_MB)
        return v / 128 * 128
    }

    /** Runs the Kotlin compiler inside Gradle's own JVM instead of a second daemon process (saves memory on phones). */
    fun kotlinInProcessArgs(): List<String> = listOf("-Pkotlin.compiler.execution.strategy=in-process")

    fun jvmArgs(heapMb: Int): List<String> = listOf("-Xmx${heapMb.coerceIn(512, 8192)}m")

    fun detect(log: String, exitCode: Int?): OomKind = when {
        log.contains("GC overhead limit exceeded") -> OomKind.GC_OVERHEAD
        log.contains("OutOfMemoryError: Metaspace") || log.contains("Metaspace") && log.contains("OutOfMemoryError") -> OomKind.METASPACE
        log.contains("OutOfMemoryError") -> OomKind.JAVA_HEAP
        log.contains("Gradle daemon disappeared", true) || log.contains("daemon disappeared unexpectedly", true) -> OomKind.DAEMON_DISAPPEARED
        exitCode == 137 || log.trimEnd().endsWith("Killed") -> OomKind.KILLED
        else -> OomKind.NONE
    }

    fun advice(kind: OomKind, currentHeapMb: Int, availRamMb: Long = 0, totalRamMb: Long = 0): MemoryAdvice {
        val possible = gradleHeapMb(availRamMb, totalRamMb)
        val lower = (currentHeapMb * 3 / 4 / 128 * 128).coerceAtLeast(MIN_MB).takeIf { it < currentHeapMb }
        return when (kind) {
            OomKind.JAVA_HEAP, OomKind.GC_OVERHEAD, OomKind.METASPACE -> {
                val raise = possible.takeIf { availRamMb > 0 && it >= currentHeapMb + 256 }
                MemoryAdvice(
                    "Gradle ran out of memory",
                    listOfNotNull(raise?.let { "Raise the Gradle memory to $it MB" }, "Close other apps and build again", "Turn off the Kotlin daemon to save memory"),
                    raise, true,
                )
            }
            OomKind.DAEMON_DISAPPEARED, OomKind.KILLED -> MemoryAdvice(
                "Android stopped the build to free memory",
                listOfNotNull(lower?.let { "Lower the Gradle memory to $it MB" }, "Close other apps and build again", "Turn off the Kotlin daemon to save memory"),
                lower, true,
            )
            OomKind.NONE -> MemoryAdvice("", emptyList(), null, false)
        }
    }
}
