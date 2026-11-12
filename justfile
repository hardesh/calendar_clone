# Calendar app tasks. Run `just` to list them.

# Java to build with: your JAVA_HOME if it works, else Android Studio's bundled Java,
# else whatever `java` is on PATH. Works on macOS and Linux.
export JAVA_HOME := `if [ -x "${JAVA_HOME:-}/bin/java" ]; then echo "$JAVA_HOME"; \
  elif [ -x "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java" ]; then echo "/Applications/Android Studio.app/Contents/jbr/Contents/Home"; \
  elif [ -x "$HOME/android-studio/jbr/bin/java" ]; then echo "$HOME/android-studio/jbr"; \
  elif [ -x /opt/android-studio/jbr/bin/java ]; then echo /opt/android-studio/jbr; \
  elif command -v java >/dev/null; then dirname "$(dirname "$(readlink -f "$(command -v java)")")"; fi`

# Full JDK with jpackage, for .dmg/.deb/.rpm packaging (Homebrew openjdk@21 on macOS).
package_java := `if [ -x "${PACKAGE_JAVA_HOME:-}/bin/jpackage" ]; then echo "$PACKAGE_JAVA_HOME"; \
  elif [ -x /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/jpackage ]; then echo /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home; \
  elif [ -x /usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/jpackage ]; then echo /usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home; \
  elif [ -x "${JAVA_HOME:-}/bin/jpackage" ]; then echo "$JAVA_HOME"; \
  elif command -v jpackage >/dev/null; then dirname "$(dirname "$(readlink -f "$(command -v jpackage)")")"; fi`

android_sdk := env_var_or_default("ANDROID_HOME", if os() == "macos" { env_var("HOME") / "Library/Android/sdk" } else { env_var("HOME") / "Android/Sdk" })
adb := android_sdk / "platform-tools/adb"

# List available recipes
default:
    @just --list --unsorted

# Build everything: shared code, Mac app and Android debug APK
build:
    ./gradlew :shared:compileKotlinJvm :desktopApp:compileKotlin :androidApp:assembleDebug

# Run all tests (logic, Google API client, simulated mouse drags, screenshot renders, menu bar)
test:
    ./gradlew :shared:jvmTest :desktopApp:test

# Run one test class or method, e.g. `just test-one MouseDragTest`
test-one name:
    ./gradlew :shared:jvmTest --tests "*{{name}}*"

# Render each view to PNG in shared/build/screenshots
screenshots:
    ./gradlew :shared:jvmTest --tests "app.calendar.ScreenshotTest" --rerun
    @echo "Screenshots: shared/build/screenshots/"

# Build Calendar.app (named and iconed properly in the Dock / Cmd+Tab) and open it
[macos]
mac: mac-app
    open desktopApp/build/mac/Calendar.app

# Build desktopApp/build/mac/Calendar.app; drag it to /Applications to keep it
[macos]
mac-app:
    ./gradlew :desktopApp:macApp

# Run the Mac app straight from Gradle (shows as "java" in Cmd+Tab)
[macos]
mac-dev:
    ./gradlew :desktopApp:run

# Run the Mac app with demo data switched on
[macos]
mac-demo: mac-app
    open desktopApp/build/mac/Calendar.app --args --demo

# Build a Mac .dmg (needs a full JDK with jpackage: brew install openjdk@21)
[macos]
dmg:
    @test -n "{{package_java}}" || (echo "No JDK with jpackage found; run: brew install openjdk@21" && exit 1)
    JAVA_HOME="{{package_java}}" ./gradlew :desktopApp:packageDmg
    @ls desktopApp/build/compose/binaries/main/dmg/

# Run the app on Linux
[linux]
linux:
    ./gradlew :desktopApp:run

# Run the app on Linux with demo data
[linux]
linux-demo:
    ./gradlew :desktopApp:run --args=--demo

# Build a Linux .deb (Debian/Ubuntu) for this machine's CPU; needs fakeroot and dpkg
[linux]
deb:
    @test -n "{{package_java}}" || (echo "No JDK with jpackage found (install openjdk-21-jdk)" && exit 1)
    JAVA_HOME="{{package_java}}" ./gradlew :desktopApp:packageDeb
    @ls desktopApp/build/compose/binaries/main/deb/

# Build a Linux .rpm (Fedora/openSUSE); needs rpm-build
[linux]
rpm:
    @test -n "{{package_java}}" || (echo "No JDK with jpackage found" && exit 1)
    JAVA_HOME="{{package_java}}" ./gradlew :desktopApp:packageRpm
    @ls desktopApp/build/compose/binaries/main/rpm/

# Build the Linux amd64 .deb from a Mac or any machine with Docker (writes to dist/)
deb-docker:
    mkdir -p dist
    docker run --rm --platform linux/amd64 -v "$PWD":/src -v calendar-gradle-cache:/root/.gradle eclipse-temurin:21-jdk-jammy bash /src/scripts/build-deb-in-docker.sh

# Build the Android debug APK
apk:
    ./gradlew :androidApp:assembleDebug
    @echo "APK: androidApp/build/outputs/apk/debug/androidApp-debug.apk"

# List connected Android devices and emulators
devices:
    {{adb}} devices -l

# Install on a connected device and open it. Pick one with `just android <serial>` when several are connected.
android serial="":
    ./gradlew :androidApp:assembleDebug
    {{adb}} {{ if serial == "" { "" } else { "-s " + serial } }} install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
    {{adb}} {{ if serial == "" { "" } else { "-s " + serial } }} shell am start -n app.calendar/.MainActivity

# Same as `android`, with demo data switched on
android-demo serial="":
    ./gradlew :androidApp:assembleDebug
    {{adb}} {{ if serial == "" { "" } else { "-s " + serial } }} install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
    {{adb}} {{ if serial == "" { "" } else { "-s " + serial } }} shell am start -n app.calendar/.MainActivity --ez demo true

# Show Android app logs (crashes and sign-in errors)
logs serial="":
    {{adb}} {{ if serial == "" { "" } else { "-s " + serial } }} logcat -v brief "*:W" "System.out:I" | grep --line-buffered -iE "\[calendar\]|app.calendar|AndroidRuntime|Auth|GoogleApi"

# Build release binaries (APK, Mac .dmg, Linux .deb) into dist/
[macos]
dist: apk dmg deb-docker
    mkdir -p dist
    cp androidApp/build/outputs/apk/debug/androidApp-debug.apk dist/Calendar-android.apk
    cp desktopApp/build/compose/binaries/main/dmg/*.dmg dist/Calendar-macos-arm64.dmg
    @ls -la dist/

# Print the debug signing SHA-1 for the Android OAuth client in Google Cloud
sha1:
    "$JAVA_HOME/bin/keytool" -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android | grep SHA1

# Regenerate the Mac/desktop icon (.icns + window PNG) from design/icon.svg
[macos]
icons:
    ./gradlew :shared:jvmTest --tests "app.calendar.IconExportTest" --rerun
    mkdir -p desktopApp/icons desktopApp/src/main/resources
    iconutil -c icns shared/build/icon/Calendar.iconset -o desktopApp/icons/Calendar.icns
    cp shared/build/icon/icon-512.png desktopApp/src/main/resources/icon.png
    cp shared/build/icon/icon-512.png desktopApp/icons/Calendar.png
    @echo "Updated desktopApp/icons/Calendar.icns and desktopApp/src/main/resources/icon.png"

# Delete build outputs
clean:
    ./gradlew clean
