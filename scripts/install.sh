#!/usr/bin/env bash
# Per-user install (no root): ~/.local/opt/cloudstream-linux, a launcher, a menu entry,
# and the cloudstreamrepo:// link handler. Needs Java 21 (sudo apt install openjdk-21-jre).
set -euo pipefail
cd "$(dirname "$0")/.."

[ -d upstream/cloudstream ] || ./scripts/fetch-upstream.sh
./gradlew --quiet :app:installDist

PREFIX="${PREFIX:-$HOME/.local}"
APP_DIR="$PREFIX/opt/cloudstream-linux"
rm -rf "$APP_DIR"
mkdir -p "$APP_DIR" "$PREFIX/bin" "$PREFIX/share/applications" "$PREFIX/share/icons/hicolor/256x256/apps"
cp -r app/build/install/cloudstream-linux/. "$APP_DIR/"
ln -sf "$APP_DIR/bin/cloudstream-linux" "$PREFIX/bin/cloudstream-linux"
cp packaging/linux/cloudstream-linux.png "$PREFIX/share/icons/hicolor/256x256/apps/cloudstream-linux.png"
sed -e "s|@EXEC@|$APP_DIR/bin/cloudstream-linux|" -e "s|@ICON@|cloudstream-linux|" \
    packaging/linux/cloudstream-linux.desktop > "$PREFIX/share/applications/cloudstream-linux.desktop"

if command -v xdg-mime >/dev/null; then
  xdg-mime default cloudstream-linux.desktop x-scheme-handler/cloudstreamrepo
fi
command -v update-desktop-database >/dev/null && update-desktop-database "$PREFIX/share/applications" || true

echo "Installed. Run 'cloudstream-linux' or open CloudStream from your app menu."
ldconfig -p 2>/dev/null | grep -q libvlc.so.5 || echo "To play videos, install VLC's libraries: sudo apt install libvlc5 vlc-plugin-base"
