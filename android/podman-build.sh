#!/bin/bash
# Containerised Android build for Depth Tilt.
# The host needs only podman (+ adb for the install command). No Android SDK,
# no Gradle, no Android Studio on the host.
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IMAGE_NAME="depthtilt-android-sdk:36"
APK_DEBUG="$SCRIPT_DIR/app/build/outputs/apk/debug/app-debug.apk"
APK_RELEASE="$SCRIPT_DIR/app/build/outputs/apk/release/app-release-unsigned.apk"

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
Usage: $0 <command>

  build-image    Build the Android SDK container image (first time / Dockerfile changed)
  test           Run the JVM unit tests in :core (also runs as part of build-apk)
  build-apk      Build the debug APK
  build-release  Build the release APK (signed only if keystore.properties exists)
  install        Build the debug APK and install it on the connected device via adb
  clean          Remove local build artifacts
  shell          Open a shell in the build container
  help           Show this message

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
    podman run --rm \
        -v "$SCRIPT_DIR:/workspace:Z" \
        ${GRADLE_CACHE_MOUNT:-} \
        ${GRADLE_USER_HOME:+-e GRADLE_USER_HOME=$GRADLE_USER_HOME} \
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
        echo "Signed APK: $SCRIPT_DIR/app/build/outputs/apk/release/app-release.apk"
    else
        echo "UNSIGNED APK (no keystore.properties): $APK_RELEASE"
    fi
}

install_apk() {
    build_apk
    if ! command -v adb >/dev/null 2>&1; then
        echo "adb not found on host; APK is at $APK_DEBUG"
        exit 1
    fi
    adb get-state >/dev/null 2>&1 || { echo "No device connected (adb devices)"; exit 1; }
    echo "Installing..."
    # signature mismatch with a previously installed build is expected when
    # switching between the Termux-signed and Gradle-signed APKs
    adb install -r "$APK_DEBUG" || {
        echo "Install failed; retrying after uninstall of the old package..."
        adb uninstall com.amoshydra.basrelief || true
        adb install "$APK_DEBUG"
    }
    echo "Installed."
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
    install)      install_apk ;;
    clean)        clean ;;
    shell)        open_shell ;;
    help|--help|-h) show_usage ;;
    *) echo "Unknown command: $1"; show_usage; exit 1 ;;
esac
