# Calendar

A personal calendar in the style of Notion Calendar, for **macOS**, **Linux** and **Android**, built with
Kotlin Multiplatform and Compose Multiplatform. It connects directly to one or more Google
accounts. There is no server: each device signs in to Google itself.

- Views: **day**, **week** and **month** on Mac/Linux; **1 day**, **3 days** and **month** on the phone
- **Drag to edit**: move events, resize them from the top or bottom edge, drag on empty space to
  create, and drag chips between days in month view. On the phone, **long-press** first, then drag
  (a plain swipe scrolls, and a sideways swipe moves to the next or previous days).
- **Multiple time zones** in the left gutter; add, rename, reorder (the top one is primary) and
  remove them in the sidebar.
- Edits appear immediately and are saved to Google in the background. If Google rejects a change,
  the event moves back and a message says why.
- **Opens instantly and works offline**: the last-known calendars and events are saved on each
  device, shown at start and refreshed from Google in the background. Without a connection you
  can still browse the months you've seen ("Offline" appears next to the title) and keep editing:
  changes are saved on the device and sent to Google in order once you're back online, even
  after a restart. Repeated edits to one event are combined, and an event created and deleted
  while offline never reaches Google. If Google rejects a queued change, the event goes back to
  how it was and a message says why.
- **Event colours**: pick one of Google's 11 event colours (or the calendar's) in the editor;
  it's saved to Google, so Google Calendar shows it too.
- **Zoom** the day/week grid: ⌘+ / ⌘− / ⌘0 or ⌘/Ctrl + scroll on Mac/Linux, pinch on the phone,
  or Settings → Zoom. Remembered per device.
- **Mac menu bar**: your next event ("Software Roadmap · in 1h 25m", or "· now" just after it
  starts). Click it for the rest of today; pick one to open it. Closing the window keeps Calendar
  in the menu bar; quit from its menu or with ⌘Q, and click the Dock icon to bring it back.
- **Phone reminders**: a notification before each timed event (Settings → Notify before events:
  off, 5, 10, 15, 30 or 60 minutes; default 10). They arrive even if the app is closed: an hourly
  background check refreshes the next two days, and reminders are re-armed after a restart.
  Tapping one opens the event. Hidden calendars don't notify.
- Demo data (sidebar → Settings → Demo data) lets you try everything without a Google account;
  the switch disappears once a Google account is connected.

## Download

Ready-made builds are on the [Releases page](https://github.com/hardesh/calendar_clone/releases):

| File | For | Install |
|---|---|---|
| `Calendar-macos-arm64.dmg` | Macs with Apple silicon (M1 and later) | Open it and drag Calendar to Applications. It isn't notarised, so the first time, right-click Calendar → **Open** → **Open** (or run `xattr -dr com.apple.quarantine /Applications/Calendar.app`). |
| `Calendar-linux-amd64.deb` | Ubuntu, Debian and similar on Intel/AMD | `sudo apt install ./Calendar-linux-amd64.deb`, then start "Calendar" from the app menu (or `/opt/calendar-app/bin/Calendar`). |
| `Calendar-android.apk` | Android 8.0+ | Open it on the phone and allow installing from your browser/files app, or `adb install Calendar-android.apk`. |

The Mac and Linux builds come with this project's Google client built in, so sign-in works as
long as your Google address is allowed in that project (see "Google setup"). To use **your own**
Google Cloud project instead, see [Using your own Google client](#using-your-own-google-client).

## Project layout

```
shared/        All UI, drag logic, Google Calendar client and state (Kotlin Multiplatform)
  src/commonMain   shared code
  src/androidMain  Android sign-in (Google Play services), storage
  src/jvmMain      Mac/Linux sign-in (browser + Keychain/keyring), storage
  src/jvmTest      unit tests, simulated mouse-drag tests, screenshot renders
androidApp/    Android app shell (MainActivity, manifest, icon)
desktopApp/    Mac/Linux app entry point, icons, packaging
design/        icon source (icon.svg)
scripts/       build-deb-in-docker.sh (Linux package from any Docker host)
TODO.md        features deferred for later
```

## Tooling

Android Studio provides the Java runtime and Android SDK. Common tasks are in the `justfile`
(install `just` with `brew install just`, or your Linux package manager). It uses your `JAVA_HOME`
if that works, otherwise Android Studio's bundled Java, otherwise `java` on your PATH. Run `just`
to list everything; Mac-only and Linux-only recipes only appear on that system.

## Run it

| Command | What it does |
|---|---|
| `just build` | Build the shared code, Mac app and Android APK |
| `just test` | Run all tests (logic, Google API client, rollback, simulated mouse drags) |
| `just test-one MouseDragTest` | Run tests whose name matches |
| `just screenshots` | Render each view to PNG in `shared/build/screenshots/` |
| `just mac` | Build `Calendar.app` and open it (named and iconed properly in the Dock and Cmd+Tab) |
| `just mac-demo` | Same, with demo data switched on (no Google setup needed) |
| `just mac-app` | Only build `desktopApp/build/mac/Calendar.app`; drag it to Applications to keep it |
| `just mac-dev` | Run straight from Gradle (quicker, but shows as "java" in Cmd+Tab) |
| `just icons` | Regenerate the Mac icon from `design/icon.svg` |
| `just android` | Build, install and open on the connected phone (USB debugging on) |
| `just android-demo` | Same, with demo data switched on |
| `just devices` | List connected phones/emulators; pass a serial to pick one: `just android <serial>` |
| `just logs` | Android warnings and errors from the app and Google sign-in |
| `just sha1` | Print the debug signing SHA-1 for the Android OAuth client |
| `just apk` | Build the Android debug APK only |
| `just dmg` | Build a Mac `.dmg` (macOS; see below) |
| `just linux` / `just linux-demo` | Run the app on Linux |
| `just deb` / `just rpm` | Build a Linux package on Linux (see below) |
| `just deb-docker` | Build the Linux amd64 `.deb` in Docker, from a Mac or anywhere |
| `just dist` | Build all release files into `dist/` (macOS + Docker) |
| `just clean` | Delete build outputs |

**Packaging** (`dmg`, `deb`, `rpm`) needs a full JDK 21+ with `jpackage`; Android Studio's
bundled Java doesn't include it. The justfile finds Homebrew's automatically, or set
`PACKAGE_JAVA_HOME`:

- macOS: `brew install openjdk@21`
- Ubuntu/Debian: `sudo apt install openjdk-21-jdk fakeroot` (and `rpm` for `.rpm`)
- From a Mac without Linux: `brew install colima docker && colima start --vm-type vz --vz-rosetta`,
  then `just deb-docker`.

Packages are built for the CPU of the machine (or container) building them. `just dist` makes an
Apple-silicon `.dmg` and an amd64 `.deb`.

### Linux notes

- Settings live in `~/.config/calendar-app/`. Google refresh tokens go in your desktop keyring
  through `secret-tool` (install `libsecret-tools`); without a keyring they go in a file there
  that only your user can read.
- Sign-in opens your default browser (`xdg-open`).

### Keyboard shortcuts (Mac)

| Key | Action |
|---|---|
| `D` or `1` / `W` or `7` / `M` | Day / Week / Month |
| `T` | Today |
| `←` `→` (or `J` `K`) | Previous / next |
| `Esc` | Cancel a drag in progress, or close the editor |
| `⌘R` | Refresh |
| `⌘+` / `⌘−` / `⌘0` | Zoom in / out / reset (also ⌘ + scroll) |
| `⌘↩` | Save in the editor |

## Google setup (one time, about 10 minutes)

Google only gives calendar access to an app that is registered in Google Cloud, even a personal
one. This is free and needs no review: while the app is in "Testing", only the Google addresses you
list can sign in.

1. Go to <https://console.cloud.google.com/>, create a project (e.g. "Calendar").
2. **APIs & Services → Library** → enable **Google Calendar API**.
3. **Google Auth Platform → Branding / Audience** (the "OAuth consent screen"): choose **External**,
   fill in the app name and your email. Under **Audience → Test users**, add every Google address
   you want to connect.
4. **Data access** → add scopes `.../auth/calendar.events` and
   `.../auth/calendar.calendarlist.readonly` (plus `openid`, `email`, `profile`).
5. **Clients → Create client** twice:
   - **Desktop app**. Copy its client ID and secret into `local.properties` (git-ignored):
     ```properties
     google.desktop.clientId=1234-abc.apps.googleusercontent.com
     google.desktop.clientSecret=GOCSPX-...
     ```
     (For an installed desktop app this "secret" is not truly secret; Google expects it to ship
     with the app. Refresh tokens are what matter, and those go in the Keychain or keyring.)
     Builds made from this checkout (`just mac`, `just dmg`, `just deb`) include this client.
   - **Android**. Package name `app.calendar`, and the SHA-1 of your debug signing key:
     ```bash
     just sha1
     ```
     Release builds are signed with the same debug key, so this one client covers both.
     No client ID goes into the Android app; Google matches it by package name and SHA-1.
6. Run the app and click **Add Google account** in the sidebar. Repeat for each account.

**Refresh tokens in Testing mode expire after 7 days** (Google's rule), so the Mac app will ask you
to reconnect weekly. To avoid that, set the app to **In production** under Audience. For personal
use you don't need Google's verification; you'll just click past an "unverified app" warning when
signing in. Android is not affected the same way, because Play services manages its tokens.

## Using your own Google client

The released Mac and Linux builds include this project's Google client. To make a downloaded
build use your own Google Cloud project instead (for example if you're not on its test-user list):

1. Do steps 1–5 of "Google setup" above in your own Google account; you only need the
   **Desktop app** client.
2. Create a file named `google-oauth.properties` with your client:
   ```properties
   clientId=1234-abc.apps.googleusercontent.com
   clientSecret=GOCSPX-...
   ```
3. Put it in the app's settings folder and restart Calendar:
   - macOS: `~/Library/Application Support/CalendarApp/google-oauth.properties`
   - Linux: `~/.config/calendar-app/google-oauth.properties`

The app uses, in order: that file, the `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` environment
variables, then the built-in client. The Android app can't be pointed at another project after it
is built: it is matched to Google by package name and signing key, so build it yourself with your
own Android client.

## How sign-in works

- **Mac and Linux**: the app opens Google's sign-in page in your browser (OAuth with PKCE),
  receives the answer on a one-time `127.0.0.1` listener, and stores the refresh token in the
  macOS Keychain (service "CalendarApp Google") or the Linux keyring. Settings live in
  `~/Library/Application Support/CalendarApp/` or `~/.config/calendar-app/`.
- **Android**: the system account picker chooses which Google account on the phone to add, then
  Google Play services grants calendar access and supplies tokens. The app stores only the list of
  connected emails (in SharedPreferences, where a home-screen widget can read it later).

## Notes

- Saved events live in the app's data folder (`cache/` on Mac/Linux, app storage on Android) and
  are cleared when you disconnect an account. Demo data is never saved.

- Dragging one occurrence of a repeating event changes only that occurrence.
- Calendars you can only view are shown but can't be dragged or edited.
- Timed events of 24 hours or more show in the all-day row, like Google and Notion do.
