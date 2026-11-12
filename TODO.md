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
- [ ] **Offline edits** (queue drags/edits made offline and send them when back online).
  Provided: edits go through one optimistic `mutate()` path in `CalendarController`, and the
  cache already stores the optimistic result.
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
