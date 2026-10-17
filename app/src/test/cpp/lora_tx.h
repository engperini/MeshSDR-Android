// Reference LoRa modulator for host tests (explicit header mode).
//
// Written independently of lora_lite.h, following the gr-lora_sdr TX chain:
// whitening -> header + CRC -> Hamming -> diagonal interleaver -> inverse Gray
// (+1 shift) -> chirp modulation with preamble, sync word and 2.25 downchirps.
// Only the whitening sequence is a published constant shared with the RX.
#pragma once
#include <cmath>
#include <complex>
#include <cstdint>
#include <vector>

namespace loratx {

using cf = std::complex<double>;

struct Params {
    int sf = 11;
    int cr = 1;               // 1..4 => 4/5..4/8
    bool crc = true;
    uint8_t sync = 0x2b;
    int preamble = 16;        // Meshtastic uses 16 upchirps
    int os = 4;               // samples per chip (1 MS/s / 250 kHz)
    bool ldro = false;
};

// LFSR x^8+x^6+x^5+x^4+1 whitening, as in the LoRa/SX127x reference.
inline std::vector<uint8_t> whitening(int n) {
    std::vector<uint8_t> w(n);
    uint8_t lfsr = 0xFF;
    for (int i = 0; i < n; ++i) {
        w[i] = lfsr;
        const uint8_t fb = ((lfsr >> 7) ^ (lfsr >> 5) ^ (lfsr >> 4) ^ (lfsr >> 3)) & 1;
        lfsr = static_cast<uint8_t>((lfsr << 1) | fb);
    }
    return w;
}

inline uint16_t crc16(const uint8_t* d, int len) {
    uint16_t crc = 0;
    for (int i = 0; i < len; ++i) {
        crc ^= static_cast<uint16_t>(d[i]) << 8;
        for (int k = 0; k < 8; ++k) crc = (crc & 0x8000) ? (crc << 1) ^ 0x1021 : (crc << 1);
    }
    return crc;
}

// Nibble stream: header(5) + whitened payload (low nibble first) + CRC(4).
inline std::vector<int> nibbles(const std::vector<uint8_t>& payload, const Params& p) {
    const int len = static_cast<int>(payload.size());
    std::vector<int> out;
    const int n0 = len >> 4, n1 = len & 0xF, n2 = (p.cr << 1) | (p.crc ? 1 : 0);
    auto b = [](int v, int k) { return (v >> k) & 1; };
    const int c4 = b(n0,3)^b(n0,2)^b(n0,1)^b(n0,0);
    const int c3 = b(n0,3)^b(n1,3)^b(n1,2)^b(n1,1)^b(n2,0);
    const int c2 = b(n0,2)^b(n1,3)^b(n1,0)^b(n2,3)^b(n2,1);
    const int c1 = b(n0,1)^b(n1,2)^b(n1,0)^b(n2,2)^b(n2,1)^b(n2,0);
    const int c0 = b(n0,0)^b(n1,1)^b(n2,3)^b(n2,2)^b(n2,1)^b(n2,0);
    out.insert(out.end(), {n0, n1, n2, c4, (c3 << 3) | (c2 << 2) | (c1 << 1) | c0});
    const auto w = whitening(len);
    for (int i = 0; i < len; ++i) {
        const uint8_t v = payload[i] ^ w[i];
        out.push_back(v & 0xF);
        out.push_back(v >> 4);
    }
    if (p.crc) {
        uint16_t crc = len >= 2 ? crc16(payload.data(), len - 2) : 0;
        if (len >= 2) crc ^= payload[len - 1] ^ (static_cast<uint16_t>(payload[len - 2]) << 8);
        for (int k = 0; k < 4; ++k) out.push_back((crc >> (4 * k)) & 0xF);
    }
    return out;
}

// Hamming codeword, bits MSB-first in a vector of length 4+cr.
inline std::vector<int> hamming(int nib, int cr) {
    const int d0 = (nib >> 3) & 1, d1 = (nib >> 2) & 1, d2 = (nib >> 1) & 1, d3 = nib & 1;
    if (cr == 1) return {d3, d2, d1, d0, d0 ^ d1 ^ d2 ^ d3};
    const int p0 = d3 ^ d2 ^ d1, p1 = d2 ^ d1 ^ d0, p2 = d3 ^ d2 ^ d0, p3 = d3 ^ d1 ^ d0;
    std::vector<int> cw = {d3, d2, d1, d0, p0, p1, p2, p3};
    cw.resize(4 + cr);
    return cw;
}

inline std::vector<int> symbols(const std::vector<uint8_t>& payload, const Params& p) {
    const auto nib = nibbles(payload, p);
    const int N = 1 << p.sf;
    std::vector<int> out;
    size_t pos = 0;
    bool header = true;
    while (pos < nib.size()) {
        const bool reduced = header || p.ldro;
        const int sfApp = reduced ? p.sf - 2 : p.sf;
        const int cr = header ? 4 : p.cr;
        const int cwLen = 4 + cr;
        std::vector<std::vector<int>> cw;
        for (int k = 0; k < sfApp; ++k, ++pos)
            cw.push_back(hamming(pos < nib.size() ? nib[pos] : 0, cr));
        for (int i = 0; i < cwLen; ++i) {
            std::vector<int> bits(p.sf, 0);
            for (int j = 0; j < sfApp; ++j) {
                const int row = ((i - j - 1) % sfApp + sfApp) % sfApp;
                bits[j] = cw[row][i];
            }
            if (reduced) {
                int parity = 0;
                for (int j = 0; j < sfApp; ++j) parity ^= bits[j];
                bits[sfApp] = parity;
            }
            int v = 0;
            for (int j = 0; j < p.sf; ++j) v = (v << 1) | bits[j];
            int g = v;
            for (int j = 1; j < p.sf; ++j) g ^= v >> j;   // inverse Gray
            out.push_back((g + 1) % N);
        }
        header = false;
    }
    return out;
}

struct Segment { int id; bool down; double chips; };

inline std::vector<Segment> frame(const std::vector<uint8_t>& payload, const Params& p) {
    const int N = 1 << p.sf;
    std::vector<Segment> seg;
    for (int i = 0; i < p.preamble; ++i) seg.push_back({0, false, double(N)});
    seg.push_back({((p.sync & 0xF0) >> 4) << 3, false, double(N)});
    seg.push_back({(p.sync & 0x0F) << 3, false, double(N)});
    seg.push_back({0, true, double(N)});
    seg.push_back({0, true, double(N)});
    seg.push_back({0, true, N / 4.0});
    for (int s : symbols(payload, p)) seg.push_back({s, false, double(N)});
    return seg;
}

// Full frame, unit amplitude, continuous phase. `ppm` models the transmitter
// crystal: chips run (1+ppm) faster and the carrier moves by ppm * carrier,
// which is added separately by the channel.
inline std::vector<cf> modulate(const std::vector<uint8_t>& payload, const Params& p, double ppm = 0) {
    const double N = 1 << p.sf;
    const double os = p.os / (1.0 + ppm * 1e-6);   // samples per (faster) chip
    std::vector<cf> out;
    double phase = 0, start = 0;                    // segment start, in chips
    size_t n = 0;
    for (const auto& sg : frame(payload, p)) {
        const double end = start + sg.chips;
        for (; n / os < end; ++n) {
            const double t = n / os - start;
            double f = sg.id / N - 0.5 + t / N;     // cycles per chip
            if (f >= 0.5) f -= 1.0;
            if (sg.down) f = -f;
            out.push_back(std::polar(1.0, phase));
            phase += 2 * M_PI * f / os;
        }
        start = end;
    }
    return out;
}

} // namespace loratx
