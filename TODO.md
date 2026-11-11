# TODO

Deferred features, with what the current code already provides for each.

## Planned

- [ ] **Android home-screen widgets** (Jetpack Glance, e.g. "today" and "next event").
  Provided: connected accounts and settings are in SharedPreferences (`calendar_prefs`);
  `AndroidGoogleAuth` gets tokens without UI once access is granted; `GoogleCalendarApi` is plain
  Kotlin. A widget module can reuse both. Will need a small on-device cache of upcoming events.
- [ ] **Other providers** (Outlook/Microsoft 365, iCloud/CalDAV).
  Provided: `CalendarProvider` interface; Google and demo data are two implementations already.
- [ ] **Offline mode / local database** (show cached events without network, queue edits).
  Provided: edits already go through one optimistic `mutate()` path in `CalendarController`.
- [ ] **Edit all events in a repeating series** (ask "this event / all events" on drag or save).
  Provided: `recurringEventId` is kept on every instance.
- [ ] **iOS app**.
  Provided: all UI and logic is in `commonMain`; needs an iOS target, Google sign-in and storage.
- [ ] **Signed and notarised Mac build** for installing without Gatekeeper warnings.
  Provided: `./gradlew :desktopApp:packageDmg` builds an unsigned `.dmg`.

## Smaller ideas

- [ ] Undo for drags (toast with an "Undo" button).
- [ ] Drag an all-day event into the time grid to give it a time (and back).
- [ ] Pinch or ⌘-scroll to change the hour height.
- [ ] Week view and a configurable number of days.
- [ ] Attendees, video-call links, reminders and event colours in the editor.
- [ ] Push updates from Google instead of the 5-minute refresh.
- [ ] Bundle the Inter font for an even closer Notion look.
