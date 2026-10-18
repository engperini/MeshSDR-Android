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
#include "diversity.h"
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

constexpr double kFs = 1e6;          // rtl_tcp sample rate used by the app
// App front-end options; main() runs every case with the app default
// (filter on, no offset), with the filter off and with both options on.
struct Dsp { double offset; bool average; const char* name; };
Dsp gDsp = {0, false, "default"};

struct Channel {
    double ppm = 0;            // combined crystal error: carrier and chip rate
    double dcSpur = 0.05;      // RTL2832U DC offset/LO leakage at the tuner centre
    double snrDb = 100;        // in-band (signal BW) SNR
    int leadSamples = 3001;    // noise before the frame (odd => fractional alignment)
    double amplitude = 0.45;   // of full scale, before quantization
};

// Frame(s) at fs complex samples/s -> rtl_tcp style unsigned 8-bit I/Q bytes.
std::vector<uint8_t> toRtlTcp(const std::vector<std::vector<loratx::cf>>& frames, double fs, double bw,
                              const Channel& ch, uint32_t seed, double carrierHz = 919.875e6) {
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
        // Channel sits gDsp.offset below the tuner centre, plus crystal CFO.
        ph += 2 * M_PI * (ch.ppm * 1e-6 * carrierHz - gDsp.offset) / fs;
        s += loratx::cf(noise(rng), noise(rng)) + loratx::cf(ch.dcSpur, -ch.dcSpur);
        auto q = [](double v) { long x = std::lround(127.5 + 127.5 * v); return static_cast<uint8_t>(x < 0 ? 0 : x > 255 ? 255 : x); };
        out.push_back(q(s.real()));
        out.push_back(q(s.imag()));
    }
    return out;
}

// Feed bytes through the same path as the JNI bridge, in random read sizes.
void runRtlTcp(lora_lite::Demod& demod, const std::vector<uint8_t>& bytes, uint32_t seed, bool tinyReads,
               int decimation = 2) {
    meshsdr::IqFrontEnd dec(decimation, gDsp.offset, kFs, gDsp.average);   // channel is at -offset
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
    return p.status == lora_lite::PKT_DECODED && p.has_crc && p.len == want.size() &&
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
    const auto bytes = toRtlTcp(frames, kFs, 250e3, ch, seed);
    Sink sink;
    auto demod = std::make_unique<lora_lite::Demod>();
    // The app accepts any sync word (0) and reports the detected one.
    demod->init(11, 1, true, 0, Sink::cb, &sink, 250000, 919875000);
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
    std::printf("[matrix] SF7..12 x CR4/5..4/8 x BW15.6/31.25/125/250k, direct complex input\n");
    int ok = 0, total = 0;
    for (int bw : {15625, 31250, 125000, 250000}) for (int sf = 7; sf <= 12; ++sf) for (int cr = 1; cr <= 4; ++cr) {
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
    demod->init(11, 1, true, 0, Sink::cb, &sink, 250000, 919875000);
    const auto t0 = std::chrono::steady_clock::now();
    runRtlTcp(*demod, bytes, 5, false);
    const double secs = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
    std::printf("  5.0 s of 1 MS/s IQ processed in %.2f s on this host (%.0fx real time)\n", secs, 5.0 / secs);
    CHECK(sink.decoded.empty(), "noise produced %zu frames", sink.decoded.size());
    CHECK(sink.headers == 0, "noise produced %d false header detections", sink.headers);
}

// The app's 32 MeshCore decoders (diversity.h) must not turn noise into frames either.
void testDiversityNoise() {
    std::printf("[noise-diversity] 20 s of noise through the MeshCore decoder diversity\n");
    std::mt19937 rng(7);
    std::normal_distribution<double> n(0.0, 0.2);
    std::vector<uint8_t> bytes(2 * 20000000);
    for (auto& b : bytes) { long x = std::lround(127.5 + 127.5 * n(rng)); b = static_cast<uint8_t>(x < 0 ? 0 : x > 255 ? 255 : x); }
    Sink sink;
    meshsdr::PhaseDiversity d(true, 8, 0, kFs, true, 8, 4, 0, 62500, 923125000, Sink::cb, &sink);
    const auto t0 = std::chrono::steady_clock::now();
    for (size_t i = 0; i < bytes.size(); i += 65536) d.push(bytes.data() + i, (int)std::min<size_t>(65536, bytes.size() - i));
    const double secs = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
    int decoded = 0;
    for (const auto& p : sink.decoded) decoded += p.status == lora_lite::PKT_DECODED;
    std::printf("  %d decoders, %.1fx real time on this host; %d header detections, %d decoded\n",
                d.decoders(), 20.0 / secs, sink.headers, decoded);
    CHECK(decoded == 0, "noise produced %d decoded frames", decoded);
    // Every bandwidth stays within 32 decoders (decimation 1 MS/s -> 2 samples/chip).
    for (int bw : {500000, 250000, 125000, 62500, 31250, 15625}) {
        const int dec = (int)(kFs / (2 * bw));
        meshsdr::PhaseDiversity m(true, dec, 0, kFs, true, 8, 4, 0, (uint32_t)bw, 923125000, Sink::cb, &sink);
        std::printf("  BW %.1f kHz: %d decoders\n", bw / 1e3, m.decoders());
        CHECK(m.decoders() <= 32, "BW %d: %d decoders", bw, m.decoders());
    }
}

// MeshCore Brazil: 923.125 MHz, BW 62.5 kHz, SF8, CR 4/8, private sync 0x12.
void testMeshCoreBr() {
    // Without the averaging filter, decimating 8x folds the whole 1 MHz of
    // noise into the channel: measured ~8 dB less sensitive (README), so the
    // default front end is checked at +4 dB and the filtered one at 0 dB.
    const double snr = gDsp.average ? 0 : 4;
    std::printf("[meshcore-br] SF8/BW62.5k CR4/8 sync 0x12 via 1 MS/s u8, decimation 8, +10 ppm, snr %.0f dB\n", snr);
    loratx::Params p;
    p.sf = 8; p.cr = 4; p.sync = 0x12; p.os = 16; p.preamble = 16;
    Channel ch; ch.ppm = 10; ch.snrDb = snr;
    const auto payload = meshtasticLike(60, 77);
    const auto bytes = toRtlTcp({loratx::modulate(payload, p, ch.ppm)}, kFs, 62.5e3, ch, 11, 923.125e6);
    Sink sink;
    auto demod = std::make_unique<lora_lite::Demod>();
    demod->init(8, 4, true, 0, Sink::cb, &sink, 62500, 923125000);
    runRtlTcp(*demod, bytes, 11, true, 8);
    CHECK(sink.decoded.size() == 1 && sameBytes(sink.decoded[0], payload), "decoded %zu", sink.decoded.size());
    if (!sink.decoded.empty()) CHECK(sink.decoded[0].sync == 0x12, "sync %02x", sink.decoded[0].sync);
}

// A frame whose payload is destroyed still produces a header detection.
void testDetectionWithoutDecode() {
    std::printf("[detect-only] payload jammed after the header: header-only event, no CRC-ok frame\n");
    loratx::Params p;
    const auto payload = meshtasticLike(48, 5);
    auto frame = loratx::modulate(payload, p);
    const size_t headerEnd = (p.preamble + 4.25 + 8) * 2048 * p.os;   // preamble+sync+downchirps+header block
    std::mt19937 rng(3);
    std::normal_distribution<double> jam(0.0, 30.0);
    for (size_t i = headerEnd + 4096; i < frame.size(); ++i) frame[i] += loratx::cf(jam(rng), jam(rng));
    Channel ch; ch.snrDb = 20;
    const auto bytes = toRtlTcp({frame}, kFs, 250e3, ch, 9);
    Sink sink;
    auto demod = std::make_unique<lora_lite::Demod>();
    demod->init(11, 1, true, 0, Sink::cb, &sink, 250000, 919875000);
    runRtlTcp(*demod, bytes, 9, false);
    CHECK(sink.headers == 1, "header detections %d", sink.headers);
    for (const auto& f : sink.decoded) CHECK(f.status != lora_lite::PKT_DECODED, "jammed frame reported as decoded");
}

} // namespace

// Crystal error sweep, no noise. Before the SFO pre-compensation fix 30-45% of
// these failed (every |ppm| ~12-21 with Meshtastic's 16-symbol preamble).
void testPpmSweep() {
    int ok = 0, total = 0;
    std::string bad;
    const int before = failures;
    for (int ppm = -30; ppm <= 30; ++ppm) {
        Channel c; c.ppm = ppm; c.dcSpur = 0;
        const int f0 = failures;
        loratx::Params p;
        const auto payload = meshtasticLike(64, 3);
        const auto bytes = toRtlTcp({loratx::modulate(payload, p, c.ppm)}, kFs, 250e3, c, 3);
        Sink sink;
        auto demod = std::make_unique<lora_lite::Demod>();
        demod->init(11, 1, true, 0, Sink::cb, &sink, 250000, 919875000);
        runRtlTcp(*demod, bytes, 3, false);
        ++total;
        if (sink.decoded.size() == 1 && sameBytes(sink.decoded[0], payload)) ++ok;
        else bad += " " + std::to_string(ppm);
        failures = f0;
    }
    failures = before;
    std::printf("[ppm-sweep] LongFast, -30..+30 ppm, no noise: %d/%d decoded exactly; failing ppm:%s\n", ok, total, bad.c_str());
    CHECK(ok * 100 >= total * 90, "only %d/%d ppm values decoded (need >= 90%%)", ok, total);
}

// Real over-the-air MeshCore packet (data/README.md): exact bytes required.
void testRealCapture() {
    if (gDsp.offset != 0) return;   // recorded with the tuner on the channel
    std::printf("[real-capture] MeshCore packet recorded in Jundiai, 17/10/2026\n");
    const std::string path = std::string(MESHSDR_TEST_DATA) + "/meshcore_jundiai_20261017_923125000Hz_1Msps.cu8";
    FILE* f = std::fopen(path.c_str(), "rb");
    CHECK(f != nullptr, "cannot open %s", path.c_str());
    if (!f) return;
    std::vector<uint8_t> bytes;
    uint8_t buf[1 << 16];
    size_t n;
    while ((n = std::fread(buf, 1, sizeof buf, f)) > 0) bytes.insert(bytes.end(), buf, buf + n);
    std::fclose(f);
    const char* want =
        "0640b678c4df75c2098027c5d84cc9de9ff03127198fd268bb848fde560de2f8b9a98bc8045c3255f9f0f929eff740340f392a6e21e2470fa99efa6809881436be0535c9df8f";
    std::vector<uint8_t> expected;
    for (size_t i = 0; want[i] && want[i + 1]; i += 2) expected.push_back((uint8_t)std::strtol(std::string(want + i, 2).c_str(), nullptr, 16));
    Sink sink;
    auto demod = std::make_unique<lora_lite::Demod>();
    demod->init(8, 4, true, 0, Sink::cb, &sink, 62500, 923125000);
    runRtlTcp(*demod, bytes, 21, true, 8);
    CHECK(sink.decoded.size() == 1 && sameBytes(sink.decoded[0], expected), "decoded %zu frame(s)", sink.decoded.size());
    if (!sink.decoded.empty()) {
        CHECK(sink.decoded[0].sync == 0x12, "sync %02x", sink.decoded[0].sync);
        std::printf("  70 bytes exact, CRC-ok, SNR %.1f dB\n", sink.decoded[0].snr);
    }
}

// Real packet from a dongle without TCXO (data/README.md): one decoder finds it
// only at some decimation phases; the app's decoder diversity at all of them.
void testPhaseDiversity() {
    std::printf("[phase-diversity] drifting dongle, MeshCore PATH, Jundiai 18/10/2026\n");
    const std::string path = std::string(MESHSDR_TEST_DATA) + "/meshcore_jundiai_20261018_driftingdongle_923125000Hz_1Msps.cu8";
    FILE* f = std::fopen(path.c_str(), "rb");
    CHECK(f != nullptr, "cannot open %s", path.c_str());
    if (!f) return;
    std::vector<uint8_t> bytes;
    uint8_t buf[1 << 16];
    size_t n;
    while ((n = std::fread(buf, 1, sizeof buf, f)) > 0) bytes.insert(bytes.end(), buf, buf + n);
    std::fclose(f);
    const char* want = "2144995c83b0e28a786a5794df8d241db803dabc629f1f0d822d82f5c78d";
    std::vector<uint8_t> expected;
    for (size_t i = 0; want[i] && want[i + 1]; i += 2) expected.push_back((uint8_t)std::strtol(std::string(want + i, 2).c_str(), nullptr, 16));
    int single = 0, diverse = 0;
    for (int start = 0; start < 8; ++start) {   // where the stream happens to begin, in samples
        for (bool extended : {false, true}) {
            Sink sink;
            meshsdr::PhaseDiversity d(extended, 8, 0, kFs, true, 8, 4, 0, 62500, 923125000, Sink::cb, &sink);
            for (size_t i = 2 * start; i < bytes.size(); i += 65536)
                d.push(bytes.data() + i, (int)std::min<size_t>(65536, bytes.size() - i));
            // Phase 0 may also report the CRC failure; exactly one decoded frame is expected.
            int good = 0, other = 0;
            for (const auto& p : sink.decoded)
                if (p.status == lora_lite::PKT_DECODED) (sameBytes(p, expected) ? good : other)++;
            const bool ok = good == 1 && other == 0;
            (extended ? diverse : single) += ok;
        }
    }
    std::printf("  start offsets decoded: single decoder %d/8, diversity %d/8\n", single, diverse);
    CHECK(diverse == 8, "diversity decoded %d/8 start offsets", diverse);
}

void runAll() {
    std::printf("\n===== front end: %s (offset %.0f kHz, average %s) =====\n", gDsp.name, gDsp.offset / 1e3, gDsp.average ? "on" : "off");
    testLongFastRtlTcp("clean", Channel{}, {40}, false, 1);
    testLongFastRtlTcp("reads", Channel{}, {1, 16, 77}, true, 2);
    { Channel c; c.ppm = 5; c.snrDb = 0; testLongFastRtlTcp("ppm+noise", c, {64}, true, 3); }
    { Channel c; c.ppm = -3; c.snrDb = -10; testLongFastRtlTcp("weak", c, {32}, false, 4); }
    { Channel c; c.ppm = 25; c.snrDb = 10; testLongFastRtlTcp("cheap-xtal", c, {200}, false, 7); }
    { Channel c; c.ppm = -25; c.snrDb = 10; testLongFastRtlTcp("cheap-xtal-neg", c, {120}, false, 8); }
    testLongFastRtlTcp("max-payload", Channel{}, {255}, false, 6);
    testMeshCoreBr();
    testDetectionWithoutDecode();
    testRealCapture();
    if (gDsp.offset == 0 && gDsp.average) testPhaseDiversity();
    testPpmSweep();
    testNoFalsePositives();
}

int main() {
    testTablesMatchReference();
    testMatrix();
    testDiversityNoise();
    for (Dsp d : {Dsp{0, true, "app default (filter on)"}, Dsp{0, false, "filter off"}, Dsp{250e3, true, "offset + filter"}}) { gDsp = d; runAll(); }
    std::printf(failures ? "\n%d check(s) FAILED\n" : "\nall LoRa PHY checks passed\n", failures);
    return failures ? 1 : 0;
}
