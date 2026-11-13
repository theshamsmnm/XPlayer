# X Player — native Android music player

Kotlin · Jetpack Compose (Material 3) · Media3 ExoPlayer + MediaSessionService · Room · WorkManager · Coil · DataStore · Coroutines/Flow.
No INTERNET permission, no WebView, no cloud services. minSdk 26, targetSdk/compileSdk 35.

> **Status: this project has NOT been compiled, run, or tested.** It was written without an Android SDK or network
> access. Expect to fix a few compile errors on first sync, and treat all behaviour (and all performance
> characteristics) as unverified until you have run it. Unit and instrumented tests are included but have never been executed.

## Build
1. Open the `XPlayer` folder in a recent Android Studio (Ladybug or newer), with JDK 17 and Android SDK 35 installed.
2. Let Gradle sync (wrapper 8.10.2, AGP 8.7.3, Kotlin 2.0.21, KSP). Versions live in `gradle/libs.versions.toml`.
3. Run the `app` configuration on a device/emulator (API 26+).
4. Tests: `./gradlew testDebugUnitTest` (JVM) and `./gradlew connectedDebugAndroidTest` (device/emulator).

## How music discovery works
First launch shows a short rationale, then asks for `READ_MEDIA_AUDIO` (Android 13+) or `READ_EXTERNAL_STORAGE` (≤ 12).
After the grant, `MediaStoreScanner` reads the audio collection and `LibrarySynchronizer` diffs it into Room in one transaction:
- new files are added, changed files updated, vanished files flagged `isAvailable = 0` (rows are never deleted by a scan),
- moved files are relinked by a fingerprint (`title|artist|album|seconds`) so favorites/collections/artwork stay attached,
- a MediaStore generation token lets unchanged libraries skip the pass.

Triggers: first grant, a debounced `ContentObserver` while the app is alive, a 6-hour periodic WorkManager job, and **Settings → Rescan**.

## Architecture
- `core/` utilities, permissions · `domain/` models and pure sorting · `data/` Room (`db`), MediaStore, sync, artwork files, repositories, backup
- `playback/` `PlaybackService` (owns the ExoPlayer, audio focus, queue persistence, history) and `PlaybackController` (UI-side MediaController wrapper)
- `ui/` theme, shared components, reorderable lists · `feature/` screens + ViewModels · `navigation/`
- Manual DI through `AppContainer`.

State ownership: scanner owns `artists/albums/tracks`; everything the user organises (collections, favorites, history, custom artwork,
library order, layout prefs and locks, hidden tracks, queue) lives in separate tables that a scan never writes.
Custom artwork is stored as files (full + thumbnail) with a version number in Room; bitmaps are never stored in the database.

## Features
Grid library (2–4 columns, size/spacing, titles/artists toggles), eight sort modes, drag-and-drop custom order, per-scope layout lock,
albums/artists/collections, favorites and history, debounced offline search, artwork pick/crop/restore (shown in grid, mini/full player,
queue, notification, lock screen), full player with queue editing, sleep timer, multi-select batch actions, hide/restore, duplicate and
missing-file tools, safe delete and metadata editing (Android 11+ via system consent), versioned atomic backup/restore (`.xpbackup`),
AMOLED/Dark/Light themes, English and Persian (RTL).

## Known limitations
- Deleting files and editing tags require Android 11+ (system write/delete request flow).
- Backup restore matches tracks by fingerprint; unmatched entries are skipped and counted.
- Albums inside collections are not reorderable (tracks are).
- Nothing here has been measured for performance or verified on a device.
