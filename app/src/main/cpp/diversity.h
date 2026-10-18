// Decimation-phase diversity for lora_lite (MeshSDR).
//
// Decimating 1 MS/s to 2 samples per chip keeps one of `decimation` possible
// sample phases. Field captures from a dongle with an unstable oscillator
// decoded at some phases and failed at others, and the app had no way to pick
// the right one. This runs one front end + decoder per phase on the same
// stream. Phase 0 behaves exactly like a single decoder and reports every
// event (detections, CRC failures); the other phases only add frames whose
// CRC passed without the nibble chase, and a frame already reported is not
// reported again.
#pragma once
#include <cstdint>
#include <cstring>
#include <memory>
#include <vector>
#include "lora_lite.h"
#include "iq_u8.h"

namespace meshsdr {

class PhaseDiversity {
public:
    using Callback = void (*)(const lora_lite::Packet&, void*);

    // `phases` decoders, spread evenly over the decimation groups (at most `decimation`).
    PhaseDiversity(int phases, int decimation, double shiftHz, double sampleRate, bool average,
                   int sf, int cr, uint16_t sync, uint32_t bw, uint32_t centerHz, Callback cb, void* user)
        : cb_(cb), user_(user), window_(static_cast<int64_t>(sampleRate) * 2 * 4) {
        if (phases > decimation) phases = decimation;
        if (phases < 1) phases = 1;
        for (int k = 0; k < phases; ++k) {
            const int phase = k * decimation / phases;
            auto b = std::make_unique<Branch>(decimation, shiftHz, sampleRate, average, phase);
            b->owner = this;
            b->primary = k == 0;
            b->demod.init(sf, cr, true, sync, &PhaseDiversity::received, b.get(), bw, centerHz);
            b->samples.reserve(16384);
            branches_.push_back(std::move(b));
        }
    }

    void push(const uint8_t* data, int length) {
        for (auto& b : branches_) {
            b->samples.clear();
            b->frontEnd.push(data, length, b->samples);
            b->demod.feed(b->samples.data(), static_cast<int>(b->samples.size()));
        }
        bytes_ += length;
    }

    int phases() const { return static_cast<int>(branches_.size()); }

private:
    struct Branch {
        Branch(int decimation, double shiftHz, double sampleRate, bool average, int phase)
            : frontEnd(decimation, shiftHz, sampleRate, average, phase) {}
        IqFrontEnd frontEnd;
        lora_lite::Demod demod;
        std::vector<lora_lite::Cx> samples;
        PhaseDiversity* owner = nullptr;
        bool primary = false;
    };

    static void received(const lora_lite::Packet& p, void* user) {
        auto* b = static_cast<Branch*>(user);
        b->owner->onPacket(*b, p);
    }

    void onPacket(const Branch& b, const lora_lite::Packet& p) {
        const bool decoded = p.status == lora_lite::PKT_DECODED && p.crc_ok;
        if (!b.primary && !(decoded && p.has_crc && p.crc_method != 2)) return;
        if (decoded) {
            if (seenRecently(p)) return;
            remember(p);
        }
        cb_(p, user_);
    }

    bool seenRecently(const lora_lite::Packet& p) const {
        for (const auto& r : recent_)
            if (bytes_ - r.at <= window_ && r.len == p.len && memcmp(r.data, p.data, p.len) == 0) return true;
        return false;
    }

    void remember(const lora_lite::Packet& p) {
        Recent r;
        r.at = bytes_;
        r.len = p.len;
        memcpy(r.data, p.data, p.len);
        if (recent_.size() >= 8) recent_.erase(recent_.begin());
        recent_.push_back(r);
    }

    struct Recent { int64_t at; int len; uint8_t data[LORA_LITE_MAX_PAYLOAD]; };
    Callback cb_;
    void* user_;
    std::vector<std::unique_ptr<Branch>> branches_;
    std::vector<Recent> recent_;
    int64_t bytes_ = 0;
    const int64_t window_;   // ~4 s of input bytes
};

} // namespace meshsdr
