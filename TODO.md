# TODO

Deferred features, with what the current code already provides for each.

## Planned

- [ ] **Android home-screen widgets** (Jetpack Glance, e.g. "today" and "next event").
  Provided: connected accounts and settings are in SharedPreferences (`calendar_prefs`);
  upcoming events are cached as JSON in `filesDir/event-cache/` (`EventCache`); `AndroidGoogleAuth`
  gets tokens without UI once access is granted. A widget can read the cache directly.
- [ ] **Other providers** (Outlook/Microsoft 365, iCloud/CalDAV).
  Provided: `CalendarProvider` interface; Google and demo data are two implementations already.
- [x] **Offline viewing and instant start**: last-known calendars and events are cached per
  device (`EventCache`) and refreshed in the background.
- [x] **Offline edits**: every change goes through a saved outbox (`OutboxOp`) that is sent in
  order when online, merged per event, and survives restarts.
- [ ] **Conflict handling** for offline edits to events that also changed on another device
  (today the last change sent wins).
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
- [ ] Configurable number of days (e.g. 5-day work week); today: Day/Week/Month on desktop, Day/3 days/Month on phone.
- [ ] Attendees, video-call links, reminders and event colours in the editor.
- [ ] Push updates from Google instead of the 5-minute refresh.
- [ ] Bundle the Inter font for an even closer Notion look.
