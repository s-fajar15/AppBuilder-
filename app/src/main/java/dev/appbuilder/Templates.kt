package dev.appbuilder

/** Generates real Gradle projects. Versions are kept in one place. */
object Templates {
    private const val AGP = Toolchain.TESTED_AGP
    private const val KOTLIN = Toolchain.TESTED_KOTLIN

    fun files(name: String, pkg: String, lang: String, ui: String): Map<String, String> {
        val kotlin = lang == "Kotlin"
        val compose = kotlin && ui == "Compose"
        val src = "app/src/main/java/" + pkg.replace('.', '/')
        val m = linkedMapOf<String, String>()

        m["settings.gradle.kts"] = """
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "$name"
include(":app")
""".trimStart()

        m["build.gradle.kts"] = buildString {
            appendLine("plugins {")
            appendLine("    id(\"com.android.application\") version \"$AGP\" apply false")
            if (kotlin) appendLine("    id(\"org.jetbrains.kotlin.android\") version \"$KOTLIN\" apply false")
            if (compose) appendLine("    id(\"org.jetbrains.kotlin.plugin.compose\") version \"$KOTLIN\" apply false")
            appendLine("}")
        }

        m["gradle.properties"] = "org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8\nandroid.useAndroidX=true\n"
        m["gradle/wrapper/gradle-wrapper.properties"] = """
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-${Toolchain.TESTED_GRADLE}-bin.zip
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
""".trimStart()

        m["app/build.gradle.kts"] = buildString {
            appendLine("plugins {")
            appendLine("    id(\"com.android.application\")")
            if (kotlin) appendLine("    id(\"org.jetbrains.kotlin.android\")")
            if (compose) appendLine("    id(\"org.jetbrains.kotlin.plugin.compose\")")
            appendLine("}\n")
            appendLine("android {")
            appendLine("    namespace = \"$pkg\"")
            appendLine("    compileSdk = 36\n")
            appendLine("    defaultConfig {")
            appendLine("        applicationId = \"$pkg\"")
            appendLine("        minSdk = 26")
            appendLine("        targetSdk = 36")
            appendLine("        versionCode = 1")
            appendLine("        versionName = \"1.0\"")
            appendLine("    }")
            appendLine("    buildTypes {")
            appendLine("        release { isMinifyEnabled = false }")
            appendLine("    }")
            appendLine("    compileOptions {")
            appendLine("        sourceCompatibility = JavaVersion.VERSION_17")
            appendLine("        targetCompatibility = JavaVersion.VERSION_17")
            appendLine("    }")
            if (kotlin) appendLine("    kotlinOptions { jvmTarget = \"17\" }")
            if (compose) appendLine("    buildFeatures { compose = true }")
            appendLine("}\n")
            appendLine("dependencies {")
            if (compose) {
                appendLine("    implementation(platform(\"androidx.compose:compose-bom:2025.04.01\"))")
                appendLine("    implementation(\"androidx.compose.ui:ui\")")
                appendLine("    implementation(\"androidx.compose.material3:material3\")")
                appendLine("    implementation(\"androidx.activity:activity-compose:1.10.1\")")
                appendLine("    implementation(\"androidx.core:core-ktx:1.16.0\")")
            } else {
                if (kotlin) appendLine("    implementation(\"androidx.core:core-ktx:1.16.0\")")
                appendLine("    implementation(\"androidx.appcompat:appcompat:1.7.0\")")
                appendLine("    implementation(\"com.google.android.material:material:1.12.0\")")
            }
            appendLine("}")
        }

        m["app/proguard-rules.pro"] = ""
        val theme = if (compose) "@android:style/Theme.Material.Light.NoActionBar" else "@style/Theme.App"
        m["app/src/main/AndroidManifest.xml"] = """
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:label="@string/app_name"
        android:theme="$theme">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
""".trimStart()
        m["app/src/main/res/values/strings.xml"] =
            "<resources>\n    <string name=\"app_name\">$name</string>\n</resources>\n"

        if (compose) {
            m["$src/MainActivity.kt"] = """
package $pkg

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Text("Hello, $name") }
    }
}
""".trimStart()
        } else {
            m["app/src/main/res/values/themes.xml"] =
                "<resources>\n    <style name=\"Theme.App\" parent=\"Theme.Material3.DayNight.NoActionBar\" />\n</resources>\n"
            m["app/src/main/res/layout/activity_main.xml"] = """
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="center"
        android:text="Hello, $name" />
</FrameLayout>
""".trimStart()
            m[if (kotlin) "$src/MainActivity.kt" else "$src/MainActivity.java"] = if (kotlin) """
package $pkg

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }
}
""".trimStart() else """
package $pkg;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }
}
""".trimStart()
        }
        return m
    }
}
