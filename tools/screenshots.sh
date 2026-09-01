#!/usr/bin/env bash
# Captures the Play store screenshots from a running emulator.
#
# Modelled on `tools/screenshots.sh` in `Valuya/sdk`, and it keeps that script's
# one hard-won trick: a capture under MIN_BYTES is treated as "not drawn yet"
# and retried, because a resumed activity is not a drawn one and a flat-colour
# PNG of an undrawn screen compresses to almost nothing. A blank screenshot is
# the kind of thing that gets noticed after upload rather than before.
#
# What it does *not* share is driving by keyboard. Crakinoku answers a D-pad;
# Breadbin's screens are Compose and its emulator view is a surface that takes
# touches, so this walks by coordinate — but it reads the toolbar's positions
# out of a uiautomator dump by content description rather than hard-coding
# them, so a toolbar that gains an icon moves the taps instead of silently
# photographing the wrong screen.
#
# Usage:
#   tools/screenshots.sh [output-dir]      (default: store/screenshots/en)
#
# Expects an emulator already booted and `app-debug.apk` already built:
#   ./gradlew assembleDebug
#   $ANDROID_HOME/emulator/emulator -avd breadbin-shots -no-snapshot -no-audio &
set -uo pipefail

OUT="${1:-store/screenshots/en}"
PKG=be.valuya.breadbin
APK=app/build/outputs/apk/debug/app-debug.apk

# The search term the "Find games" screen is photographed with. It is not
# arbitrary. The Internet Archive's C64 collection is largely cracked releases
# of commercial games, and a store screenshot showing a crack group's tag next
# to a 1984 title — or the profanity a broader query turns up — is precisely
# what a reviewer looking at an emulator is looking for. "gazette" returns
# Compute!'s Gazette type-ins and similar freely-circulated material.
QUERY=gazette

MIN_BYTES=40000

mkdir -p "$OUT"

shot() { # shot <name>
    for attempt in 1 2 3 4 5 6 7 8; do
        adb exec-out screencap -p > "$OUT/$1.png"
        local size
        size=$(stat -c%s "$OUT/$1.png" 2>/dev/null || echo 0)
        if [ "$size" -ge "$MIN_BYTES" ]; then
            echo "  captured $1 (${size}B)"
            return 0
        fi
        echo "  $1 not drawn yet (${size}B), waiting"
        sleep 10
    done
    echo "  WARNING: $1 never reached ${MIN_BYTES}B — do not upload it unlooked at"
}

# The centre of the toolbar icon whose content description is $1, so the taps
# survive the toolbar gaining or losing a button.
locate() { # locate <content-desc>
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb shell cat /sdcard/ui.xml 2>/dev/null | tr '>' '\n' |
        grep -o "content-desc=\"$1\"[^\n]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" |
        grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1 |
        sed 's/[^0-9]\+/ /g' | awk '{print int(($1+$3)/2), int(($2+$4)/2)}'
}

tap_desc() { # tap_desc <content-desc>
    local xy
    xy=$(locate "$1")
    if [ -z "$xy" ]; then
        echo "  WARNING: no element described '$1' on screen; skipping"
        return 1
    fi
    adb shell input tap $xy
}

adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 5; done

# Google Photos throws a location-tagging dialog over whatever is on screen on
# a fresh image. It is not part of this app and it is not worth racing.
adb shell pm disable-user --user 0 com.google.android.apps.photos >/dev/null 2>&1
adb shell settings put global hide_error_dialogs 1 >/dev/null 2>&1
adb shell settings put system accelerometer_rotation 0 >/dev/null 2>&1
adb shell settings put system user_rotation 0 >/dev/null 2>&1

adb install -r "$APK" >/dev/null 2>&1
# Cleared so the setup screen is the first thing the app shows: once ROMs have
# been chosen it never appears again, and it is the screenshot that explains
# why the app works without the user finding anything first.
adb shell pm clear "$PKG" >/dev/null 2>&1

until adb shell dumpsys activity activities 2>/dev/null | grep -q "mResumedActivity.*$PKG"; do
    adb shell "am start -n $PKG/.MainActivity" >/dev/null 2>&1
    sleep 15
done
sleep 10

echo "== setup =="
shot 5-setup

# "Start with the free ROMs" — the only button on the setup screen, and the one
# path that needs no file from the user.
adb shell input tap 540 993; sleep 8

echo "== find games =="
tap_desc "Find games online" && sleep 6
adb shell input tap 540 400; sleep 2
adb shell input text "$QUERY"; sleep 2
adb shell input keyevent KEYCODE_ENTER; sleep 14
adb shell input keyevent KEYCODE_BACK; sleep 3   # drop the soft keyboard
shot 3-search

# Four results downloaded so the library is not photographed empty. The y
# offsets are result rows; they are stable for as long as the row height is.
for y in 745 918 1298 1478; do adb shell input tap 1005 $y; sleep 18; done
adb shell input keyevent KEYCODE_BACK; sleep 5

echo "== library =="
shot 2-library

echo "== emulator =="
# "Just BASIC" boots the machine with no image inserted: a real C64 screen on
# the bundled Open ROMs, which is the one running-machine picture that raises
# no question about what is being distributed.
tap_desc "Just BASIC" && sleep 20
shot 1-emulator

echo "== keyboard =="
# The emulator toolbar's own keyboard toggle, over the running machine.
tap_desc "Keyboard" || adb shell input tap 745 211
sleep 6
shot 4-keyboard

echo "== done; review every file in $OUT before uploading =="
