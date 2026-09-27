FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y \
    openjdk-17-jdk \
    wget \
    unzip \
    git \
    curl \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ENV ANDROID_HOME=/opt/android-sdk
ENV PATH=${PATH}:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools

# Android command-line tools
RUN mkdir -p ${ANDROID_HOME}/cmdline-tools && \
    cd ${ANDROID_HOME}/cmdline-tools && \
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip && \
    unzip commandlinetools-linux-11076708_latest.zip && \
    rm commandlinetools-linux-11076708_latest.zip && \
    mv cmdline-tools latest

# Accept licenses and install exactly what app/build.gradle needs:
# compileSdk 34 / build-tools 34.0.0 / minSdk 26
RUN yes | sdkmanager --licenses
RUN sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"

# Bake a debug signing key into the image.
#
# Without this, AGP generates a fresh debug.keystore inside the container's
# ephemeral filesystem on every run, so each build gets a DIFFERENT signature.
# The result is that `adb install -r` always fails with
# INSTALL_FAILED_UPDATE_INCOMPATIBLE, and the only recovery is an uninstall --
# which wipes the user's studio settings and chosen image every single time.
#
# This is a well-known throwaway key, not a secret. It exists purely so
# successive debug builds are mutually upgradeable. It must never be used for
# release signing (that uses the gitignored keystore.properties).
RUN mkdir -p /root/.android && \
    keytool -genkeypair -v -keystore /root/.android/debug.keystore \
      -storepass android -keypass android -alias androiddebugkey \
      -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=Android Debug,O=Android,C=US" && \
    chmod 644 /root/.android/debug.keystore

WORKDIR /workspace

CMD ["/bin/bash"]
