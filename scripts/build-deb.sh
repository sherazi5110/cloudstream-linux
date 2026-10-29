#!/usr/bin/env bash
# Builds a self-contained .deb (bundled Java runtime) without needing fakeroot:
# jpackage makes the app image, dpkg-deb --root-owner-group packages it.
#   ./scripts/build-deb.sh  ->  build/cloudstream-linux_<version>_<arch>.deb
set -euo pipefail
cd "$(dirname "$0")/.."

[ -d upstream/cloudstream ] || ./scripts/fetch-upstream.sh
./gradlew --quiet :app:packageDeb -PjpackageType=app-image

VERSION=$(grep -o 'VERSION = "[^"]*"' app/src/main/kotlin/com/lagradost/cloudstream3/linux/ui/AppWindow.kt | cut -d'"' -f2)
ARCH=$(dpkg --print-architecture)
STAGE=build/deb/cloudstream-linux
rm -rf build/deb && mkdir -p "$STAGE/DEBIAN" "$STAGE/opt" "$STAGE/usr/bin" \
  "$STAGE/usr/share/applications" "$STAGE/usr/share/icons/hicolor/512x512/apps"

cp -r app/build/jpackage/cloudstream-linux "$STAGE/opt/cloudstream-linux"
ln -s /opt/cloudstream-linux/bin/cloudstream-linux "$STAGE/usr/bin/cloudstream-linux"
cp packaging/linux/cloudstream-linux.png "$STAGE/usr/share/icons/hicolor/512x512/apps/cloudstream-linux.png"
sed -e "s|@EXEC@|/opt/cloudstream-linux/bin/cloudstream-linux|" -e "s|@ICON@|cloudstream-linux|" \
  packaging/linux/cloudstream-linux.desktop > "$STAGE/usr/share/applications/cloudstream-linux.desktop"

cat > "$STAGE/DEBIAN/control" <<CONTROL
Package: cloudstream-linux
Version: $VERSION
Section: video
Priority: optional
Architecture: $ARCH
Installed-Size: $(du -sk "$STAGE" | cut -f1)
Depends: libgtk-3-0t64 | libgtk-3-0, libgl1, libx11-6, libxtst6, libvlc5, vlc-plugin-base
Recommends: mpv
Suggests: vlc, ffmpeg
Maintainer: cloudstream-linux <cloudstream-linux@users.noreply.github.com>
Homepage: https://github.com/recloudstream/cloudstream
Description: Run CloudStream extensions on Linux
 Media center that installs and runs CloudStream extensions from the same
 repositories the Android app uses, and plays streams in its built-in
 player (libVLC: every format VLC plays, online subtitles, source switching).
CONTROL

cat > "$STAGE/DEBIAN/postinst" <<'POSTINST'
#!/bin/sh
set -e
command -v update-desktop-database >/dev/null && update-desktop-database -q /usr/share/applications || true
command -v gtk-update-icon-cache >/dev/null && gtk-update-icon-cache -q -t /usr/share/icons/hicolor || true
exit 0
POSTINST
cp "$STAGE/DEBIAN/postinst" "$STAGE/DEBIAN/postrm"
chmod 755 "$STAGE/DEBIAN/postinst" "$STAGE/DEBIAN/postrm"

OUT="build/cloudstream-linux_${VERSION}_${ARCH}.deb"
dpkg-deb --root-owner-group -Zxz --build "$STAGE" "$OUT" >/dev/null
echo "Built $OUT ($(du -h "$OUT" | cut -f1))"
