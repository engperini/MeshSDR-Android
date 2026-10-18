// Decoder diversity for lora_lite (MeshSDR).
//
// Decimating 1 MS/s to 2 samples per chip keeps one of `decimation` possible
// sample phases. Field captures from a dongle with an unstable oscillator
// decoded at some phases and failed at others, with or without the averaging
// filter, and some only with drift tracking. Extended mode runs a decoder for
// every combination (phase x filter on/off x tracking off/on) on the same
// stream. The primary decoder (phase 0, the user's filter setting, no
// tracking) behaves exactly like a single decoder and reports every event
// (detections, CRC failures); the others only add frames whose CRC passed
// without the nibble chase, and a frame already reported is not reported again.
//
// The decoders run on several threads; their events are collected and reported
// afterwards on the calling thread (JNI), primary first.
#pragma once
#include <cstdint>
#include <cstring>
#include <memory>
#include <thread>
#include <vector>
#include "lora_lite.h"
#include "iq_u8.h"

namespace meshsdr {

class PhaseDiversity {
public:
    using Callback = void (*)(const lora_lite::Packet&, void*);

    // Extended: up to 8 decimation phases (evenly spaced when there are more,
    // e.g. 32 at BW 15.6 kHz), both filter settings, with and without tracking:
    // at most 32 decoders. Otherwise a single decoder, as before.
    PhaseDiversity(bool extended, int decimation, double shiftHz, double sampleRate, bool average,
                   int sf, int cr, uint16_t sync, uint32_t bw, uint32_t centerHz, Callback cb, void* user)
        : cb_(cb), user_(user), window_(static_cast<int64_t>(sampleRate) * 2 * 4) {
        add(0, average, 0.0f, true, decimation, shiftHz, sampleRate, sf, cr, sync, bw, centerHz);
        if (!extended) return;
        const int step = (decimation + kMaxPhases - 1) / kMaxPhases;
        for (float track : {0.0f, kTrackGain})
            for (bool avg : {average, !average})
                for (int phase = 0; phase < decimation; phase += step)
                    if ((phase != 0 || avg != average || track != 0.0f) && (decimation > 1 || avg == average))
                        add(phase, avg, track, false, decimation, shiftHz, sampleRate, sf, cr, sync, bw, centerHz);
    }

    void push(const uint8_t* data, int length) {
        const int n = static_cast<int>(branches_.size());
        const int workers = n == 1 ? 1 : threads_;
        auto run = [&](int w) {
            for (int i = w; i < n; i += workers) {
                Branch& b = *branches_[i];
                b.samples.clear();
                b.frontEnd.push(data, length, b.samples);
                b.demod.feed(b.samples.data(), static_cast<int>(b.samples.size()));
            }
        };
        std::vector<std::thread> pool;
        for (int w = 1; w < workers; ++w) pool.emplace_back(run, w);
        run(0);
        for (auto& t : pool) t.join();
        for (auto& b : branches_) {
            for (const auto& p : b->pending) onPacket(*b, p);
            b->pending.clear();
        }
        bytes_ += length;
    }

    int decoders() const { return static_cast<int>(branches_.size()); }

private:
    static constexpr float kTrackGain = 0.25f;
    static constexpr int kMaxPhases = 8;

    void add(int phase, bool average, float track, bool primary, int decimation, double shiftHz,
             double sampleRate, int sf, int cr, uint16_t sync, uint32_t bw, uint32_t centerHz) {
        auto b = std::make_unique<Branch>(decimation, shiftHz, sampleRate, average, phase);
        b->owner = this;
        b->primary = primary;
        b->demod.init(sf, cr, true, sync, &PhaseDiversity::received, b.get(), bw, centerHz);
        b->demod.set_tracking(track);
        b->samples.reserve(16384);
        branches_.push_back(std::move(b));
    }

    struct Branch {
        Branch(int decimation, double shiftHz, double sampleRate, bool average, int phase)
            : frontEnd(decimation, shiftHz, sampleRate, average, phase) {}
        IqFrontEnd frontEnd;
        lora_lite::Demod demod;
        std::vector<lora_lite::Cx> samples;
        std::vector<lora_lite::Packet> pending;   // events of the current push
        PhaseDiversity* owner = nullptr;
        bool primary = false;
    };

    static void received(const lora_lite::Packet& p, void* user) {
        static_cast<Branch*>(user)->pending.push_back(p);
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
    const int threads_ = [] {
        const unsigned c = std::thread::hardware_concurrency();
        return c < 2 ? 1 : static_cast<int>(c > 4 ? 4 : c);
    }();
    const int64_t window_;   // ~4 s of input bytes
};

} // namespace meshsdr
