# Recipe: integrate a new sensor

**Goal:** the study's real device streams data into the pipeline.
**Contract:** `SensorSource` (`mbpulse/collection/SensorSource.kt`) — a **cold**
`Flow<SensorSample>`; each `SensorSample(timestampMs, signals: Map<String, Float?>)`.
The library never sees the device — only this flow. All driver code lives in
the **host app**.

## Steps

1. **Prove the pipeline first, without hardware.** Run the template as-is:
   `CsvReplaySensorSource` + `sample_data.csv` must fill the home-screen cards.
   Any problem now is configuration, not your driver.
2. **Fix the signal vocabulary.** Choose signal names (e.g. `hr`, `acc_x`,
   `temp`) — they are the contract between driver and processor. If you have a
   recording of the real device, export it as CSV (`timestamp_ms` + one column
   per signal), drop it in `src/main/assets/`, and point `CsvReplaySensorSource`
   at it — now the processor is developed against real data before the driver
   exists.
3. **Write the driver** in the host app (next to `StudyConfig.kt`). BLE shape:
   ```kotlin
   class MyDeviceSource(private val context: Context) : SensorSource {
       override fun samples(): Flow<SensorSample> = callbackFlow {
           val device = /* connect; subscribe to notifications */
           device.onReading = { ts, hr ->
               trySend(SensorSample(ts, mapOf("hr" to hr)))
           }
           awaitClose { device.disconnect() }   // runs on cancel/stop
       }
   }
   ```
   Rules: emit monotonically increasing `timestampMs`; keep signal names
   identical across the session; a new independent stream per `samples()` call
   (the factory in config is invoked once per session); clean up in `awaitClose`.

   **Bluetooth LE wearable:** start from `template-app/.../BleSensorSource.kt`
   instead — it already connects to the device the participant chose,
   follows changes to that choice and reconnects with backoff. Fill in its
   `TODO(agent)`s: `SERVICE_UUID` / `CHARACTERISTIC_UUID` (the device's GATT
   service and data characteristic) and `decode(bytes) → signals`. Then turn
   on the library's device step, which lists only devices advertising that
   service:
   `deviceSelection = DeviceSelection(serviceUuids = listOf(BleSensorSource.SERVICE_UUID))`.
   Debug builds can keep the CSV replay until a device is chosen
   (`BleSensorSource(context, whenNoDevice = { … })`).
4. **Permissions** (host manifest): BLE with the device step needs
   `BLUETOOTH_CONNECT` (API 31+) — the step requests it, and the Companion
   Device Manager chooser needs no `BLUETOOTH_SCAN` or location — plus, for
   Android 11 and lower, `BLUETOOTH` and `BLUETOOTH_ADMIN` with
   `android:maxSdkVersion="30"` (without them the device step and
   `connectGatt` fail there). All are in the template manifest's commented
   Bluetooth block; the handoff check fails when one is missing. Also declare
   `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND` and
   `REQUEST_COMPANION_RUN_IN_BACKGROUND` (commented in the template manifest).
   A driver that scans itself needs `BLUETOOTH_SCAN` and a runtime request in
   your own UI. The collection foreground service is already declared by the library.
5. **Swap the factory** in the host config:
   `sensorSource = { MyDeviceSource(applicationContext) }`.
6. **Align the processor** — it must read the same signal names
   (`sample.get("hr")`).

## Verification checklist

Collection starts differently per `collectionMode` — check the one the study uses:

- **Passive** (`PASSIVE_CONNECTED_DEVICE` / `PASSIVE_HEALTH`, the default):
  nothing to press. After onboarding (and on every app start, and after a
  reboot) collection starts by itself — the "Recording health data"
  notification appears and the home screen's status panel says recording.
- **Session** (`SESSION`): start and stop it on the collection screen.

- [ ] `.\gradlew.bat assembleDebug` (in the app's folder) succeeds.
- [ ] `.\gradlew.bat testDebugUnitTest` passes (HandoffConformanceTest — none
      skipped). Point its `REPLAY` at a short recording of the real device:
      the processor must emit every study schema from it, timed with the
      samples. Read the audit list in `build/reports/handoff-check.md`.
- [ ] With the CSV source: collection runs (passive: on its own; session:
      after Start) → a new reading about every 30 s; cards fill; history
      chart grows. (Debug builds also show the epoch counter on the
      collection screen.)
- [ ] With the device: same observations, plus logcat shows no
      `EpochEncryptor` warnings (a warning names a schema that is being
      dropped, unmapped or not consented — fix `schemaToConsentType`).
- [ ] Leave the app (home button / screen off) mid-recording: collection
      continues (the foreground-service notification stays), and the cards
      have newer readings when you return.
- [ ] Passive only: after a reboot, collection resumes without opening the app.
- [ ] Device step (if on): after onboarding the "Your device" screen appears;
      the system chooser lists the device; after choosing it the home
      screen's device card names it and readings arrive. "Change" → another
      device switches the running session; withdrawal clears the choice.
- [ ] Records upload: the home screen's upload card shows them synced; rows
      appear in `encrypted_records`.
