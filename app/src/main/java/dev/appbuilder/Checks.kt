package dev.appbuilder

enum class CheckStatus { OK, WARN, FAIL }

enum class FixKind { NONE, OPEN_SDK, ACCEPT_LICENSE, SET_COMPILE_SDK, USE_GRADLE, INSTALL_PLATFORM, INSTALL_BUILD_TOOLS, OPEN_CONFIG, FIND_JDK }

data class CheckFix(val label: String, val kind: FixKind, val arg: String? = null)

/** [confirm]: a warning that pauses the build until the user taps "Continue anyway". Other warnings only inform. */
data class CheckItem(
    val id: String, val title: String, val status: CheckStatus, val detail: String,
    val fix: CheckFix? = null, val confirm: Boolean = false,
)

data class PreflightFacts(
    val agp: String?, val gradle: GradlePick, val wrapperOverrideTooOld: String?, val jdkMajor: Int?, val jdkRuns: Boolean,
    val jdkIsGlibc: Boolean, val jdkArch: String?, val compileSdk: Int?, val installedPlatforms: Set<Int>, val maxKnownPlatform: Int?,
    val installedBuildTools: List<String>, val licensesAccepted: Boolean, val aapt2Arch: String?,
    val freeBytes: Long, val totalRamBytes: Long, val availRamBytes: Long, val sdkDirOk: Boolean, val hasWrapperProps: Boolean,
)

object PreflightChecks {
    private const val GIB = 1024L * 1024 * 1024
    private const val MIB = 1024L * 1024

    fun size(b: Long): String = if (b >= GIB) "%.1f GB".format(b / GIB.toDouble()) else "${b / MIB} MB"

    fun evaluate(f: PreflightFacts): List<CheckItem> {
        val out = ArrayList<CheckItem>()

        // Gradle
        val gv = f.gradle.version
        out += when {
            f.wrapperOverrideTooOld != null -> CheckItem("gradle", "Gradle", CheckStatus.FAIL,
                "The Gradle you chose is older than the ${f.wrapperOverrideTooOld} that Android Gradle Plugin ${f.agp} needs",
                CheckFix("Use Gradle ${f.wrapperOverrideTooOld}", FixKind.USE_GRADLE, f.wrapperOverrideTooOld))
            !f.hasWrapperProps && gv != null -> CheckItem("gradle", "Gradle", CheckStatus.OK, "No gradle-wrapper.properties. Using Gradle $gv (${f.gradle.reason})")
            gv != null -> CheckItem("gradle", "Gradle", CheckStatus.OK, "Gradle $gv (${f.gradle.reason})")
            else -> CheckItem("gradle", "Gradle", CheckStatus.OK, "Default Gradle version")
        }

        // JDK
        val minJdk = Toolchain.minJdk(f.agp)
        out += when {
            f.jdkIsGlibc -> CheckItem("jdk", "JDK", CheckStatus.FAIL, "This JDK is a Linux desktop (glibc) build and cannot run on Android", CheckFix("Open SDK", FixKind.OPEN_SDK))
            f.jdkArch != null && f.jdkArch != "ARM64" -> CheckItem("jdk", "JDK", CheckStatus.FAIL, "JDK binary is ${f.jdkArch}, expected ARM64", CheckFix("Open SDK", FixKind.OPEN_SDK))
            !f.jdkRuns -> CheckItem("jdk", "JDK", CheckStatus.FAIL, "The JDK is missing or does not run on this device", CheckFix("Open SDK", FixKind.OPEN_SDK))
            f.jdkMajor == null -> CheckItem("jdk", "JDK", CheckStatus.WARN, "The JDK version could not be read")
            minJdk != null && f.jdkMajor < minJdk -> CheckItem("jdk", "JDK", CheckStatus.FAIL,
                "JDK ${f.jdkMajor} is too old: Android Gradle Plugin ${f.agp} needs JDK $minJdk or newer", CheckFix("Find another JDK", FixKind.FIND_JDK))
            else -> CheckItem("jdk", "JDK", CheckStatus.OK, "JDK ${f.jdkMajor}")
        }

        // Platform
        val api = f.compileSdk
        val best = (f.installedPlatforms + listOfNotNull(f.maxKnownPlatform)).maxOrNull()
        out += when {
            api == null -> CheckItem("platform", "Android platform", CheckStatus.WARN, "Compile SDK could not be read from the project")
            api in f.installedPlatforms -> CheckItem("platform", "Android platform", CheckStatus.OK, "Android API $api is installed")
            f.maxKnownPlatform != null && api > f.maxKnownPlatform -> CheckItem("platform", "Android platform", CheckStatus.FAIL,
                "Android API $api is not available (newest: API ${best ?: f.maxKnownPlatform})",
                CheckFix("Set Compile SDK to ${best ?: f.maxKnownPlatform}", FixKind.SET_COMPILE_SDK, (best ?: f.maxKnownPlatform).toString()))
            else -> CheckItem("platform", "Android platform", CheckStatus.WARN, "Android API $api is not installed yet. It will be installed during the build.",
                CheckFix("Install now", FixKind.INSTALL_PLATFORM, api.toString()))
        }

        // Build tools
        out += if (f.installedBuildTools.isEmpty()) CheckItem("buildtools", "Build Tools", CheckStatus.WARN,
            "Build Tools are not installed yet. Gradle will download them.", CheckFix("Install now", FixKind.INSTALL_BUILD_TOOLS))
        else CheckItem("buildtools", "Build Tools", CheckStatus.OK, "Build Tools ${f.installedBuildTools.maxWithOrNull { a, b -> Toolchain.compare(a, b) }}")

        // License
        out += if (f.licensesAccepted) CheckItem("license", "SDK license", CheckStatus.OK, "The Android SDK license is accepted")
        else CheckItem("license", "SDK license", CheckStatus.FAIL, "The Android SDK license has not been accepted", CheckFix("Review license", FixKind.ACCEPT_LICENSE))

        // aapt2
        out += when {
            f.aapt2Arch == null -> CheckItem("aapt2", "aapt2", CheckStatus.WARN, "aapt2 was not found yet. It comes with Build Tools.")
            f.aapt2Arch != "ARM64" -> CheckItem("aapt2", "aapt2", CheckStatus.WARN, "aapt2 is ${f.aapt2Arch} and cannot run on this phone. Import ARM64 build tools.", CheckFix("Open SDK", FixKind.OPEN_SDK))
            else -> CheckItem("aapt2", "aapt2", CheckStatus.OK, "aapt2 is ARM64")
        }

        // Storage
        out += when {
            f.freeBytes < 500 * MIB -> CheckItem("storage", "Storage", CheckStatus.FAIL, "Only ${size(f.freeBytes)} of free storage. Free up space and try again.")
            f.freeBytes < 2 * GIB -> CheckItem("storage", "Storage", CheckStatus.WARN, "Low storage: ${size(f.freeBytes)} free. Builds need about 2 GB.", confirm = true)
            else -> CheckItem("storage", "Storage", CheckStatus.OK, "${size(f.freeBytes)} free")
        }

        // Memory
        out += if (f.availRamBytes in 1 until GIB) CheckItem("memory", "Memory", CheckStatus.WARN,
            "Low memory: ${size(f.availRamBytes)} available. The build may be stopped. Close other apps.", confirm = true)
        else CheckItem("memory", "Memory", CheckStatus.OK, if (f.availRamBytes > 0) "${size(f.availRamBytes)} available" else "Memory could not be read")

        // sdk.dir
        out += if (f.sdkDirOk) CheckItem("sdkdir", "sdk.dir", CheckStatus.OK, "sdk.dir is set in local.properties")
        else CheckItem("sdkdir", "sdk.dir", CheckStatus.WARN, "local.properties will be written with sdk.dir before the build")
        return out
    }

    fun blocking(items: List<CheckItem>): List<CheckItem> = items.filter { it.status == CheckStatus.FAIL }

    /** True when nothing fails and every pausing warning has been confirmed. */
    fun canProceed(items: List<CheckItem>, confirmed: Boolean): Boolean =
        blocking(items).isEmpty() && (confirmed || items.none { it.status == CheckStatus.WARN && it.confirm })
}
