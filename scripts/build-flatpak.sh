#!/usr/bin/env bash
# Builds a Flatpak bundle: build/cloudstream-linux.flatpak
# Needs flatpak and flatpak-builder (sudo apt install flatpak flatpak-builder).
# The first run downloads the Freedesktop SDK and compiles libVLC (~15 minutes).
set -euo pipefail
cd "$(dirname "$0")/.."
APP_ID=io.github.sherazi5110.CloudStreamLinux

# The app image (with its own Java), packed for the manifest.
[ -d upstream/cloudstream ] || ./scripts/fetch-upstream.sh
./gradlew --quiet :app:packageDeb -PjpackageType=app-image
tar -C app/build/jpackage -cf build/cloudstream-linux-app.tar cloudstream-linux

flatpak remote-add --user --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo
flatpak-builder --user --install-deps-from=flathub --force-clean --ccache \
  --repo=build/flatpak-repo build/flatpak-build "packaging/flatpak/$APP_ID.yml"
flatpak build-bundle --runtime-repo=https://dl.flathub.org/repo/flathub.flatpakrepo \
  build/flatpak-repo build/cloudstream-linux.flatpak "$APP_ID"
echo "Built build/cloudstream-linux.flatpak"
echo "Install: flatpak install --user build/cloudstream-linux.flatpak"
