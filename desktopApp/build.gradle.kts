import java.util.Properties
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
}

// Desktop OAuth client (type "Desktop app") from local.properties, which is git-ignored.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val oauthJvmArgs = listOfNotNull(
    localProps.getProperty("google.desktop.clientId")?.let { "-Dgoogle.clientId=$it" },
    localProps.getProperty("google.desktop.clientSecret")?.let { "-Dgoogle.clientSecret=$it" },
)

compose.desktop {
    application {
        mainClass = "app.calendar.desktop.MainKt"
        jvmArgs += oauthJvmArgs
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "Calendar"
            packageVersion = "1.0.0"
            modules("jdk.httpserver", "java.net.http")
            macOS {
                bundleID = "app.calendar.desktop"
            }
        }
    }
}

// `./gradlew :desktopApp:run --args=--demo` starts with demo data switched on.
