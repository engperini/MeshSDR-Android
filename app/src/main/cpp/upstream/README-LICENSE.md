# Third-party LoRa PHY

Files `lora_lite.h` and `lora_kiss.h` are copied from [mmmaly/LoraReceiverStandalone](https://github.com/mmmaly/LoraReceiverStandalone) (master branch), and are derived from `gr-lora_sdr` DSP algorithms. Upstream describes its DSP as **GPL-3.0**; `lora_kiss.h` is included from the same project. These files are not authored by MeshSDR. Preserve upstream attribution and comply with applicable GPL-3.0 obligations when distributing APKs and source. Full upstream source is available at the linked repository.

The Android JNI bridge is in `../lora_jni.cpp`.
