# Code review — 2026-11-18

Four review passes (UI/design, bugs/correctness, security/privacy, simplification) over the
whole project. Findings are the reviewers'; they were not built or re-verified line by line.
Line numbers are as of commit `0296e98`.

**Approved to do:** A1–A13, B3–B8, D1–D2.
**Rejected:** B1, B2 (export redaction, import confirmation).
**Not yet decided:** A14, C1–C14, D3–D9 (listed at the end).

Paths below are relative to `app/src/main/java/com/kj7nye/lorafieldops/` unless they start
with `app/`, `.github/`, `gradle/` or `settings.gradle.kts`.

---

## A. Bugs (approved)

### A1. Captive-portal 200 deletes queued entries — S
- `logger/CoverageUploader.kt:121-126`: `runCatching { decode }.getOrDefault(DeviceLogResponse())`
  then `queue.delete(...)`, so any HTTP 200 (hotel/captive portal HTML) counts as success and up
  to 500 entries are lost.
- `logger/CoverageLogger.kt:311-315` `isOnline()` only checks `NET_CAPABILITY_INTERNET`.
- **Fix:** on JSON parse failure return `UploadResult.Retry("Unexpected reply from website…")`
  without deleting. Require `NET_CAPABILITY_VALIDATED` in `isOnline()`.
- Tests: all existing 200 responders in `CoverageUploaderTest` return valid JSON, so none break.
  Add a test: 200 with `<html>` body → `Retry`, queue unchanged.

### A2. Foreground service dropped on BLE reattach; can't restart in background — M
- `logger/CoverageLogger.kt:133-135` (`attach` sets `reconnecting=false`, `_tracker=null`),
  `:194-199` (blank device → "Reading the tracker's callsign…", `logging=false`),
  `:322-339` (`serviceWanted` false → `stopService`).
- With the screen off, the later `startForegroundService` throws
  `ForegroundServiceStartNotAllowedException` (Android 12+), is caught and logged, and logging
  silently dies. Same flicker on any settings-driven `restartSession`.
- `LoggerService.running` is only cleared in `onDestroy`, so a quick stop→start can skip the start.
- **Fix:** `serviceWanted = isEnabled && (transport != null || logging || reconnecting)`. Keep
  `reconnecting` true until `logging` is established (don't clear it in `attach`; clear it when
  `runSession` sets `logging = true` or when the session ends without a link).

### A3. Rotation reconnects USB and drops unsaved edits — S
- `MainActivity.kt:68`, `:81-96`: `onCreate` always calls `handleUsbIntent(intent)`; after
  recreation the intent is still `USB_DEVICE_ATTACHED`, so `vm.connectUsb(driver)` reruns
  (close/reopen port, `enterSetupAndLoad`, dirty flag cleared).
- **Fix:** only handle when `savedInstanceState == null`; also skip if the VM is already
  connected/connecting; clear the intent action after handling (`intent.action = null` /
  `setIntent(Intent())`). Check `onNewIntent` for the same guard.

### A4. A 404 for one device blocks uploads for all devices — M
- `logger/CoverageUploader.kt:134`, `logger/LogQueueDb.kt:66-69`: `nextBatch` always takes the
  oldest row's device; a 404 returns `Blocked` without removing anything, so newer rows for a
  valid device never upload. Other 4xx (400, 403) fall to `Retry` forever.
- **Fix:** on 404, skip that device for the rest of the pass (e.g. `nextBatch(limit, exclude: Set<String>)`
  → `SELECT … WHERE device NOT IN (:exclude)`), report it, and keep going; return `Blocked` only
  if every device was rejected. Treat other unexpected 4xx as drop-with-error after N attempts
  (or park them like 404).
- Tests: `unknownDeviceBlocks` still holds for a single device; add "404 on device A, B still
  uploads". `FakeQueue.nextBatch` in the test needs the same exclude parameter.

### A5. SerialManager emits a spurious `Lost` after intentional close/reopen — S
- `serial/SerialManager.kt:145-152`, `:167-180`: `close()` cancels the scope then closes the
  port; the blocking `port.read` throws and the catch unconditionally emits `ConnectionEvent.Lost`.
  On re-open, the stale `Lost` reaches the new session and kills it. `port` isn't `@Volatile`.
- **Fix:** capture `port`/scope locally in the read loop; emit `Lost` only if `isActive` and the
  port is still the current one. Mark `port` `@Volatile`.

### A6. Setup handshake blocks the connection-event collector — M
- `ui/ConfigViewModel.kt:207-217`: `enterSetupAndLoad` (entry up to 15 s × 3, then export and
  version) runs inside `connectionEvents.collect`, so `Lost`/`Error` aren't processed meanwhile;
  UI shows Connected, logger stays attached, reconnect is delayed ~30 s+.
- **Fix:** launch `enterSetupAndLoad` in its own `Job`; cancel it in the `Lost`/`Error` branches
  and in `disconnect()`.

### A7. `ProtocolHandler.close()` leaves awaiting callers hanging — S
- `serial/ProtocolHandler.kt:138-139`, `:190`: `close()` cancels the scope that owned the timeout;
  `sendCommand` waits on `cmd.await()` forever (e.g. `_wifiScanning` stuck true). A cancelled
  caller also leaves its command queued, to be written later.
- **Fix:** in `close()`, drain `cmdQueue` and the pending command, completing each with
  `Err("closed")`. Put the timeout in `sendCommand` itself (`withTimeoutOrNull`). Remove a
  command from the queue if its caller is cancelled.

### A8. USB resource leaks — S
- `serial/SerialManager.kt:111-136`: if `p.open`/`setParameters` throws, the `UsbDeviceConnection`
  isn't closed.
- `serial/SerialManager.kt:76-91`: the permission receiver is unregistered only when a broadcast
  arrives; repeated calls register duplicates, each firing `onResult`.
- **Fix:** close the connection in the catch. Keep a single receiver field; unregister the old one
  before registering, and in `close()`.

### A9. Uncaught logger exceptions crash; failed inserts lose rows — S
- `logger/CoverageLogger.kt:65-67`, `:207-236`, `:264-266`: no `CoroutineExceptionHandler`;
  `runUpload` only handles `IOException` (inside the uploader), so `SQLiteException`,
  serialization errors etc. crash the process (also via `uploadNow()`).
- `logger/CoverageLogger.kt:106`: a failed `dao.insert` is logged and the rows dropped.
- **Fix:** add a `CoroutineExceptionHandler` to `scope` (log + set `lastError`); wrap `runUpload`
  body in try/catch → `Retry`. Retry inserts a few times with backoff before giving up.

### A10. Non-atomic `_runtime` updates — S
- `logger/CoverageLogger.kt:134,145,150,190,197,224,228,245,255`: `_runtime.value = _runtime.value.copy(…)`
  from several threads loses updates (counts, `logging`/`reconnecting`).
- **Fix:** `_runtime.update { it.copy(…) }` everywhere.

### A11. BLE drop during pairing / settle window — M
- `serial/BleTransport.kt:211-263`, `:130-142`, `:386-397`: the disconnect callback only
  completes `pendingOperation`; during `ensureBonded` there is none, so open waits up to
  `BOND_TIMEOUT_MS` (90 s). A drop inside the 300 ms `LINK_SETTLE_MS` emits no `Lost` and a dead
  link is reported `Opened`.
- **Fix:** a "link dead" `CompletableDeferred` completed from the disconnect callback, raced
  against the bond wait (`select`) and checked before emitting `Opened` (also re-check
  `gatt != null`).

### A12. UTF-8 characters split across BLE chunks get garbled — S
- `ui/ConfigViewModel.kt:249` and `serial/ProtocolHandler.kt:207` decode each chunk separately;
  BLE chunks can be 20 bytes, so multi-byte chars (e.g. `→`, `°`) split across notifications.
- **Fix:** one stateful `CharsetDecoder` per stream (keep leftover bytes between chunks). Ideally
  move the line assembly at `ConfigViewModel.kt:246-271` into a pure `LineAssembler` class with
  unit tests (split multi-byte char, CR/LF variants).

### A13. Log screen stops auto-scrolling at 1000 lines — S
- `ui/LogScreen.kt:51` keys the scroll effect on `logLines.size`; the buffer is capped at 1000
  (`ConfigViewModel.kt:261`), so once full the size stops changing.
- **Fix:** key on `logLines` (the list instance) or a monotonically increasing line counter.

---

## B. Security / privacy (approved: B3–B8)

### B3. Release workflow hardening — S
`.github/workflows/release.yml`
- Pin `actions/checkout@v4`, `actions/setup-java@v4`, `gradle/actions/setup-gradle@v4`
  (`:9,15,19,24,29`) to full commit SHAs; add Dependabot for `github-actions`.
- Script injection: `tag="${{ github.event.release.tag_name }}"` (`:39`, also `:67`, `:69`).
  Pass via `env: TAG: ${{ github.event.release.tag_name }}` and use `"$TAG"`.
- Least privilege: build job `contents: read`; a separate upload job with `contents: write` that
  downloads the APK artifact and runs `gh release upload`.
- Delete the keystore from `$RUNNER_TEMP` in an `if: always()` step after the build.
- Move signing secrets into a GitHub Environment (e.g. `release`) with required reviewers /
  tag-only rule; drop `workflow_dispatch` (`:6`) or skip signing in it. *(Environment setup is a
  GitHub settings change, done by hand.)*

### B4. JitPack can supply any dependency — S
- `settings.gradle.kts:19-20`: wrap in
  `exclusiveContent { forRepository { maven("https://jitpack.io") }; filter { includeGroup("com.github.mik3y") } }`.
- Add dependency verification: `./gradlew --write-verification-metadata sha256 help` →
  commit `gradle/verification-metadata.xml`.

### B5. Backups include queue and logger prefs — S
- `app/src/main/AndroidManifest.xml:33-35`: `allowBackup="true"` with no rules.
- **Fix:** add `android:dataExtractionRules="@xml/data_extraction_rules"` (API 31+) and
  `android:fullBackupContent="@xml/backup_rules"` excluding
  `sharedpref/coverage_logger_secure.xml`, `sharedpref/coverage_logger.xml` and
  `database/coverage_log_queue.db*` (incl. `-wal`/`-shm`). Or simply `allowBackup="false"`.

### B6. Setting-change notes can leak location — S
- `model/SettingNotes.kt:16,52-63` (called from `ui/ConfigViewModel.kt:449-457`) only filters
  `password|pin|passcode|token`. Fixed-position lat/lon/elevation, Wi-Fi SSIDs, APRS-IS
  server/filter, BT name are uploaded.
- **Fix:** also exclude `ssid` and `fixed.*` lat/lon/alt, or switch to an allow-list (keys in
  `LABELS`). Update the UI text at `ui/CoverageLoggerScreen.kt:205` and README to match. Extend
  `SettingNotesTest`.

### B7. CR/LF in text fields splits device commands — S
- `ui/ConfigViewModel.kt:995-1042` (`sendField`/`sendRequiredField`), `serial/ProtocolHandler.kt:276`.
- **Fix:** strip or reject `\r`, `\n` and other control chars in `sendField`, and defensively in
  `ProtocolHandler.sendCommand`.

### B8. Website address not validated — S
- `ui/LoggerViewModel.kt:34-40`, `logger/CoverageUploader.kt:100-103`.
- **Fix:** on save require `URL(...).protocol == "https"` with a clear error; confirm (or clear the
  key) when the host changes away from `apps.k7swi.org`. Optional
  `res/xml/network_security_config.xml` with `cleartextTrafficPermitted="false"`.

---

## D. Code health (approved: D1–D2)

### D1. CI never runs unit tests — S
- `.github/workflows/release.yml:57-59` only runs `assembleRelease`.
- **Fix:** add `./gradlew testDebugUnitTest` before assemble, and a new
  `.github/workflows/ci.yml` on `pull_request` + `push` that runs
  `./gradlew testDebugUnitTest assembleDebug` (JDK 17, setup-gradle, SHA-pinned per B3).

### D2. Dead code and small cleanups — S
- `serial/ProtocolHandler.kt:44,62,209-212`: `SerialMode`/`mode` written, never read; line 210
  repeats `"SETUP MODE ACTIVE"` instead of `BANNER_SETUP`.
- `logger/LogQueueDb.kt:46`: `LogQueueDao.count()` unused.
- `logger/PacketRecorder.kt:37`: `reset()` unused (its doc comment references it).
- Unused imports: `MainActivity.kt:13,23,30` (`ArrowBack`, `IconButton`, `TopAppBar`),
  `logger/CoverageUploader.kt:12` (`SerialName`).
- `ui/ConnectionScreen.kt:169-178`: redundant `hasPermission` check (`requestPermission` already
  short-circuits) → then `SerialManager.hasPermission` (`serial/SerialManager.kt:59`) can go.
- `ui/ConfigViewModel.kt:974-986`: orphaned doc comment for `sendField` sits above
  `sendRequiredField`; move/merge into `sendField` (`:1009-1015`).
- `MainActivity.kt:81-96`: `handleUsbIntent` re-implements `SerialManager.findDevices()`; use
  `IntentCompat.getParcelableExtra` here and in `serial/BleTransport.kt:218-223`.

---

## Suggested order
A1, A13, A10, A12 (quick wins) → A5, A7, A8 (serial) → A3, A6 (ViewModel/Activity) →
A2, A9, A4 (logger) → A11 (BLE) → B7, B6, B8, B5 → B4, B3 + D1 → D2.

Verification: `./gradlew testDebugUnitTest assembleDebug`. (In the cloud session the Android SDK
download from dl.google.com was blocked, so nothing has been compiled yet.)

---

## Not yet decided

**A14** — Import sheet and MainNavigation both consume `vm.snackMessage`; success detected by
string matching (`ui/ImportExportSheet.kt:104-151,171`, `MainActivity.kt:111-116`). Expose an
`importState` StateFlow instead. (M)

**UI/UX**
- C1 Save/Discard progress + double-tap guard (`ui/ConfigScreen.kt:147-163`) — M
- C2 Nested Scaffolds; snackbar covers Save (`ui/ConfigScreen.kt:132-165`) — M
- C3 Sunlight readability: larger key values, ≥14 sp body, log ≥13 sp — M
- C4 Full light/dark color scheme, DayNight XML theme, real launcher icon — S
- C5 48 dp touch targets (copy button, Wi-Fi scan rows, section headers) — S
- C6 Config hierarchy: Beacon expanded, collapsed summaries, Advanced group, dirty markers — M
- C7 Move Disconnect / Import-Export out of the bottom nav — S
- C8 Status: scrollable, load all on entry, per-card spinners (`ui/StatusScreen.kt:52-60`) — S
- C9 De-emphasize "Enter OTA DFU mode" (`ui/StatusScreen.kt:142`) — S
- C10 Connect screen retry/cancel; USB permission denied message — S
- C11 Coverage logger screen: status on top, saved feedback, clearer errors — S
- C12 Accessibility: distinct APRS-IS icon, no emoji icons, merged semantics — S
- C13 Pause log auto-scroll when scrolled up; "Jump to latest" — S
- C14 Move hardcoded strings to `strings.xml` — L

**Code health**
- D3 Tests for ProtocolHandler, status parsers, FirmwareVersion, field editing — M
- D4 Dedupe result→snackbar handling in ConfigViewModel; merge `txCommentNow`/`txStatusNow` — S
- D5 Enums for role / GPS source / digi mode; named constants — S–M
- D6 Split `ConfigScreen` into section composables + shared `Fields.kt` — M
- D7 Split the 1141-line `ConfigViewModel` — L
- D8 Dependency tidy: unused deps, deprecated icons/APIs, Room schema export, Java 17 — S
- D9 Consistent ViewModel API; `collectAsStateWithLifecycle` — M
