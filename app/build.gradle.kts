plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.appbuilder"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.appbuilder"
        minSdk = 26
        // targetSdk 28 on purpose: Android 10+ blocks executing files from app storage for newer targets,
        // and the on-device JDK/Gradle toolchain must be executable (same approach Termux uses).
        targetSdk = 28
        versionCode = 1
        versionName = "0.1"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    lint {
        // targetSdk 28 is intentional (see above), so the Play Store target check must not fail release builds.
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
    }
    packaging {
        // JGit and its dependencies ship overlapping metadata files.
        resources {
            excludes += setOf(
                "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES", "META-INF/INDEX.LIST", "META-INF/AL2.0", "META-INF/LGPL2.1",
                "META-INF/versions/**", "about.html", "plugin.properties",
            )
        }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    // Git runs inside the app: JGit is pure Java (5.13 is the last line that works on Java 8 / Android).
    implementation("org.eclipse.jgit:org.eclipse.jgit:5.13.3.202401111512-r")
    implementation("org.slf4j:slf4j-nop:1.7.36")

    testImplementation("junit:junit:4.13.2")
}
