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
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    jvmArgs("-Dapple.awt.UIElement=true", "-Djava.awt.headless=true")
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
        // Name shown in the Dock and Cmd+Tab when running from Gradle (otherwise "java").
        // -Xdock:* is a macOS-only JVM option and stops Java from starting elsewhere; packages are
        // always built on the OS they target, so the build machine's OS decides.
        if (System.getProperty("os.name").lowercase().contains("mac")) {
            jvmArgs += listOf("-Xdock:name=Calendar", "-Dapple.awt.application.name=Calendar")
        }
        // Lets main() set the Linux WM_CLASS so docks show the app's name and icon.
        jvmArgs += "--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "Calendar"
            // macOS needs a first number above 0, so the Mac bundle keeps 1.0.0; Linux uses the release number.
            packageVersion = "1.0.0"
            modules("jdk.httpserver", "java.net.http")
            description = "Calendar for Google accounts, in the style of Notion Calendar"
            vendor = "Calendar"
            linux {
                packageName = "calendar-app"
                debPackageVersion = "0.2.0"
                rpmPackageVersion = "0.2.0"
                debMaintainer = "calendar-app@users.noreply.github.com"
                menuGroup = "Office"
                appCategory = "Office"
                shortcut = true
                iconFile.set(project.file("icons/Calendar.png"))
            }
            macOS {
                bundleID = "app.calendar.desktop"
                iconFile.set(project.file("icons/Calendar.icns"))
                dockName = "Calendar"
            }
        }
    }
}

// `./gradlew :desktopApp:run --args=--demo` starts with demo data switched on.

// A lightweight Calendar.app for day-to-day use on this Mac, without jpackage:
// the app's jars plus a launcher script that runs Java from the bundle, so macOS
// shows "Calendar" and the app icon in the Dock and Cmd+Tab (a plain `run` shows "java").
// Built by `just mac-app`; `just dmg` is the full self-contained package.
val macApp = tasks.register<Sync>("macApp") {
    val bundle = layout.buildDirectory.dir("mac/Calendar.app")
    val javaHome = System.getProperty("java.home")
    val extraArgs = (oauthJvmArgs + listOf("-Dapple.awt.application.name=Calendar")).joinToString(" ") { "'$it'" }
    into(bundle)
    into("Contents/Java") {
        from(tasks.named("jar"))
        from(configurations.named("runtimeClasspath"))
    }
    into("Contents/Resources") { from("icons/Calendar.icns") }
    doLast {
        val contents = bundle.get().asFile.resolve("Contents")
        contents.resolve("Info.plist").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0"><dict>
              <key>CFBundleName</key><string>Calendar</string>
              <key>CFBundleDisplayName</key><string>Calendar</string>
              <key>CFBundleIdentifier</key><string>app.calendar.desktop</string>
              <key>CFBundleExecutable</key><string>Calendar</string>
              <key>CFBundleIconFile</key><string>Calendar.icns</string>
              <key>CFBundlePackageType</key><string>APPL</string>
              <key>CFBundleShortVersionString</key><string>1.0.0</string>
              <key>NSHighResolutionCapable</key><true/>
              <key>NSSupportsAutomaticGraphicsSwitching</key><true/>
            </dict></plist>
            """.trimIndent(),
        )
        val launcher = contents.resolve("MacOS/Calendar")
        launcher.parentFile.mkdirs()
        launcher.writeText(
            """
            #!/bin/sh
            # Starts Calendar with the Java it was built with (override with CALENDAR_JAVA_HOME).
            DIR="${'$'}(cd "${'$'}(dirname "${'$'}0")/.." && pwd)"
            JAVA_HOME="${'$'}{CALENDAR_JAVA_HOME:-$javaHome}"
            exec "${'$'}JAVA_HOME/bin/java" -Xdock:name=Calendar -Xdock:icon="${'$'}DIR/Resources/Calendar.icns" $extraArgs \
                -cp "${'$'}DIR/Java/*" app.calendar.desktop.MainKt "${'$'}@"
            """.trimIndent() + "\n",
        )
        launcher.setExecutable(true)
    }
}
