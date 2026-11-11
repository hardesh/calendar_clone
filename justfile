# Calendar app tasks. Run `just` to list them.

# Your JAVA_HOME if it points at a real Java, otherwise Android Studio's bundled one.
export JAVA_HOME := `if [ -x "${JAVA_HOME:-}/bin/java" ]; then echo "$JAVA_HOME"; else echo "/Applications/Android Studio.app/Contents/jbr/Contents/Home"; fi`

adb := env_var("HOME") / "Library/Android/sdk/platform-tools/adb"

# List available recipes
default:
    @just --list --unsorted

# Build everything: shared code, Mac app and Android debug APK
build:
    ./gradlew :shared:compileKotlinJvm :desktopApp:compileKotlin :androidApp:assembleDebug

# Run all tests (logic, Google API client, simulated mouse drags, screenshot renders)
test:
    ./gradlew :shared:jvmTest

# Run one test class or method, e.g. `just test-one MouseDragTest`
test-one name:
    ./gradlew :shared:jvmTest --tests "*{{name}}*"

# Render each view to PNG in shared/build/screenshots
screenshots:
    ./gradlew :shared:jvmTest --tests "app.calendar.ScreenshotTest" --rerun
    @echo "Screenshots: shared/build/screenshots/"

# Build Calendar.app (named and iconed properly in the Dock / Cmd+Tab) and open it
mac: mac-app
    open desktopApp/build/mac/Calendar.app

# Build desktopApp/build/mac/Calendar.app; drag it to /Applications to keep it
mac-app:
    ./gradlew :desktopApp:macApp

# Run the Mac app straight from Gradle (shows as "java" in Cmd+Tab)
mac-dev:
    ./gradlew :desktopApp:run

# Run the Mac app with demo data switched on
mac-demo: mac-app
    open desktopApp/build/mac/Calendar.app --args --demo

# Build a Mac .dmg (needs a full JDK with jpackage, e.g. JAVA_HOME=... just dmg)
dmg:
    ./gradlew :desktopApp:packageDmg
    @echo "DMG: desktopApp/build/compose/binaries/main/dmg/"

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

# Print the debug signing SHA-1 for the Android OAuth client in Google Cloud
sha1:
    "$JAVA_HOME/bin/keytool" -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android | grep SHA1

# Regenerate the Mac/desktop icon (.icns + window PNG) from design/icon.svg
icons:
    ./gradlew :shared:jvmTest --tests "app.calendar.IconExportTest" --rerun
    mkdir -p desktopApp/icons desktopApp/src/main/resources
    iconutil -c icns shared/build/icon/Calendar.iconset -o desktopApp/icons/Calendar.icns
    cp shared/build/icon/icon-512.png desktopApp/src/main/resources/icon.png
    @echo "Updated desktopApp/icons/Calendar.icns and desktopApp/src/main/resources/icon.png"

# Delete build outputs
clean:
    ./gradlew clean
