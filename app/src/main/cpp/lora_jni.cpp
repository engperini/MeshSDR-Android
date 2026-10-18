#include <jni.h>
#include <cstdint>
#include <vector>
#include "lora_lite.h"
#include "iq_u8.h"

struct Receiver {
    lora_lite::Demod demod;
    meshsdr::IqFrontEnd frontEnd;
    jobject listener;
    jmethodID onFrame;
    JNIEnv* env = nullptr;
    std::vector<lora_lite::Cx> samples;
    /** Hz per FFT bin, to report the carrier offset in Hz. */
    float hzPerBin = 0;
    Receiver(JNIEnv* e, jobject callback, int decimation, double shiftHz, double sampleRate, bool average)
        : frontEnd(decimation, shiftHz, sampleRate, average), listener(e->NewGlobalRef(callback)) {
        jclass cls = e->GetObjectClass(callback);
        onFrame = e->GetMethodID(cls, "onFrame", "([BIIZZFIF)V");
        e->DeleteLocalRef(cls);
        samples.reserve(16384);
    }
    // Every event goes to Kotlin: header-only detections and CRC failures
    // too, so the UI can show LoRa activity it could not fully decode.
    static void received(const lora_lite::Packet& p, void* user) {
        auto* self = static_cast<Receiver*>(user);
        if (!self->env) return;
        JNIEnv* e = self->env;
        jbyteArray data = e->NewByteArray(p.len);
        if (!data) return;
        e->SetByteArrayRegion(data, 0, p.len, reinterpret_cast<const jbyte*>(p.data));
        e->CallVoidMethod(self->listener, self->onFrame, data, static_cast<jint>(p.status),
                          static_cast<jint>(p.hdr_len), static_cast<jboolean>(p.crc_ok),
                          static_cast<jboolean>(p.has_crc),
                          static_cast<jfloat>(p.snr), static_cast<jint>(p.sync),
                          static_cast<jfloat>(p.cfo * self->hzPerBin));
        e->DeleteLocalRef(data);
    }
};

extern "C" JNIEXPORT jlong JNICALL
Java_com_engperini_meshsdr_LoraReceiver_nativeCreate(JNIEnv* env, jobject thiz, jint sf, jint bandwidth,
                                                     jint codingRate, jint syncWord, jlong centerHz,
                                                     jint decimation, jdouble shiftHz, jdouble sampleRate,
                                                     jboolean average) {
    if (sf < 6 || sf > LORA_LITE_MAX_SF || decimation < 1) return 0;
    auto* r = new Receiver(env, thiz, decimation, shiftHz, sampleRate, average == JNI_TRUE);
    if (!r->onFrame) { env->DeleteGlobalRef(r->listener); delete r; return 0; }
    r->hzPerBin = static_cast<float>(bandwidth) / static_cast<float>(1 << sf);
    // syncWord 0 accepts any sync word; the detected one is reported per frame.
    r->demod.init(sf, codingRate, true, static_cast<uint16_t>(syncWord), Receiver::received, r,
                  static_cast<uint32_t>(bandwidth), static_cast<uint32_t>(centerHz));
    return reinterpret_cast<jlong>(r);
}

extern "C" JNIEXPORT void JNICALL
Java_com_engperini_meshsdr_LoraReceiver_nativeFeed(JNIEnv* env, jobject, jlong handle, jbyteArray bytes, jint length) {
    auto* r = reinterpret_cast<Receiver*>(handle);
    if (!r || length <= 0) return;
    const jsize size = env->GetArrayLength(bytes);
    if (length > size) length = size;
    jbyte* ptr = env->GetByteArrayElements(bytes, nullptr);
    if (!ptr) return;
    r->samples.clear();
    r->frontEnd.push(reinterpret_cast<const uint8_t*>(ptr), length, r->samples);
    env->ReleaseByteArrayElements(bytes, ptr, JNI_ABORT);
    r->env = env;
    r->demod.feed(r->samples.data(), static_cast<int>(r->samples.size()));
    r->env = nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_engperini_meshsdr_LoraReceiver_nativeDestroy(JNIEnv* env, jobject, jlong handle) {
    auto* r = reinterpret_cast<Receiver*>(handle);
    if (!r) return;
    env->DeleteGlobalRef(r->listener);
    delete r;
}
