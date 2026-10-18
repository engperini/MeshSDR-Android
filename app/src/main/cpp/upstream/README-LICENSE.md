# Third-party LoRa PHY

`lora_lite.h` and `lora_kiss.h` come from
[mmmaly/LoraReceiverStandalone](https://github.com/mmmaly/LoraReceiverStandalone)
and are derived from the DSP algorithms of
[gr-lora_sdr](https://github.com/tapparelj/gr-lora_sdr). They are licensed
under the **GNU GPL v3.0**, which is why MeshSDR as a whole is distributed under
GPL-3.0 (see `LICENSE` at the repository root). Upstream attribution is kept
in the files.

## Local modifications (MeshSDR)

- `finish_sync()`: when no sync word is configured ("accept any"), the net-ID
  residual used for CFO recovery is still estimated, against the nearest
  multiple of 8 bins.
- `finish_sync()`: the clock drift (SFO) accumulated between the preamble
  timing reference and the payload is now compensated
  (`LORA_LITE_SFO_LEAD`). Without it, payloads failed for crystal errors of
  roughly 12–21 ppm with a 16-symbol preamble.

The Android JNI bridge is `../lora_jni.cpp`; the IQ front end is `../iq_u8.h`.
