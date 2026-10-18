**MeshSDR turns an Android phone and an RTL-SDR dongle into a passive LoRa receiver for Meshtastic and MeshCore networks.**

### Highlights
- Real-time LoRa demodulation on the phone with a native C++ PHY: SF7–12, BW 62.5/125/250 kHz, CR 4/5–4/8.
- Detections even when a packet is too weak to decode, with length, SNR and sync word.
- MeshCore interpretation: route, payload type, hops and repeaters; ADVERTs show node name and GPS position.
- Meshtastic radio-header interpretation.
- Profiles for Brazil (Meshtastic ANZ LongFast 919.875 MHz, MeshCore 923.125 MHz) and the US (906.875 MHz).
- Gain control, live IQ level meter and screen-off reception.
- 10 s IQ recording (`.cu8`) and log export to `Download/MeshSDR`.
- Field-tested: MeshCore packets received and decoded over the air in Jundiaí, Brazil.

### Install
1. Install [RTL-SDR Driver](https://play.google.com/store/apps/details?id=marto.rtl_tcp_andro) from Google Play.
2. Download `MeshSDR-<version>.apk` below and install it. You may need to allow installs from your browser or file manager.
3. Plug in the RTL-SDR with an OTG adapter, choose a profile and tap **Conectar**.

The UI is in Portuguese (Brazil); an English UI is planned.

### Assets
- `MeshSDR-<version>.apk`: the Android app. It is signed with the project's public test key, so future builds install over it.
- `meshcore_jundiai_20261017_923125000Hz_1Msps.cu8`: 0.9 s of real IQ (u8, 1 MS/s, 923.125 MHz) containing one MeshCore packet, for trying the decoder or other SDR tools.
- `SHA256SUMS.txt`: checksums of the files above.

Licensed under GPL-3.0.
