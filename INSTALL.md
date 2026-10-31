# Install CloudStream for Linux

> **Android:** an Android app (phones and Android TV) is being built in this repository. See
> [Android (coming soon)](#android-coming-soon) at the end.

One download works everywhere: **`cloudstream-linux_6.0.1_amd64.deb`** from the
[Releases page](https://github.com/sherazi5110/cloudstream-linux/releases).
It includes its own Java, so you don't need to install Java.

Requirements:
- a 64-bit PC (x86-64 / amd64);
- **VLC** (the built-in player uses VLC's engine);
- a desktop session (GNOME, KDE, Xfce, Cinnamon, …).

---

## Ubuntu, Debian, Linux Mint, Pop!_OS, Zorin, elementary OS, ChromeOS (Linux)

### Install

**Option A: in the terminal (recommended)**
```bash
wget -O /tmp/cloudstream-linux.deb https://github.com/sherazi5110/cloudstream-linux/releases/download/V6.0.1/cloudstream-linux_6.0.1_amd64.deb
sudo apt install /tmp/cloudstream-linux.deb
```

**Option B: with a file you downloaded in the browser**
```bash
cd ~/Downloads
sudo apt install ./cloudstream-linux_6.0.1_amd64.deb
```
Keep the `./`, which tells apt it's a file, not a package name.
apt installs VLC's libraries automatically.

On ChromeOS, first move the downloaded file from **Downloads** to **Linux files** in the
Files app, then use `cd ~` instead of `cd ~/Downloads`.

Then open **CloudStream** from the app menu, or run `cloudstream-linux`.

> Use `apt`, not `dpkg -i` or double-clicking the file in some file managers:
> `apt` also installs the libraries the app needs.
> A message about `_apt` and "permission denied" when installing from `/tmp` is harmless.

### Update
Download the new `.deb` and install it the same way. Your extensions, history and
settings are kept.

### Remove
```bash
sudo apt remove cloudstream-linux
```
Also delete your extensions, history and settings (optional):
```bash
rm -rf ~/.local/share/cloudstream-linux
```
Remove libraries nothing else uses any more (optional; check the list before typing `Y`):
```bash
sudo apt autoremove
```

---

## Flatpak (any distro)

Flatpak runs the same way on every distro and includes its own copy of VLC, so you don't
need to install VLC. Every release has it as `cloudstream-linux.flatpak`.

```bash
# Once per computer, if Flathub isn't set up yet (it provides the runtime the app needs):
flatpak remote-add --user --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo

wget -O /tmp/cloudstream-linux.flatpak https://github.com/sherazi5110/cloudstream-linux/releases/latest/download/cloudstream-linux.flatpak
flatpak install --user /tmp/cloudstream-linux.flatpak
flatpak run io.github.sherazi5110.CloudStreamLinux
```
It also appears in your app menu as **CloudStream**.

- **Update:** download the new `.flatpak` and run the same `flatpak install` line.
- **Remove:** `flatpak uninstall --user io.github.sherazi5110.CloudStreamLinux`
  (add `--delete-data` to also delete extensions, history and settings).

Inside the Flatpak, the app can only open files from your **Downloads** folder (for
subtitle files and side-loaded extensions).

---

## Fedora, Arch, Manjaro, openSUSE and other x86-64 Linux (without Flatpak)

These systems don't install `.deb` packages, but the app inside the `.deb` runs on them.
These steps unpack it into your home folder; no root access is needed except to install VLC.

### 1. Install VLC and the desktop libraries
```bash
# Fedora (if vlc isn't found, enable RPM Fusion first: https://rpmfusion.org/Configuration)
sudo dnf install vlc gtk3 libXtst mesa-libGL binutils

# Arch / Manjaro / EndeavourOS
sudo pacman -S --needed vlc gtk3 libxtst mesa binutils

# openSUSE (the Packman repository gives VLC all video codecs)
sudo zypper install vlc gtk3 libXtst6 Mesa-libGL1 binutils
```

### 2. Unpack the app
```bash
cd /tmp
wget -O cloudstream-linux.deb https://github.com/sherazi5110/cloudstream-linux/releases/download/V6.0.1/cloudstream-linux_6.0.1_amd64.deb
mkdir -p cs-unpack && cd cs-unpack
ar x ../cloudstream-linux.deb
tar -xJf data.tar.xz
mkdir -p ~/.local/opt
rm -rf ~/.local/opt/cloudstream-linux
mv opt/cloudstream-linux ~/.local/opt/
cd /tmp && rm -rf cs-unpack cloudstream-linux.deb
```

### 3. Add the `cloudstream-linux` command and the app-menu entry
```bash
mkdir -p ~/.local/bin ~/.local/share/applications ~/.local/share/icons
ln -sf ~/.local/opt/cloudstream-linux/bin/cloudstream-linux ~/.local/bin/cloudstream-linux
cp ~/.local/opt/cloudstream-linux/lib/cloudstream-linux.png ~/.local/share/icons/cloudstream-linux.png
cat > ~/.local/share/applications/cloudstream-linux.desktop <<EOF
[Desktop Entry]
Type=Application
Name=CloudStream
GenericName=Media Center
Comment=Run CloudStream extensions on Linux
Exec=$HOME/.local/opt/cloudstream-linux/bin/cloudstream-linux %u
Icon=$HOME/.local/share/icons/cloudstream-linux.png
Terminal=false
Categories=AudioVideo;Video;Player;
MimeType=x-scheme-handler/cloudstreamrepo;
StartupWMClass=com.lagradost.cloudstream3.linux.ui.DesktopApp
EOF
update-desktop-database ~/.local/share/applications 2>/dev/null || true
```
Open **CloudStream** from the app menu. If the menu entry or the command doesn't show up
straight away, log out and back in once.

### Update
Repeat step 2 with the new `.deb`. Steps 1 and 3 only need to be done once.

### Remove
```bash
rm -rf ~/.local/opt/cloudstream-linux ~/.local/bin/cloudstream-linux \
       ~/.local/share/applications/cloudstream-linux.desktop ~/.local/share/icons/cloudstream-linux.png
```
Also delete your extensions, history and settings (optional):
```bash
rm -rf ~/.local/share/cloudstream-linux
```

---

## Troubleshooting

| Problem | Fix |
|---|---|
| "The built-in player needs VLC's libraries" | Install VLC (see above), then restart the app. |
| `E: Unsupported file … given on commandline` | You typed the file name without `./` in front; use `sudo apt install ./cloudstream-linux_6.0.1_amd64.deb`. |
| `ar: command not found` | Install `binutils` (included in the step 1 commands). |
| A site doesn't load | Settings → Network & DNS → pick a DNS-over-HTTPS provider. |
| An extension misbehaves | Settings → Developer & logs → **Test** next to it, then copy the log into a bug report. |

ARM computers (Raspberry Pi, ARM Chromebooks, Apple Silicon Linux) aren't supported by this
release.

---

## Android (coming soon)

**Not released yet.** An Android app for **phones (touch)** and **Android TV (remote)** is being built in this
repository (`android/`). When it's ready, each release will include an `.apk` next to the Linux files. The plan
and the approved design are in [docs/ANDROID-PLAN.md](docs/ANDROID-PLAN.md).

Planned installation, once an `.apk` is on the [Releases page](https://github.com/sherazi5110/cloudstream-linux/releases):

- **Phone or tablet:** download the `.apk` on the device, open it, and allow "Install unknown apps" for your
  browser or file manager when Android asks.
- **Android TV / Google TV / Fire TV:** install the free **Downloader** app (or use a USB stick and a file manager),
  enable "Install unknown apps" for it in the TV's settings, then open the `.apk` link.
- **From a computer:** `adb install cloudstream-*.apk` with USB or network debugging turned on.

It will install next to the official CloudStream app (different app ID), so you can keep both.

