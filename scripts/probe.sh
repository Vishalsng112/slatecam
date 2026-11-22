#!/usr/bin/env bash
# Run the SlateCam capability prober on a connected device and pull the report.
#
#   scripts/probe.sh <adb-serial> [output.json]
#
# Set APK=path/to.apk to install a specific build first; if unset, the debug APK
# under build/app/outputs/flutter-apk/ is installed when present.
set -euo pipefail

serial=${1:?usage: scripts/probe.sh <adb-serial> [output.json]}
out=${2:-probe-${serial}.json}
pkg=io.github.vishalsng112.slatecam
apk=${APK:-build/app/outputs/flutter-apk/app-debug.apk}

if [[ -f "$apk" ]]; then
  echo "installing $apk on $serial"
  adb -s "$serial" install -r "$apk" >/dev/null
else
  echo "no APK at $apk; expecting $pkg to be installed already"
fi

adb -s "$serial" shell am force-stop "$pkg"
adb -s "$serial" shell am start -n "$pkg/.MainActivity" >/dev/null
echo "probing..."
sleep 6

adb -s "$serial" exec-out run-as "$pkg" cat files/probe.json >"$out"
echo "wrote $out ($(wc -c <"$out") bytes)"
