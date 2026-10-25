**MeshSDR turns an Android phone or a Windows/Linux computer or Raspberry Pi and an RTL-SDR dongle into a passive LoRa receiver for Meshtastic and MeshCore networks.**

### Highlights
- Real-time LoRa demodulation with a native C++ PHY: SF7–12, BW 15.6–500 kHz, CR 4/5–4/8.
- **Android, Windows and Linux** apps with the same interface and the same decoder.
- Detections even when a packet is too weak to decode, with length, SNR and sync word.
- MeshCore interpretation: route, payload type, hops and repeaters; ADVERTs show node name and GPS position. **Public channel messages are shown as text**, and so are the #hashtag channels listed in the settings; private channels and direct messages stay encrypted.
- Meshtastic radio header: sender, destination, packet id, channel (the public LongFast channel is recognised), hops taken out of the hop start, relay node, ACK request and MQTT flag. LongFast decoding is tested through the same 8-decoder diversity the app uses: every frame exactly once, about 1 dB more sensitive than a single decoder.
- **Generic LoRa mode:** any frequency, one SF or SF7–12 at once, typed bandwidth (default 125 kHz); frames shown raw.
- **Automatic IQ recording** (optional): the 3 s before and the seconds after every detection are saved as `.cu8`.
- **ppm correction** for dongles without TCXO; each detection shows the measured offset and the ppm value to set.
- **Decoder diversity:** up to 32 decoders per profile (up to 8 decimation phases, filter on/off, with and without drift tracking) on several CPU cores, so packets that only some of them decode are no longer lost. When one decoder fails a packet's CRC and another decodes it, the log shows only the decoded packet; "bad CRC" lines are real losses. With a dongle without TCXO: 4 real MeshCore packets decoded from the field captures, against 0 with a single decoder.
- **Field station: Raspberry Pi + phone, no internet.** `MeshSDR --server` runs headless on the Pi (systemd units included) and decodes; the Android app's **Remote** mode is its screen over **Bluetooth** or **Wi-Fi**: same interface, settings, messages and logs, plus downloading the Pi's recordings. The Pi takes the time from the phone (no internet clock in the field). The app shows the Pi's name and IP address once linked. See *Field station* in the README.
- English and Portuguese UI.
- Profiles for Brazil (Meshtastic ANZ LongFast 919.875 MHz, MeshCore 923.125 MHz) and the US (906.875 MHz).
- Gain control, live IQ level meter, screen-off reception on Android.
- Field-tested: MeshCore packets received and decoded over the air in Jundiaí, Brazil.

### Install
**Android**
1. Install [RTL-SDR Driver](https://play.google.com/store/apps/details?id=marto.rtl_tcp_andro) from Google Play.
2. Download `MeshSDR-<version>.apk` below and install it. You may need to allow installs from your browser or file manager.
3. Plug in the RTL-SDR with an OTG adapter, choose a profile and tap **Connect**.

**Windows**
1. Install the dongle's WinUSB driver once with [Zadig](https://zadig.akeo.ie/) (skip if SDR# or SDR++ already work).
2. Install `MeshSDR-<version>.msi`, or unzip `MeshSDR-<version>-windows-x64-portable.zip` and run `MeshSDR\MeshSDR.exe`. `rtl_tcp` is included. The app is not code-signed, so Windows may show "Windows protected your PC": choose **More info → Run anyway**.

**Linux (x64)**
1. `sudo apt install rtl-sdr` (provides `rtl_tcp`).
2. `sudo apt install ./meshsdr_<version>-1_amd64.deb`, or unpack `MeshSDR-<version>-linux-x64-portable.tar.gz` and run `MeshSDR/bin/MeshSDR`.

**Raspberry Pi 4/5 (64-bit Raspberry Pi OS)**
1. `sudo apt install rtl-sdr python3-dbus python3-gi`.
2. `sudo apt install ./meshsdr_<version>-1_arm64.deb`, or unpack `MeshSDR-<version>-linux-arm64-portable.tar.gz` and run `MeshSDR/bin/MeshSDR`. The 32-bit OS (armhf) is not supported; `uname -m` must print `aarch64`.
3. Field station with the phone as screen (Bluetooth or Wi-Fi): follow *Field station* in the README.

Logs and recordings go to `Download/MeshSDR` (Android) or `~/Downloads/MeshSDR` (desktop).

### Assets
- `MeshSDR-<version>.apk`: Android app, signed with the project's public test key so future builds install over it.
- `MeshSDR-<version>.msi`, `MeshSDR-<version>-windows-x64-portable.zip`: Windows.
- `meshsdr_<version>-1_amd64.deb`, `MeshSDR-<version>-linux-x64-portable.tar.gz`: Linux (x64).
- `meshsdr_<version>-1_arm64.deb`, `MeshSDR-<version>-linux-arm64-portable.tar.gz`: Raspberry Pi 4/5 and other 64-bit ARM Linux.
- `meshcore_jundiai_20261017_923125000Hz_1Msps.cu8`: 0.9 s of real IQ (u8, 1 MS/s, 923.125 MHz) containing one MeshCore packet, for trying the decoder or other SDR tools.
- `SHA256SUMS.txt`: checksums of the files above.

Licensed under GPL-3.0.
