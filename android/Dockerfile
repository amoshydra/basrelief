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
# compileSdk 36 / build-tools 36.0.0 / minSdk 26
RUN yes | sdkmanager --licenses
RUN sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"

# Debug signing key: COPIED, not generated.
#
# This used to run `keytool -genkeypair` here, and that was the bug: the key was
# created at image-build time, so rebuilding the image produced a DIFFERENT one.
# Every debug build then had a different signature, `adb install -r` always
# failed with INSTALL_FAILED_UPDATE_INCOMPATIBLE, and the only recovery was an
# uninstall -- which wipes the user's studio settings and chosen image.
#
# It looked correct for a long time purely because the image was rarely
# rebuilt, and the very first rebuild (the SDK 36 bump) reproduced the original
# failure exactly. Committing the key makes rebuilds a no-op.
#
# The key is generated on the host by `podman-build.sh` (see `ensure_keystore`),
# not committed and not generated per image build. It is a well-known throwaway
# with the published password `android`, not a secret, and it must never be used
# for release signing: F-Droid and Obtainium distribute the APK you sign, so the
# release key is the only thing standing between a user and a forged update.
COPY debug.keystore /root/.android/debug.keystore
RUN chmod 644 /root/.android/debug.keystore

WORKDIR /workspace

CMD ["/bin/bash"]
