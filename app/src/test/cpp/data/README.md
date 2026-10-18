# Real captures

`meshcore_jundiai_20261017_923125000Hz_1Msps.cu8`: 0.9 s of rtl_tcp IQ
(unsigned 8-bit interleaved, 1 MS/s, tuned to 923.125 MHz, AGC) recorded by
the app in Jundiaí on 17/10/2026 with the antenna inside a car. It contains
one MeshCore packet (SF8 / BW 62.5 kHz / CR 4/8, sync 0x12): 70 bytes,
DIRECT RESPONSE, CRC-ok — decoded live by the app and offline from this file.
`lora_phy_test` requires the exact bytes with the filter on and off.
