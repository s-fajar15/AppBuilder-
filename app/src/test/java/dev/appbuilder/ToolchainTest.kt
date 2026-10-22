package dev.appbuilder

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ToolchainTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun proj(vararg files: Pair<String, String>): File { val d = tmp.newFolder(); files.forEach { (p, t) -> File(d, p).apply { parentFile.mkdirs(); writeText(t) } }; return d }

    @Test fun compareVersions() {
        assertEquals(0, Toolchain.compare("8.11.1", "8.11.1")); assertTrue(Toolchain.compare("9.5.0", "8.11.1") > 0)
        assertTrue(Toolchain.compare("8.14", "8.14.1") < 0); assertEquals(0, Toolchain.compare("8.14", "8.14.0"))
        assertTrue(Toolchain.compare("9.5.0-rc-1", "9.4.9") > 0)
    }

    @Test fun agpFromRootBuildFile() {
        assertEquals("9.3.3", Toolchain.agpVersion(proj("build.gradle.kts" to "plugins {\n  id(\"com.android.application\") version \"9.3.3\" apply false\n}")))
        assertEquals("8.2.0", Toolchain.agpVersion(proj("build.gradle" to "plugins {\n  id 'com.android.application' version '8.2.0' apply false\n}")))
        assertEquals("7.4.2", Toolchain.agpVersion(proj("build.gradle" to "buildscript { dependencies { classpath 'com.android.tools.build:gradle:7.4.2' } }")))
        assertNull(Toolchain.agpVersion(proj("build.gradle" to "// nothing")))
    }

    @Test fun agpFromVersionCatalog() {
        assertEquals("9.3.3", Toolchain.agpVersion(proj("gradle/libs.versions.toml" to "[versions]\nagp = \"9.3.3\"\nkotlin = \"2.1.0\"\n")))
        val named = "[versions]\nandroidGradle = \"8.9.1\"\n[plugins]\nandroid-application = { id = \"com.android.application\", version.ref = \"androidGradle\" }\n"
        assertEquals("8.9.1", Toolchain.agpVersion(proj("gradle/libs.versions.toml" to named)))
        val direct = "[plugins]\nandroid-application = { id = \"com.android.application\", version = \"8.5.2\" }\n"
        assertEquals("8.5.2", Toolchain.agpVersion(proj("gradle/libs.versions.toml" to direct)))
        assertEquals("2.1.0", Toolchain.kotlinVersion(proj("gradle/libs.versions.toml" to "[versions]\nkotlin = \"2.1.0\"\n")))
    }

    @Test fun minGradleTable() {
        assertEquals("9.5.0", Toolchain.minGradle("9.3.3"))
        assertEquals("8.11.1", Toolchain.minGradle("8.9.1"))
        assertEquals("9.5.0", Toolchain.minGradle("9.4.0"))   // newer minor than known: newest older row is a lower bound
        assertEquals("9.1.0", Toolchain.minGradle("9.1.0"))
        assertNull(Toolchain.minGradle("10.0.0")); assertNull(Toolchain.minGradle(null)); assertNull(Toolchain.minGradle("8"))
        assertEquals("9.6.0", Toolchain.minGradle("9.3.3", mapOf("9.3.3" to "9.6.0")))
    }

    @Test fun pickGradleOrder() {
        assertEquals(GradlePick("8.0", "your choice"), Toolchain.pickGradle("8.0", "8.11.1", "9.3.3"))
        assertEquals(GradlePick("9.6.0", "gradle-wrapper.properties"), Toolchain.pickGradle(null, "9.6.0", "9.3.3"))
        assertEquals(GradlePick("9.5.0", "raised from wrapper 8.11.1: Android Gradle Plugin 9.3.3 needs 9.5.0 or newer"), Toolchain.pickGradle(null, "8.11.1", "9.3.3"))
        assertEquals(GradlePick("9.5.0", "required by Android Gradle Plugin 9.3.3"), Toolchain.pickGradle(null, null, "9.3.3"))
        assertEquals(GradlePick(null, "default"), Toolchain.pickGradle(null, null, null))
        assertEquals(GradlePick("8.11.1", "gradle-wrapper.properties"), Toolchain.pickGradle(null, "8.11.1", "10.0.0"))
    }

    @Test fun pixlyGalleryCaseWithoutWrapper() {
        val d = proj("gradle/libs.versions.toml" to "[versions]\nagp = \"9.3.3\"\n", "settings.gradle.kts" to "rootProject.name=\"Pixly\"")
        assertNull(Toolchain.wrapperVersion(d))
        val pick = Toolchain.pickGradle(null, Toolchain.wrapperVersion(d), Toolchain.agpVersion(d))
        assertEquals("9.5.0", pick.version); assertTrue(pick.reason.startsWith("required by Android Gradle Plugin 9.3.3"))
    }

    @Test fun wrapperVersionParsing() {
        val w = { u: String -> proj("gradle/wrapper/gradle-wrapper.properties" to "distributionUrl=https\\://services.gradle.org/distributions/$u\n") }
        assertEquals("8.11.1", Toolchain.wrapperVersion(w("gradle-8.11.1-bin.zip")))
        assertEquals("9.0", Toolchain.wrapperVersion(w("gradle-9.0-all.zip")))
        assertEquals("9.5.0-rc-1", Toolchain.wrapperVersion(w("gradle-9.5.0-rc-1-bin.zip")))
        assertNull(Toolchain.wrapperVersion(w("gradle-8.11.1-evil/../x-bin.zip")))
    }

    @Test fun overrideTooOld() {
        assertEquals("9.5.0", Toolchain.overrideTooOld("8.11.1", "9.3.3")); assertNull(Toolchain.overrideTooOld("9.5.0", "9.3.3")); assertNull(Toolchain.overrideTooOld(null, "9.3.3"))
    }

    @Test fun jdkParsing() {
        assertEquals(21, Toolchain.jdkMajor("openjdk version \"21.0.12.1\" 2026-08-18"))
        assertEquals(8, Toolchain.jdkMajor("java version \"1.8.0_392\""))
        assertEquals(17, Toolchain.jdkMajor("17.0.9")); assertEquals(11, Toolchain.jdkMajor("openjdk 11.0.21 2023-10-17")); assertNull(Toolchain.jdkMajor("none"))
        assertEquals(17, Toolchain.minJdk("8.9.1")); assertEquals(11, Toolchain.minJdk("7.4.2")); assertEquals(17, Toolchain.minJdk("9.3.3")); assertNull(Toolchain.minJdk(null))
    }

    @Test fun presetsAreConsistentWithTheTable() {
        for (p in Toolchain.presets()) {
            val min = Toolchain.minGradle(p.agp)!!
            assertTrue("${p.id}: gradle ${p.gradle} < $min", Toolchain.compare(p.gradle, min) >= 0)
            assertTrue(p.jdk >= Toolchain.minJdk(p.agp)!!)
        }
        val t = Toolchain.presets().first { it.agp == Toolchain.TESTED_AGP }
        assertEquals(Toolchain.TESTED_GRADLE, t.gradle)
    }

    @Test fun learnedPairs() {
        val log = "* What went wrong:\nMinimum supported Gradle version is 9.6.1. Current version is 9.5.0. If using the gradle wrapper, try editing"
        assertEquals("9.4.0" to "9.6.1", LearnedToolchain.parse(log, "9.4.0"))
        assertNull(LearnedToolchain.parse(log, null)); assertNull(LearnedToolchain.parse(log, "9.4.0-beta")); assertNull(LearnedToolchain.parse("nothing", "9.4.0"))
        var t = LearnedToolchain.merge("", "9.4.0", "9.6.1")
        assertEquals("9.4.0=9.6.1\n", t)
        assertEquals(t, LearnedToolchain.merge(t, "9.4.0", "9.6.1"))
        assertEquals(t, LearnedToolchain.merge(t, "9.4.0", "9.5.0"))      // keeps the higher
        t = LearnedToolchain.merge(t, "8.9.1", "8.12")
        assertEquals("8.9.1=8.12\n9.4.0=9.6.1\n", t)
        assertEquals("9.6.1", Toolchain.minGradle("9.4.0", LearnedToolchain.toMap(t)))
        assertEquals(mapOf("8.9.1" to "8.12"), LearnedToolchain.toMap("8.9.1=8.12\ngarbage\n# c\nx=y\n"))
    }

    @Test fun projectMetaRoundTrips() {
        val d = tmp.newFolder()
        assertNull(ProjectMeta.gradleOverride(d)); ProjectMeta.setGradleOverride(d, "9.5.0"); assertEquals("9.5.0", ProjectMeta.gradleOverride(d))
        ProjectMeta.setHeapOverrideMb(d, 1024); assertEquals(1024, ProjectMeta.heapOverrideMb(d)); assertEquals("9.5.0", ProjectMeta.gradleOverride(d))
        ProjectMeta.setKotlinInProcess(d, true); assertTrue(ProjectMeta.kotlinInProcess(d))
        ProjectMeta.clearGradleOverride(d); assertNull(ProjectMeta.gradleOverride(d))
        ProjectMeta.setLastBuild(d, false); assertEquals(1024, ProjectMeta.heapOverrideMb(d))
    }

    @Test fun ensureWrapperPropsWritesOnlyWhenMissing() {
        val d = proj("gradle/libs.versions.toml" to "[versions]\nagp = \"9.3.3\"\n")
        assertEquals("9.5.0", Toolchain.ensureWrapperProps(d))
        assertEquals("9.5.0", Toolchain.wrapperVersion(d))
        assertNull(Toolchain.ensureWrapperProps(d))
        assertNull(Toolchain.ensureWrapperProps(proj("build.gradle" to "// none")))
    }
}
