// rtl_tcp IQ (unsigned 8-bit, interleaved I/Q) -> complex floats decimated 2:1.
// Shared by the JNI bridge and the host-side PHY tests so both run the exact
// same conversion.
#pragma once
#include <cstdint>
#include <vector>
#include "lora_lite.h"

namespace meshsdr {

class U8Decimator {
public:
    // Appends converted samples to `out`. Reads may end between I and Q (or
    // between the two samples of a decimation pair); state carries over.
    void push(const uint8_t* data, int length, std::vector<lora_lite::Cx>& out) {
        for (int i = 0; i < length; ++i) {
            const int value = data[i];
            if (pendingI_ < 0) { pendingI_ = value; continue; }
            const float in = (pendingI_ - 127.5f) / 127.5f;
            const float quad = (value - 127.5f) / 127.5f;
            pendingI_ = -1;
            // 2-tap average before dropping every other sample: a cheap
            // anti-alias filter with a null at the folding frequency.
            if (!havePrev_) { prevI_ = in; prevQ_ = quad; havePrev_ = true; continue; }
            out.push_back({(prevI_ + in) * 0.5f, (prevQ_ + quad) * 0.5f});
            havePrev_ = false;
        }
    }

private:
    int pendingI_ = -1;
    bool havePrev_ = false;
    float prevI_ = 0, prevQ_ = 0;
};

} // namespace meshsdr
