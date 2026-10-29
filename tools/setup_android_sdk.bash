#!/usr/bin/env bash
# Idempotent Android SDK + NDK install for HeliBoard / HeliTrime.
# Safe to run twice. Does not build the app and does not start an emulator.
# *.sh is gitignored in this repo, so this file is named .bash on purpose.
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
NDK_VERSION="28.0.13004108"
# compileSdk 37 is published as the versioned package platforms;android-37.0.
# sdkmanager has no unversioned platforms;android-37.
PLATFORM="android-37.0"
CMDLINE_ZIP="commandlinetools-linux-15859902_latest.zip"
CMDLINE_URL="https://dl.google.com/android/repository/${CMDLINE_ZIP}"
CMDLINE_SHA="4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583"

if [[ "$(id -u)" -eq 0 ]]; then
  echo "Run this as the ubuntu user, not root. It uses sudo for apt and /opt." >&2
  exit 1
fi

sudo apt-get update
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
  ca-certificates \
  wget \
  unzip \
  git \
  cmake \
  ninja-build \
  python3 \
  pkg-config \
  build-essential

sudo mkdir -p "${ANDROID_HOME}"
sudo chown -R "$(id -u):$(id -g)" "${ANDROID_HOME}"

if [[ ! -x "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" ]]; then
  tmp="$(mktemp -d)"
  trap 'rm -rf "${tmp}"' RETURN
  wget -O "${tmp}/${CMDLINE_ZIP}" "${CMDLINE_URL}"
  echo "${CMDLINE_SHA}  ${tmp}/${CMDLINE_ZIP}" | sha256sum -c -
  unzip -q "${tmp}/${CMDLINE_ZIP}" -d "${tmp}"
  mkdir -p "${ANDROID_HOME}/cmdline-tools"
  rm -rf "${ANDROID_HOME}/cmdline-tools/latest"
  mv "${tmp}/cmdline-tools" "${ANDROID_HOME}/cmdline-tools/latest"
  trap - RETURN
  rm -rf "${tmp}"
fi

export ANDROID_HOME
export ANDROID_SDK_ROOT="${ANDROID_HOME}"
export PATH="${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}"

# sdkmanager prompts for licenses. A second run is a no-op.
yes | sdkmanager --sdk_root="${ANDROID_HOME}" --licenses >/dev/null || true
sdkmanager --sdk_root="${ANDROID_HOME}" \
  "platforms;${PLATFORM}" \
  "ndk;${NDK_VERSION}" \
  "platform-tools"

sudo tee /etc/profile.d/android-sdk.sh >/dev/null <<EOF
export ANDROID_HOME=${ANDROID_HOME}
export ANDROID_SDK_ROOT=${ANDROID_HOME}
export PATH="\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$PATH"
EOF
sudo ln -sfn "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" /usr/local/bin/sdkmanager

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -f "${repo_root}/settings.gradle" ]]; then
  cat > "${repo_root}/local.properties" <<EOF
sdk.dir=${ANDROID_HOME}
ndk.dir=${ANDROID_HOME}/ndk/${NDK_VERSION}
EOF
fi

test -x "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager"
test -d "${ANDROID_HOME}/platforms/${PLATFORM}"
test -d "${ANDROID_HOME}/ndk/${NDK_VERSION}"
echo "Android SDK ready: ${ANDROID_HOME}"
echo "NDK ready: ${ANDROID_HOME}/ndk/${NDK_VERSION}"
echo "Export ANDROID_HOME in this shell if it was not a login shell:"
echo "  export ANDROID_HOME=${ANDROID_HOME} ANDROID_SDK_ROOT=${ANDROID_HOME}"
echo "  export PATH=\"\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$PATH\""
