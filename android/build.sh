#!/bin/bash
# Build a signed APK entirely on-device (aapt2 -> javac -> dx -> zip -> apksigner).
#
# This is the LEGACY Termux build, kept for fast on-device iteration.
# The canonical build is Gradle via podman-build.sh.
# Both compile the same sources: core/src/main/java + app/src/main/java.
set -e

PROJECT="$(cd "$(dirname "$0")" && pwd)"
APP="$PROJECT/app/src/main"
ANDROID_JAR="$HOME/android-sdk/android.jar"
BUILD="$PROJECT/build"
GEN="$BUILD/gen"
OBJ="$BUILD/obj"
APK_DIR="$BUILD/apk"
COMPILED_RES="$BUILD/compiled_res"

# must match `namespace` in app/build.gradle (the manifest no longer carries it)
APP_PACKAGE="com.amoshydra.basrelief"

if [ ! -f "$ANDROID_JAR" ]; then
    echo "ERROR: android.jar not found at $ANDROID_JAR"
    echo "Run bash setup.sh first."
    exit 1
fi

echo "=== Cleaning build artifacts ==="
rm -rf "$GEN" "$OBJ" "$APK_DIR" "$COMPILED_RES" "$BUILD/classes.dex"
mkdir -p "$GEN" "$OBJ" "$APK_DIR" "$COMPILED_RES"

echo "=== Step 1: Compile resources ==="
aapt2 compile --dir "$APP/res" -o "$COMPILED_RES/"

echo "=== Step 2: Link resources ==="
aapt2 link \
    -I "$ANDROID_JAR" \
    --manifest "$APP/AndroidManifest.xml" \
    --custom-package "$APP_PACKAGE" \
    --java "$GEN" \
    --min-sdk-version 26 \
    --target-sdk-version 34 \
    -o "$APK_DIR/app-unaligned.apk" \
    "$COMPILED_RES"/*.flat

echo "=== Step 3: Compile Java ==="
# :core (pure-JVM rendering core, no Android imports) and :app are compiled
# together here; Gradle keeps them as separate modules, this path does not.
( echo "$GEN/$APP_PACKAGE/R.java"; \
  find "$PROJECT/core/src/main/java" -name '*.java'; \
  find "$APP/java" -name '*.java' ) > "$BUILD/sources.txt"
# -encoding UTF-8 explicitly: sources contain em-dashes/degree signs, and
# javac's default encoding follows the ambient locale, so this build would
# otherwise break on a device configured for a non-UTF-8 charset.
javac \
    -source 1.8 -target 1.8 \
    -encoding UTF-8 \
    -classpath "$ANDROID_JAR" \
    -d "$OBJ" \
    @"$BUILD/sources.txt"

echo "=== Step 4: DEX ==="
dx --dex --output="$BUILD/classes.dex" "$OBJ"

echo "=== Step 5: Package APK ==="
cp "$APK_DIR/app-unaligned.apk" "$APK_DIR/app.apk"
cd "$BUILD" && zip -j "$APK_DIR/app.apk" classes.dex
cd "$PROJECT"

echo "=== Step 6: Sign ==="
if [ ! -f "$HOME/.debug.keystore" ]; then
    keytool -genkeypair \
        -keystore "$HOME/.debug.keystore" \
        -alias debug \
        -keyalg RSA -keysize 2048 \
        -validity 10000 \
        -storepass android \
        -keypass android \
        -dname "CN=Debug, OU=Debug, O=Debug, L=Debug, ST=Debug, C=US"
fi
apksigner sign \
    --ks "$HOME/.debug.keystore" \
    --ks-key-alias debug \
    --ks-pass pass:android \
    --key-pass pass:android \
    "$APK_DIR/app.apk"

echo ""
echo "=== BUILD SUCCESSFUL ==="
ls -lh "$APK_DIR/app.apk"
echo ""
echo "To install:"
echo "  copy $APK_DIR/app.apk to shared storage and install it from a file manager"
echo "Then long-press the home screen -> Wallpapers -> Depth Tilt"
