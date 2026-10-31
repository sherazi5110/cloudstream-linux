## Which file do I need?

| Your device | Download |
|---|---|
| **Ubuntu, Debian, Mint, Pop!_OS, Zorin, ChromeOS (Linux)** | `cloudstream-linux_{{VERSION}}_amd64.deb` |
| **Any Linux with Flatpak** (Fedora, Arch, openSUSE, Steam Deck…) | `cloudstream-linux.flatpak` |
| **Linux with Java 21 already installed** | `cloudstream-linux-{{TAG}}.tar` |
| **Android phone or tablet** | `cloudstream-android-arm64-v8a.apk` |
| **Android TV, Google TV, Fire TV Stick, TV box** | `cloudstream-android-armeabi-v7a.apk` |
| **Android, not sure which** | `cloudstream-android-universal.apk` (larger, runs on any device) |

<details>
<summary><b>Install the .deb</b> (Ubuntu, Debian, Mint, ChromeOS)</summary>

```bash
wget -O /tmp/cloudstream-linux.deb https://github.com/sherazi5110/cloudstream-linux/releases/download/{{TAG}}/cloudstream-linux_{{VERSION}}_amd64.deb
sudo apt install /tmp/cloudstream-linux.deb
```
Or, after downloading it in the browser: `cd ~/Downloads && sudo apt install ./cloudstream-linux_{{VERSION}}_amd64.deb`
(keep the `./`). apt also installs VLC's libraries. Open **CloudStream** from the app menu.

To update, install the new `.deb` the same way (your extensions and settings are kept).
To remove: `sudo apt remove cloudstream-linux`.
</details>

<details>
<summary><b>Install the .flatpak</b> (any distro; includes VLC)</summary>

```bash
flatpak remote-add --user --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo
wget -O /tmp/cloudstream-linux.flatpak https://github.com/sherazi5110/cloudstream-linux/releases/download/{{TAG}}/cloudstream-linux.flatpak
flatpak install --user /tmp/cloudstream-linux.flatpak
flatpak run io.github.sherazi5110.CloudStreamLinux
```
To update, install the new file the same way. To remove: `flatpak uninstall --user io.github.sherazi5110.CloudStreamLinux`.
</details>

<details>
<summary><b>Run the .tar</b> (needs Java 21 and VLC)</summary>

```bash
sudo apt install openjdk-21-jre libvlc5 vlc-plugin-base   # or your distro's packages
tar -xf cloudstream-linux-{{TAG}}.tar
./cloudstream-linux/bin/cloudstream-linux
```
</details>

<details>
<summary><b>Install the .apk on a phone or tablet</b></summary>

1. Download the `.apk` on the phone (tap it in the list below).
2. Open it; when Android asks, allow **Install unknown apps** for your browser, then tap **Install**.
3. Open **CloudStream** → **More → Extensions** and add your repositories.

It installs next to the official CloudStream app. "App not installed" means the file doesn't match the device:
use the universal one.
</details>

<details>
<summary><b>Install the .apk on Android TV / Fire TV</b></summary>

1. Install **Downloader** (by AFTVnews) from the TV's app store.
2. Allow it to install apps: **Fire TV:** Settings → My Fire TV → Developer options → Install unknown apps.
   **Android/Google TV:** Settings → Apps → Security & restrictions → Unknown sources.
3. In Downloader, enter
   `https://github.com/sherazi5110/cloudstream-linux/releases/download/{{TAG}}/cloudstream-android-armeabi-v7a.apk`
   (or the `arm64-v8a` one for newer TVs) and choose **Install**.

Or copy the `.apk` to a USB stick and open it with a file manager on the TV, or: `adb install -r cloudstream-android-armeabi-v7a.apk`.
</details>

Full guide, other distros and troubleshooting: [INSTALL.md](https://github.com/sherazi5110/cloudstream-linux/blob/main/INSTALL.md).

---

