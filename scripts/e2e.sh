#!/usr/bin/env bash
# End-to-end test on an emulator: install the app, drive the accelerometer via the emulator
# console, and check the LAN listener receives the expected state transitions.
set -euo pipefail
cd "$(dirname "$0")/.."

PKG=com.zacaj.posture
APK=${APK:-app/build/outputs/apk/debug/app-debug.apk}
OUT=${OUT:-e2e-out}
PORT=8765
rm -rf "$OUT"; mkdir -p "$OUT"

python3 tools/listener.py --port $PORT --out "$OUT" > "$OUT/listener.log" 2>&1 &
LISTENER=$!
adb logcat -c || true
adb logcat -v time Posture:V PostureNet:V AndroidRuntime:E '*:S' > "$OUT/logcat.txt" &
LOGCAT=$!
trap 'kill $LISTENER $LOGCAT 2>/dev/null || true' EXIT

accel() { adb emu sensor set acceleration "$1" > /dev/null; }

# Root lets the shell start the (non-exported) service directly.
adb root > /dev/null; sleep 2; adb wait-for-device
adb install -r -g "$APK"
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true

prox() { adb emu sensor set proximity "$1" > /dev/null; }

prox 0           # covered = in pocket
accel 0:9.81:0   # standing: gravity along the phone's long axis
adb shell am start-foreground-service -n $PKG/.PostureService -a $PKG.CONFIGURE \
    --es lanUrl "http://10.0.2.2:$PORT" --ez notifyOnChange true

wait_for() { # wait_for <to-state> <timeout-s>
    for _ in $(seq "$2"); do
        grep -q "\"to\": \"$1\"" "$OUT/events.jsonl" 2>/dev/null && { echo "got $1"; return 0; }
        sleep 1
    done
    echo "FAIL: no transition to $1"; return 1
}

wait_for STANDING 30

accel 0:1.5:9.7  # sitting: thigh horizontal
wait_for SITTING 20

# walking: oscillate the acceleration for a while
end=$((SECONDS + 15))
i=0
while (( SECONDS < end )); do
    if (( i++ % 2 )); then accel 1.5:13.5:2.5; else accel -1.5:6.0:-2.5; fi
done &
WALK=$!
wait_for WALKING 20
wait $WALK || true

accel 0:9.81:0
wait_for STANDING 20

# Out of pocket: holding the phone at a "sitting" angle must not change state.
prox 5
sleep 1
accel 0:1.5:9.7
sleep 10
if grep -q '"to": "SITTING"' <(tail -n 1 "$OUT/events.jsonl"); then
    echo "FAIL: state changed while out of pocket"; exit 1
fi
echo "held STANDING while out of pocket"
accel 0:9.81:0
prox 0
sleep 2

# Recorder: flush and confirm a trace file was written with accel rows.
adb shell am start-foreground-service -n $PKG/.PostureService -a $PKG.FLUSH
sleep 3
adb shell "run-as $PKG ls files/traces/ready files/traces/uploaded 2>/dev/null" | tee "$OUT/trace-files.txt"
grep -q 'trace-.*\.csv\.gz' "$OUT/trace-files.txt" || { echo "FAIL: no trace file"; exit 1; }

adb shell am start-foreground-service -n $PKG/.PostureService -a $PKG.STOP
echo "e2e OK"
cat "$OUT/events.jsonl"
