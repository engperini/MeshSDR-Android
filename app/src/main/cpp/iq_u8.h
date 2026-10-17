// rtl_tcp IQ (unsigned 8-bit, interleaved I/Q) -> complex floats for lora_lite.
// Shared by the JNI bridge and the host-side PHY tests so both run the exact
// same conversion.
//
// The tuner is set `offsetHz` away from the LoRa channel so the RTL2832U DC
// spike and LO leakage fall outside it; the channel is mixed back to 0 Hz and
// then decimated with a boxcar average (nulls at multiples of the output rate).
#pragma once
#include <cmath>
#include <cstdint>
#include <vector>
#include "lora_lite.h"

namespace meshsdr {

class IqFrontEnd {
public:
    // `shiftHz` is added to every sample's frequency: with the tuner `offset` Hz
    // above the channel, the channel sits at -offset, so pass +offset.
    IqFrontEnd(int decimation, double shiftHz, double sampleRate)
        : decimation_(decimation < 1 ? 1 : decimation) {
        const double w = 2.0 * M_PI * shiftHz / sampleRate;
        stepR_ = std::cos(w); stepI_ = std::sin(w);
    }

    // Appends converted samples to `out`. Reads may end anywhere (between I
    // and Q or inside a decimation group); state carries over.
    void push(const uint8_t* data, int length, std::vector<lora_lite::Cx>& out) {
        for (int i = 0; i < length; ++i) {
            const int value = data[i];
            if (pendingI_ < 0) { pendingI_ = value; continue; }
            const double in = (pendingI_ - 127.5) / 127.5;
            const double quad = (value - 127.5) / 127.5;
            pendingI_ = -1;
            accR_ += in * ncoR_ - quad * ncoI_;
            accI_ += in * ncoI_ + quad * ncoR_;
            const double r = ncoR_ * stepR_ - ncoI_ * stepI_;
            ncoI_ = ncoR_ * stepI_ + ncoI_ * stepR_;
            ncoR_ = r;
            if (++count_ == decimation_) {
                out.push_back({static_cast<float>(accR_ / decimation_), static_cast<float>(accI_ / decimation_)});
                accR_ = accI_ = 0;
                count_ = 0;
                // Renormalise the oscillator so rounding never lets it drift.
                const double mag = std::sqrt(ncoR_ * ncoR_ + ncoI_ * ncoI_);
                ncoR_ /= mag; ncoI_ /= mag;
            }
        }
    }

private:
    int decimation_;
    int pendingI_ = -1;
    int count_ = 0;
    double accR_ = 0, accI_ = 0;
    double ncoR_ = 1, ncoI_ = 0, stepR_ = 1, stepI_ = 0;
};

} // namespace meshsdr
