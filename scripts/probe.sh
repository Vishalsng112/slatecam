#!/usr/bin/env bash
# Run the SlateCam capability prober on a connected device and pull the report.
#
#   scripts/probe.sh <adb-serial> [output.json]
#
# Set APK=path/to.apk to install a specific build first; if unset, the debug APK
# under build/app/outputs/flutter-apk/ is installed when present.
#
# PROBE_WAIT controls how long to wait after launch. 20 s is enough for a clean
# device. Devices whose encoder claims Main10 but emits 8-bit Main take longer:
# each failed encode drains before it is rejected (about 150 s for the POCO X6
# Pro), so set PROBE_WAIT=160 there.
set -euo pipefail

serial=${1:?usage: scripts/probe.sh <adb-serial> [output.json]}
out=${2:-probe-${serial}.json}
pkg=io.github.vishalsng112.slatecam
apk=${APK:-build/app/outputs/flutter-apk/app-debug.apk}
wait_seconds=${PROBE_WAIT:-20}

if [[ -f "$apk" ]]; then
  echo "installing $apk on $serial"
  adb -s "$serial" install -r -t -d "$apk" >/dev/null
else
  echo "no APK at $apk; expecting $pkg to be installed already"
fi

adb -s "$serial" shell am force-stop "$pkg"
adb -s "$serial" shell am start \
  -n "$pkg/.MainActivity" --ez run_probe true >/dev/null
echo "probing for ${wait_seconds}s..."
sleep "$wait_seconds"

adb -s "$serial" exec-out run-as "$pkg" cat files/probe.json >"$out"
echo "wrote $out ($(wc -c <"$out") bytes)"
