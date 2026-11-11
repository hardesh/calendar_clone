# Calendar

A personal calendar in the style of Notion Calendar, for **macOS** and **Android**, built with
Kotlin Multiplatform and Compose Multiplatform. It connects directly to one or more Google
accounts. There is no server: each device signs in to Google itself.

- Views: **1 day**, **3 days**, **month**
- **Drag to edit**: move events, resize them from the top or bottom edge, drag on empty space to
  create, and drag chips between days in month view. On the phone, **long-press** first, then drag
  (a plain swipe scrolls, and a sideways swipe moves to the next or previous days).
- **Multiple time zones** in the left gutter; add, rename, reorder (the top one is primary) and
  remove them in the sidebar.
- Edits appear immediately and are saved to Google in the background. If Google rejects a change,
  the event moves back and a message says why.
- Demo data (sidebar → Settings → Demo data) lets you try everything without a Google account.

## Project layout

```
shared/        All UI, drag logic, Google Calendar client and state (Kotlin Multiplatform)
  src/commonMain   shared code
  src/androidMain  Android sign-in (Google Play services), storage
  src/jvmMain      Mac sign-in (browser + Keychain), storage
  src/jvmTest      unit tests, simulated mouse-drag tests, screenshot renders
androidApp/    Android app shell (MainActivity, manifest, icon)
desktopApp/    Mac app entry point
TODO.md        features deferred for later
```

## Tooling

Android Studio provides the Java runtime and Android SDK. Common tasks are in the `justfile`
(install `just` with `brew install just`); it uses Android Studio's bundled Java automatically
unless your `JAVA_HOME` points to a working JDK. Run `just` to list everything.

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
| `just dmg` | Build a Mac `.dmg` (see below) |
| `just clean` | Delete build outputs |

The `.dmg` lands in `desktopApp/build/compose/binaries/main/dmg/`. It needs a full JDK 21+ with
`jpackage`, which Android Studio's bundled Java doesn't include. For example:

```bash
brew install openjdk@21
```

```bash
JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home" just dmg
```

### Keyboard shortcuts (Mac)

| Key | Action |
|---|---|
| `D` or `1` / `3` / `M` | Day / 3 days / Month |
| `T` | Today |
| `←` `→` (or `J` `K`) | Previous / next |
| `Esc` | Cancel a drag in progress, or close the editor |
| `⌘R` | Refresh |
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
     with the app. Refresh tokens are what matter, and those go in the macOS Keychain.)
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

## How sign-in works

- **Mac**: the app opens Google's sign-in page in your browser (OAuth with PKCE), receives the
  answer on a one-time `127.0.0.1` listener, and stores the refresh token in the macOS Keychain
  (service "CalendarApp Google"). Settings live in `~/Library/Application Support/CalendarApp/`.
- **Android**: the system account picker chooses which Google account on the phone to add, then
  Google Play services grants calendar access and supplies tokens. The app stores only the list of
  connected emails (in SharedPreferences, where a home-screen widget can read it later).

## Notes

- Dragging one occurrence of a repeating event changes only that occurrence.
- Calendars you can only view are shown but can't be dragged or edited.
- Timed events of 24 hours or more show in the all-day row, like Google and Notion do.
