#!/bin/bash
# One-time Termux setup: install build tools, download android.jar, make keystore.
set -e

echo "=== Installing build tools ==="
pkg install -y aapt aapt2 apksigner dx zip

echo ""
echo "=== Downloading Android SDK platform (android.jar, API 33) ==="
ANDROID_JAR_DIR="$HOME/android-sdk"
mkdir -p "$ANDROID_JAR_DIR"

if [ ! -f "$ANDROID_JAR_DIR/android.jar" ]; then
    TMP_DIR="$(mktemp -d)"
    trap 'rm -rf "$TMP_DIR"' EXIT
    curl -L -o "$TMP_DIR/platform-33.zip" \
        "https://dl.google.com/android/repository/platform-33_r02.zip"
    unzip -j "$TMP_DIR/platform-33.zip" "android-13/android.jar" -d "$ANDROID_JAR_DIR/"
    echo "   android.jar saved to $ANDROID_JAR_DIR/android.jar"
else
    echo "   android.jar already exists, skipping"
fi

echo ""
echo "=== Generating debug keystore ==="
if [ ! -f "$HOME/.debug.keystore" ]; then
    keytool -genkeypair \
        -keystore "$HOME/.debug.keystore" \
        -alias debug \
        -keyalg RSA -keysize 2048 \
        -validity 10000 \
        -storepass android \
        -keypass android \
        -dname "CN=Debug, OU=Debug, O=Debug, L=Debug, ST=Debug, C=US"
    echo "   Debug keystore created."
else
    echo "   Debug keystore already exists, skipping"
fi

echo ""
echo "=== Setup complete. Build with: bash build.sh ==="
