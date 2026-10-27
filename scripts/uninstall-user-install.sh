#!/usr/bin/env bash
# Removes a per-user install made by scripts/install.sh or the release tarball
# (~/.local/...). Run this before installing the .deb: the old menu entry has the
# same name and would hide the new one.
# Your repositories, extensions, history and settings in ~/.local/share/cloudstream-linux
# are kept (the .deb uses them too) unless you pass --purge.
set -u
PREFIX="${PREFIX:-$HOME/.local}"
rm -rfv "$PREFIX/opt/cloudstream-linux"
rm -fv "$PREFIX/bin/cloudstream-linux"
rm -fv "$PREFIX/share/applications/cloudstream-linux.desktop"
rm -fv "$PREFIX"/share/icons/hicolor/*/apps/cloudstream-linux.png
command -v update-desktop-database >/dev/null && update-desktop-database "$PREFIX/share/applications" 2>/dev/null
hash -r
if [ "${1:-}" = "--purge" ]; then
  rm -rfv "${XDG_DATA_HOME:-$HOME/.local/share}/cloudstream-linux"
fi
echo "Old per-user install removed."
