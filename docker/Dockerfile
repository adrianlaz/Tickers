# Android build toolchain. Nothing is installed on the host; see ../dw.
FROM eclipse-temurin:21-jdk

ARG UID=1000
ARG GID=1000
ARG CMDLINE_TOOLS=commandlinetools-linux-16111833_latest.zip
ARG GRADLE_VERSION=9.9.1
ARG PLATFORM=platforms;android-37.0
ARG BUILD_TOOLS=build-tools;37.0.0

ENV ANDROID_HOME=/opt/android-sdk \
    GRADLE_USER_HOME=/home/builder/.gradle
ENV PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:/opt/gradle/bin

RUN apt-get update \
 && apt-get install -y --no-install-recommends unzip curl git \
 && rm -rf /var/lib/apt/lists/*

# Builder user matching the host UID/GID so build outputs are not root-owned.
# The base image ships an "ubuntu" user at 1000; remove it first.
RUN (userdel -r ubuntu 2>/dev/null || true) \
 && (getent group $GID || groupadd -g $GID builder) \
 && useradd -m -u $UID -g $GID -s /bin/bash builder

# Gradle distribution, used only to bootstrap the wrapper.
RUN curl -fsSL -o /tmp/gradle.zip https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip \
 && unzip -q /tmp/gradle.zip -d /opt && mv /opt/gradle-${GRADLE_VERSION} /opt/gradle && rm /tmp/gradle.zip

# Android SDK, writable by builder so AGP can auto-install anything missing.
RUN mkdir -p $ANDROID_HOME/cmdline-tools \
 && curl -fsSL -o /tmp/clt.zip https://dl.google.com/android/repository/${CMDLINE_TOOLS} \
 && unzip -q /tmp/clt.zip -d $ANDROID_HOME/cmdline-tools \
 && mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest \
 && rm /tmp/clt.zip \
 && yes | sdkmanager --licenses > /dev/null \
 && sdkmanager "platform-tools" "${PLATFORM}" "${BUILD_TOOLS}" > /dev/null \
 && chown -R $UID:$GID $ANDROID_HOME

# Pre-create the Gradle home so the named volume inherits builder ownership.
RUN mkdir -p /home/builder/.gradle && chown -R $UID:$GID /home/builder

USER builder
WORKDIR /work
