# MeshSDR-Android

Android RTL2832U receiver for monitoring Meshtastic and MeshCore networks.

## Product requirements (v0.1)

**Priority: real reception and readable data, not visual polish.**

### Screen
- Editable center frequency in MHz, with user-configurable presets and apply action.
- Protocol selector: Meshtastic / MeshCore.
- Connect/disconnect SDR and visible USB/stream status.
- Two independent views: **Messages** and **Application logs**.
- Messages: local timestamp, protocol, source (if available), channel, readable payload when authorized/decodable, raw packet details, and copy/export.
- Logs: timestamp, severity, subsystem (USB/RTL/LoRa/decoder/UI), rolling live view, auto-scroll toggle, copy/export and clear. Never mix messages into logs.
- Persistent frequency and settings; clear indication when retuning.
- No fabricated messages: explicitly distinguish RF activity, LoRa frames, recognized packets, and decrypted text.

### Receiver architecture
1. Android Kotlin UI and lifecycle-aware foreground receive service.
2. Reuse existing Android RTL-SDR driver through iqsrc:// and local rtl_tcp IQ stream. Handle USB permissions and disconnect/reconnect.
3. Native LoRa demodulation (C++/NDK candidate, to be validated against IQ recordings).
4. Protocol parsers: Meshtastic first, MeshCore next. Respect encryption and channel keys.
5. Structured events to UI; separate logs and packet/message stores.

### Verification gates
- Build debug APK in GitHub Actions.
- Verify RTL2832U driver handoff and real IQ stream on Android hardware.
- Frequency retune changes tuner and appears in logs.
- Decode known IQ test captures with reproducible expected packets.
- Demonstrate live over-the-air packets, then decoded public-channel messages.
- Never mark a decoder implemented until tests prove it.

## Status
Requirements and UI plan only. **No functional Android app or LoRa decoder has been implemented yet.**

## Reference
Android RTL-SDR driver: https://github.com/signalwareltd/rtl_tcp_andro-
