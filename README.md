# Posture

Personal Android app that infers sitting / standing / walking from the phone's orientation in a
trouser pocket, alerts on changes or when a state lasts too long, and records raw sensor traces
for offline tuning.

## Layout

- `core/` — pure Kotlin, no Android: classifier, debouncing state machine, trace format, replay +
  scoring. All detection logic lives here and is unit-tested on the JVM.
- `app/` — foreground service (sensors → detector → notifications / LAN POST), trace recorder,
  upload worker, Compose UI.
- `tools/listener.py` — LAN listener for events and trace uploads.
- `scripts/e2e.sh` — emulator end-to-end test (run by CI).
- `testdata/traces/` — labeled recordings; every one is replayed by `RecordedTracesTest`.

## How detection works

Accelerometer at 25 Hz, 2 s sliding window:
- std-dev of |accel| ≥ `walkStdThreshold` → walking
- otherwise the angle between mean gravity and the reference axis (phone long axis, or the
  calibrated standing vector), ignoring sign: ≤ 35° standing, ≥ 55° sitting, in between = no change
- a new classification must persist `minDwellMs` (3–4 s) before the state changes
- detection is paused while the proximity sensor is uncovered (phone out of the pocket): the state
  is held, too-long timers keep running, and the window restarts when it goes back in

Tunables are in `DetectorConfig` (`core/.../Model.kt`).

## Commands

```sh
./gradlew :core:test                      # detector tests (incl. all testdata/traces)
./gradlew :core:run --args="trace.csv.gz" # replay a trace and print transitions + score
./gradlew assembleDebug                   # APK (needs Android SDK)
python3 tools/listener.py --port 8765     # LAN listener
```

## Phone setup (Pixel)

1. Install the APK (CI artifact `app-debug`, or `adb install`). Start → allow notifications and
   the battery-optimization exemption.
2. Tap **Calibrate standing**, pocket the phone and stand still: it buzzes 3s after going in,
   records 5s, then double-buzzes (long buzz = moved, try again). Repeat with **Calibrate sitting**
   — with both, sit/stand is decided by whichever calibrated pose is closer.
3. Settings: LAN listener URL, alert thresholds, and optionally a GitHub fine-grained token
   (Contents: read/write on this repo) to upload traces to the `traces` branch.
4. Use the ongoing notification's sitting/standing/walking buttons to label ground truth — labels
   go into the trace and are what the replay tests score against.

## Trace format

Gzipped CSV, `t_ms,kind,x,y,z`; kinds `a` (accel m/s²), `g` (gyro rad/s), `pocket` (1/0), `label`, `state`, `note`.
Files rotate every 30 min and upload hourly on unmetered networks (or via "upload now").
