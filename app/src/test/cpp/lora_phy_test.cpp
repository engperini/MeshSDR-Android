// Host-side LoRa PHY tests: reference modulator -> (rtl_tcp u8 IQ) -> decoder.
// Proves exact payload bytes are recovered, not just that the code compiles.
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <random>
#include <string>
#include <vector>

#include "lora_lite.h"
#include "iq_u8.h"
#include "lora_tx.h"

namespace {

int failures = 0;
#define CHECK(cond, ...) do { if (!(cond)) { ++failures; std::printf("  FAIL %s:%d: ", __FILE__, __LINE__); \
    std::printf(__VA_ARGS__); std::printf("\n"); } } while (0)

struct Sink {
    std::vector<lora_lite::Packet> decoded;   // PKT_DECODED or PKT_CRC_FAIL
    int headers = 0;
    static void cb(const lora_lite::Packet& p, void* user) {
        auto* s = static_cast<Sink*>(user);
        if (p.status == lora_lite::PKT_HEADER_ONLY) s->headers++;
        else s->decoded.push_back(p);
    }
};

std::vector<uint8_t> meshtasticLike(int len, uint32_t seed) {
    // 16-byte Meshtastic radio header (dest, from, id, flags, channel hash,
    // next-hop, relay) followed by opaque bytes.
    std::vector<uint8_t> p = {0xFF,0xFF,0xFF,0xFF, 0x78,0x56,0x34,0x12, 0x01,0x00,0x00,0x00, 0x63, 0x08, 0x00, 0x00};
    std::mt19937 rng(seed);
    while (static_cast<int>(p.size()) < len) p.push_back(static_cast<uint8_t>(rng()));
    p.resize(len);
    return p;
}

std::string hex(const uint8_t* d, int n) {
    std::string s;
    char b[4];
    for (int i = 0; i < n && i < 24; ++i) { std::snprintf(b, sizeof b, "%02x", d[i]); s += b; }
    return n > 24 ? s + "..." : s;
}

struct Channel {
    double ppm = 0;            // combined crystal error: carrier and chip rate
    double snrDb = 100;        // in-band (signal BW) SNR
    int leadSamples = 3001;    // noise before the frame (odd => fractional alignment)
    double amplitude = 0.45;   // of full scale, before quantization
};

// Frame(s) at fs complex samples/s -> rtl_tcp style unsigned 8-bit I/Q bytes.
std::vector<uint8_t> toRtlTcp(const std::vector<std::vector<loratx::cf>>& frames, double fs, double bw,
                              const Channel& ch, uint32_t seed) {
    std::mt19937 rng(seed);
    const double sigma = ch.amplitude / std::sqrt(2.0) * std::pow(10.0, -ch.snrDb / 20.0) * std::sqrt(fs / bw);
    std::normal_distribution<double> noise(0.0, sigma);
    std::vector<loratx::cf> iq(ch.leadSamples);
    for (const auto& f : frames) {
        for (const auto& s : f) iq.push_back(s * ch.amplitude);
        iq.resize(iq.size() + 20000);
    }
    std::vector<uint8_t> out;
    out.reserve(iq.size() * 2);
    double ph = 0;
    for (auto s : iq) {
        s *= std::polar(1.0, ph);
        ph += 2 * M_PI * (ch.ppm * 1e-6 * 906.875e6) / fs;
        s += loratx::cf(noise(rng), noise(rng));
        auto q = [](double v) { long x = std::lround(127.5 + 127.5 * v); return static_cast<uint8_t>(x < 0 ? 0 : x > 255 ? 255 : x); };
        out.push_back(q(s.real()));
        out.push_back(q(s.imag()));
    }
    return out;
}

// Feed bytes through the same path as the JNI bridge, in random read sizes.
void runRtlTcp(lora_lite::Demod& demod, const std::vector<uint8_t>& bytes, uint32_t seed, bool tinyReads) {
    meshsdr::U8Decimator dec;
    std::vector<lora_lite::Cx> buf;
    std::mt19937 rng(seed);
    std::uniform_int_distribution<int> big(1, 65536), small(1, 7);
    size_t pos = 0;
    while (pos < bytes.size()) {
        int n = tinyReads && (rng() % 4 == 0) ? small(rng) : big(rng);
        if (pos + n > bytes.size()) n = static_cast<int>(bytes.size() - pos);
        buf.clear();
        dec.push(bytes.data() + pos, n, buf);
        demod.feed(buf.data(), static_cast<int>(buf.size()));
        pos += n;
    }
}

bool sameBytes(const lora_lite::Packet& p, const std::vector<uint8_t>& want) {
    return p.status == lora_lite::PKT_DECODED && p.len == want.size() &&
           std::equal(want.begin(), want.end(), p.data);
}

void testTablesMatchReference() {
    std::printf("[tables] whitening + CRC match reference\n");
    const auto w = loratx::whitening(255);
    for (int i = 0; i < 255; ++i) CHECK(w[i] == lora_lite::WHITEN[i], "whitening[%d] %02x != %02x", i, w[i], lora_lite::WHITEN[i]);
    const uint8_t v[] = {'1','2','3','4','5','6','7','8','9'};
    CHECK(loratx::crc16(v, 9) == 0x31C3, "crc16 xmodem check value");
    CHECK(lora_lite::crc16_ccitt(v, 9) == 0x31C3, "lora_lite crc16 check value");
}

// Meshtastic US LongFast through the real app path: 1 MS/s u8 -> 500 kS/s.
void testLongFastRtlTcp(const char* name, Channel ch, std::vector<int> lens, bool tinyReads, uint32_t seed) {
    std::printf("[%s] SF11/BW250k CR4/5 sync 0x2b, %zu frame(s), %+.0f ppm (%+.1f kHz), snr %.1f dB\n",
                name, lens.size(), ch.ppm, ch.ppm * 0.906875, ch.snrDb);
    loratx::Params p;   // defaults are LongFast at os=4 (1 MS/s)
    std::vector<std::vector<uint8_t>> payloads;
    std::vector<std::vector<loratx::cf>> frames;
    for (size_t i = 0; i < lens.size(); ++i) {
        payloads.push_back(meshtasticLike(lens[i], seed + static_cast<uint32_t>(i)));
        frames.push_back(loratx::modulate(payloads.back(), p, ch.ppm));
    }
    const auto bytes = toRtlTcp(frames, 1e6, 250e3, ch, seed);
    Sink sink;
    auto demod = std::make_unique<lora_lite::Demod>();
    demod->init(11, 1, true, 0x2b, Sink::cb, &sink, 250000, 906875000);
    runRtlTcp(*demod, bytes, seed, tinyReads);
    CHECK(sink.decoded.size() == payloads.size(), "decoded %zu frames, expected %zu (headers seen %d)",
          sink.decoded.size(), payloads.size(), sink.headers);
    for (size_t i = 0; i < payloads.size() && i < sink.decoded.size(); ++i) {
        const auto& got = sink.decoded[i];
        CHECK(sameBytes(got, payloads[i]), "frame %zu: status %d len %d crc_ok %d\n    want %s\n    got  %s",
              i, got.status, got.len, got.crc_ok, hex(payloads[i].data(), (int)payloads[i].size()).c_str(),
              hex(got.data, got.len).c_str());
        CHECK(got.sync == 0x2b, "frame %zu: sync %02x", i, got.sync);
    }
}

// Decoder core across SF/CR/BW at its native 2 samples per chip.
void testMatrix() {
    std::printf("[matrix] SF7..12 x CR4/5..4/8 x BW125/250k, direct complex input\n");
    int ok = 0, total = 0;
    for (int bw : {125000, 250000}) for (int sf = 7; sf <= 12; ++sf) for (int cr = 1; cr <= 4; ++cr) {
        loratx::Params p;
        p.sf = sf; p.cr = cr; p.os = 2; p.preamble = 8;
        p.ldro = (1 << sf) * 1e3 / bw > 16.0;
        const auto payload = meshtasticLike(5 + 7 * sf + cr, sf * 10 + cr);
        auto frame = loratx::modulate(payload, p);
        std::vector<lora_lite::Cx> iq(777, {0, 0});
        for (auto s : frame) iq.push_back({static_cast<float>(s.real()), static_cast<float>(s.imag())});
        iq.resize(iq.size() + 4 * (2 << sf), {0, 0});
        Sink sink;
        auto demod = std::make_unique<lora_lite::Demod>();
        demod->init(sf, 1, true, 0x2b, Sink::cb, &sink, bw, 906875000);
        demod->feed(iq.data(), static_cast<int>(iq.size()));
        ++total;
        const bool pass = sink.decoded.size() == 1 && sameBytes(sink.decoded[0], payload);
        if (pass) ++ok;
        CHECK(pass, "SF%d CR4/%d BW%d ldro %d: decoded %zu", sf, cr + 4, bw / 1000, p.ldro, sink.decoded.size());
    }
    std::printf("  %d/%d configurations decoded exactly\n", ok, total);
}

void testNoFalsePositives() {
    std::printf("[noise] 5 s of pure noise produces no decoded frame\n");
    std::mt19937 rng(99);
    std::normal_distribution<double> n(0.0, 0.2);
    std::vector<uint8_t> bytes(2 * 5000000);
    for (auto& b : bytes) { long x = std::lround(127.5 + 127.5 * n(rng)); b = static_cast<uint8_t>(x < 0 ? 0 : x > 255 ? 255 : x); }
    Sink sink;
    auto demod = std::make_unique<lora_lite::Demod>();
    demod->init(11, 1, true, 0x2b, Sink::cb, &sink, 250000, 906875000);
    const auto t0 = std::chrono::steady_clock::now();
    runRtlTcp(*demod, bytes, 5, false);
    const double secs = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
    std::printf("  5.0 s of 1 MS/s IQ processed in %.2f s on this host (%.0fx real time)\n", secs, 5.0 / secs);
    CHECK(sink.decoded.empty(), "noise produced %zu frames", sink.decoded.size());
}

} // namespace

int main() {
    testTablesMatchReference();
    testLongFastRtlTcp("clean", Channel{}, {40}, false, 1);
    testLongFastRtlTcp("reads", Channel{}, {1, 16, 77}, true, 2);
    { Channel c; c.ppm = 5; c.snrDb = 0; testLongFastRtlTcp("ppm+noise", c, {64}, true, 3); }
    { Channel c; c.ppm = -3; c.snrDb = -10; testLongFastRtlTcp("weak", c, {32}, false, 4); }
    { Channel c; c.ppm = 25; c.snrDb = 10; testLongFastRtlTcp("cheap-xtal", c, {200}, false, 7); }
    { Channel c; c.ppm = -25; c.snrDb = 10; testLongFastRtlTcp("cheap-xtal-neg", c, {120}, false, 8); }
    testLongFastRtlTcp("max-payload", Channel{}, {255}, false, 6);
    testMatrix();
    testNoFalsePositives();
    std::printf(failures ? "\n%d check(s) FAILED\n" : "\nall LoRa PHY checks passed\n", failures);
    return failures ? 1 : 0;
}
