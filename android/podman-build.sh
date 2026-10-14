#!/bin/bash
# Containerised Android build for Depth Tilt.
# The host needs only podman (+ adb for the install command). No Android SDK,
# no Gradle, no Android Studio on the host.
#
# `install` builds and installs the RELEASE APK and then AOT-compiles it, because
# that is the only combination that is usable on this ROM -- see aot_compile.
# `install-debug` is the explicit escape hatch for behaviour testing.
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IMAGE_NAME="depthtilt-android-sdk:36"
APP_ID="com.amoshydra.basrelief"
APK_DEBUG="$SCRIPT_DIR/app/build/outputs/apk/debug/app-debug.apk"
APK_RELEASE_UNSIGNED="$SCRIPT_DIR/app/build/outputs/apk/release/app-release-unsigned.apk"
APK_RELEASE="$SCRIPT_DIR/app/build/outputs/apk/release/app-release.apk"

# Which device an install goes to, when it is not auto-detected. Resolved by
# parse_args from -s / --serial / a bare argument / ANDROID_SERIAL, in that
# order; an empty value asks select_device to auto-select exactly one device.
ADB_SERIAL="${ANDROID_SERIAL:-}"
ADB=(adb)

# Gradle cache, bind-mounted out of the throwaway container so dependencies are
# downloaded once instead of on every single build. Override with
# GRADLE_CACHE_DIR, or set GRADLE_NO_CACHE=1 to opt out entirely.
GRADLE_CACHE_DIR="${GRADLE_CACHE_DIR:-$HOME/.cache/depthtilt-gradle}"
GRADLE_USER_HOME="/cache/gradle"
if [ -z "${GRADLE_NO_CACHE:-}" ] && mkdir -p "$GRADLE_CACHE_DIR" 2>/dev/null; then
    GRADLE_CACHE_MOUNT="-v $GRADLE_CACHE_DIR:/cache:Z"
else
    GRADLE_CACHE_MOUNT=""
    GRADLE_USER_HOME=""   # container default: cache is thrown away, as before
fi
mkdir -p "$GRADLE_CACHE_DIR" 2>/dev/null || true

show_usage() {
    cat <<EOF
Usage: $0 <command> [serial]

  build-image    Build the Android SDK container image (first time / Dockerfile changed)
  test           Run the JVM unit tests in :core (also runs as part of build-apk)
  build-apk      Build the debug APK
  build-release  Build the release APK (signed only if keystore.properties exists)
  install        Build the RELEASE APK, install it, and AOT-compile it — the usable build
  install-debug  Build and install the DEBUG APK — behaviour testing only, NOT performance
  clean          Remove local build artifacts
  shell          Open a shell in the build container
  help           Show this message

Device: adb needs exactly one. Pass -s <serial>, set ANDROID_SERIAL, or give
the serial as an argument, e.g.:

  $0 install 192.0.2.10:5555

APK output: $SCRIPT_DIR/app/build/outputs/apk/
EOF
}

build_image() {
    echo "Building Android SDK image..."
    podman build -t "$IMAGE_NAME" -f "$SCRIPT_DIR/Dockerfile" "$SCRIPT_DIR"
    echo "Image ready: $IMAGE_NAME"
}

ensure_image() {
    if ! podman image exists "$IMAGE_NAME"; then
        echo "Image $IMAGE_NAME not found; building it first..."
        build_image
    fi
}

gradle_task() {
    ensure_image
    # GRADLE_USER_HOME is bind-mounted from the host. Without it the cache lives
    # in the throwaway container layer, so every build re-downloads the Gradle
    # distribution, AGP and all transitives — locally and on every CI run.
    # Falls back to no cache where :Z is unavailable (macOS/Windows), rather
    # than failing the build.
    # The commit is resolved HERE, on the host, where git and the repository exist --
    # the container has neither (the repo is bind-mounted without .git, and the image
    # ships no git binary). Passing it in is what makes BuildConfig.GIT_COMMIT a fact
    # rather than the "unknown" a container-side lookup always produced.
    local commit
    commit="$(git -C "$SCRIPT_DIR" rev-parse --short HEAD 2>/dev/null || echo unknown)"
    podman run --rm \
        -v "$SCRIPT_DIR:/workspace:Z" \
        ${GRADLE_CACHE_MOUNT:-} \
        ${GRADLE_USER_HOME:+-e GRADLE_USER_HOME=$GRADLE_USER_HOME} \
        -e BASRELIEF_GIT_COMMIT="$commit" \
        ${BASRELIEF_RENDERER:+-e BASRELIEF_RENDERER="$BASRELIEF_RENDERER"} \
        -w /workspace \
        "$IMAGE_NAME" \
        bash -c "./gradlew $1 --no-daemon"
}

build_apk() {
    echo "Building debug APK..."
    gradle_task assembleDebug
    echo
    echo "=== Build complete ==="
    echo "APK: $APK_DEBUG"
}

build_release() {
    echo "Building release APK..."
    gradle_task assembleRelease
    echo
    echo "=== Build complete ==="
    if [ -f "$SCRIPT_DIR/keystore.properties" ]; then
        echo "Signed APK: $APK_RELEASE"
    else
        echo "UNSIGNED APK (no keystore.properties): $APK_RELEASE_UNSIGNED"
    fi
}

# -s <serial> / --serial=<serial> / a bare argument / ANDROID_SERIAL. Anything
# else is a usage error rather than something adb would half-understand.
parse_args() {
    while [ $# -gt 0 ]; do
        case "$1" in
            -s|--serial)
                [ $# -ge 2 ] || { echo "-s needs a device serial"; exit 1; }
                ADB_SERIAL="$2"; shift 2 ;;
            --serial=*) ADB_SERIAL="${1#--serial=}"; shift ;;
            -h|--help)  show_usage; exit 0 ;;
            -*)         echo "Unknown option: $1"; show_usage; exit 1 ;;
            *)          ADB_SERIAL="$1"; shift ;;
        esac
    done
}

# Resolve the one device an install will touch. Two phones are routinely
# attached here, and bare `adb` then fails with an unhelpful
# "more than one device/emulator" -- so say which ones are attached and how to
# pick one instead.
select_device() {
    if ! command -v adb >/dev/null 2>&1; then
        echo "adb not found on the host; the APK is under $SCRIPT_DIR/app/build/outputs/apk/"
        exit 1
    fi
    if [ -n "$ADB_SERIAL" ]; then
        ADB=(adb -s "$ADB_SERIAL")
        if ! "${ADB[@]}" get-state >/dev/null 2>&1; then
            echo "adb: device '$ADB_SERIAL' is not connected or not authorised."
            adb devices -l
            exit 1
        fi
        return 0
    fi
    local n
    n="$(adb devices | awk 'NR > 1 && NF > 0 { c++ } END { print c+0 }')"
    if [ "$n" -eq 0 ]; then
        echo "adb: no device attached. Devices seen:"
        adb devices -l
        exit 1
    fi
    if [ "$n" -gt 1 ]; then
        echo "adb: $n devices attached, and an install touches exactly one."
        echo "Pick one with -s <serial>, ANDROID_SERIAL, or as an argument:"
        echo "  $0 install <serial>"
        adb devices -l
        exit 1
    fi
    local serial
    serial="$(adb devices | awk 'NR > 1 && NF > 0 { print $1; exit }')"
    ADB=(adb -s "$serial")
    echo "Using the only attached device: $serial"
}

# Installs a signed APK. A signature mismatch cannot be installed over, so the
# uninstall fallback is kept -- but it is the last resort and it is made loud,
# because it DESTROYS THE USER'S LIBRARY, not merely some settings.
install_with_fallback() {
    local apk="$1"
    echo
    echo "Installing $apk ..."
    if "${ADB[@]}" install -r "$apk"; then
        return 0
    fi
    echo
    echo "############################################################################"
    echo "# INSTALL FAILED. The usual cause is a signature mismatch: the build already"
    echo "# on the device was signed with a different key, so 'install -r' cannot"
    echo "# replace it."
    echo "#"
    echo "# The only way past that is to UNINSTALL $APP_ID, and that"
    echo "# DESTROYS THE USER'S WHOLE LIBRARY. Not just settings:"
    echo "#   - every imported photo, its depth map and its normal map"
    echo "#   - every clip (colour, depth, normal) and every generated splat"
    echo "#   - the selection, the archive flags, the playlists, all settings"
    echo "#"
    echo "# It all lives in the app's private files dir, which the uninstall removes;"
    echo "# there is no in-app backup to restore. This is why AGENTS.md says"
    echo "# 'never uninstall' twice."
    echo "#"
    echo "# Proceeding anyway -- a mismatched signature cannot be installed over."
    echo "############################################################################"
    echo
    "${ADB[@]}" uninstall "$APP_ID" || true
    "${ADB[@]}" install "$apk"
}

# THE REASON `install` IS NOT JUST `build-release && adb install`.
#
# This ROM has dalvik.vm.usejit=false, and ART refuses to AOT a debuggable app.
# So an install that is not compiled runs INTERPRETER-ONLY, where every Java
# O(N) loop is 30-100x slower -- and the dominant CPU cost here (the depth sort)
# is memory-bound, so interpreting it is catastrophic rather than merely slow.
# Measured on a Pixel 7 Pro, same 299,008-splat cloud and settings:
#
#   debug / [status=verify] : fps 1.25, frameMs 802, sortMs 326, upload 2401 ms
#   release / [status=speed]: fps 50.1, frameMs 19.95, sortMs 5.55 ms
#
# The GPU was identical in both (gpuMs 28-85), so the entire difference is CPU.
# The AOT step is run explicitly rather than assumed. Measured HERE, an
# `adb install -r` of the non-debuggable APK lands at `[status=speed]`
# `[reason=install]` -- this ROM's install-time filter already does a full
# compile -- but that is a ROM policy, not a guarantee: the filter can be a
# lighter one, or the compile deferred to a background dexopt that has not run
# yet. A debuggable APK never gets one at all. So compile and report the status
# instead of trusting it. It was a manual step before, which is how it got
# forgotten.
aot_compile() {
    echo
    echo "=== dexopt ==="
    echo "AOT-compiling $APP_ID (speed). usejit=false on this ROM and a debuggable"
    echo "app cannot be AOT'd, so a debug install runs interpreter-only (~40x slower);"
    echo "a release install is compiled at install time here, but that is not guaranteed."
    if ! "${ADB[@]}" shell cmd package compile -m speed -f "$APP_ID"; then
        echo "DEXOPT FAILED -- the app may run interpreter-only until it works."
        exit 1
    fi
}

# What actually happened: the APK, whether the phone can run it, and the one
# thing an install does NOT do (swap code under a running process).
report_install() {
    local apk="$1"
    local dex
    dex="$("${ADB[@]}" shell dumpsys package "$APP_ID" \
        | awk '/Dexopt state:/ { f=1 } f && /\[status=/ { sub(/^[ \t]+/, ""); print; exit }')"
    echo
    echo "=== Installed ==="
    echo "APK:     $apk"
    echo "Package: $APP_ID"
    echo "Dexopt:  ${dex:-<no dexopt status reported>}"
    if "${ADB[@]}" shell dumpsys package "$APP_ID" | grep -q 'DEBUGGABLE'; then
        echo
        echo "WARNING: the installed package IS debuggable. ART refuses to AOT a"
        echo "         debuggable app, so this is an interpreter-only build -- fine for"
        echo "         testing behaviour, wrong for measuring performance."
    fi
    echo
    echo "NOTE: a process that was already running keeps the OLD code until it is"
    echo "restarted -- an install does not swap code under a live process. Reopen the"
    echo "studio, or re-select the wallpaper. Do NOT use 'am force-stop': on a package"
    echo "that is the active wallpaper it makes Android revert the wallpaper to the"
    echo "system's static image, and only the wallpaper picker can restore it. Use"
    echo "'am kill $APP_ID' if you need to restart the wallpaper."
}

install_apk() {
    build_release
    if [ ! -f "$APK_RELEASE" ]; then
        echo
        echo "No signed release APK at $APK_RELEASE."
        echo "Release signing needs android/keystore.properties; without it the build"
        echo "is unsigned and an unsigned APK cannot be installed. Re-run build-release"
        echo "to see which file it produced."
        exit 1
    fi
    select_device
    install_with_fallback "$APK_RELEASE"
    aot_compile
    report_install "$APK_RELEASE"
}

# Behaviour testing on a debug build is legitimate; discovering later that a
# performance number was measured on one is not -- so it is a separate command
# that says so up front.
install_debug_apk() {
    build_apk
    select_device
    echo
    echo "NOTE: this is the DEBUG APK. It is debuggable, so ART refuses to AOT it;"
    echo "      with usejit=false on this ROM it runs interpreter-only -- measured"
    echo "      ~40x slower than the AOT-compiled release build. Test BEHAVIOUR with"
    echo "      it, never performance. For a usable build run: $0 install"
    install_with_fallback "$APK_DEBUG"
    report_install "$APK_DEBUG"
}

clean() {
    if podman image exists "$IMAGE_NAME"; then
        podman run --rm -v "$SCRIPT_DIR:/workspace:Z" ${GRADLE_CACHE_MOUNT:-} \
            ${GRADLE_USER_HOME:+-e GRADLE_USER_HOME=$GRADLE_USER_HOME} \
            -w /workspace "$IMAGE_NAME" \
            bash -c "./gradlew clean --no-daemon" || true
    fi
    rm -rf "$SCRIPT_DIR/app/build" "$SCRIPT_DIR/.gradle"
    echo "Clean."
}

open_shell() {
    ensure_image
    podman run -it --rm -v "$SCRIPT_DIR:/workspace:Z" ${GRADLE_CACHE_MOUNT:-} \
        ${GRADLE_USER_HOME:+-e GRADLE_USER_HOME=$GRADLE_USER_HOME} \
        -w /workspace "$IMAGE_NAME" bash
}

run_tests() {
    echo "Running JVM unit tests (:core)..."
    gradle_task ":core:test"
}

case "${1:-build-apk}" in
    build-image)  build_image ;;
    test|tests)   run_tests ;;
    build-apk|debug|assembleDebug) build_apk ;;
    build-release|release) build_release ;;
    install)      shift; parse_args "$@"; install_apk ;;
    install-debug|debug-install) shift; parse_args "$@"; install_debug_apk ;;
    clean)        clean ;;
    shell)        open_shell ;;
    help|--help|-h) show_usage ;;
    *) echo "Unknown command: $1"; show_usage; exit 1 ;;
esac
