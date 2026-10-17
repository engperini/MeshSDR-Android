#include <jni.h>
#include <cstdint>
#include <vector>
#include "lora_lite.h"

struct Receiver {
    lora_lite::Demod demod;
    jobject listener;
    jmethodID onPacket;
    JNIEnv* env = nullptr;
    int samplePhase = 0;
    std::vector<lora_lite::Cx> samples;
    Receiver(JNIEnv* e, jobject callback) : listener(e->NewGlobalRef(callback)) {
        jclass cls = e->GetObjectClass(callback);
        onPacket = e->GetMethodID(cls, "onPacket", "([B)V");
        e->DeleteLocalRef(cls);
        samples.reserve(16384);
    }
    static void received(const lora_lite::Packet& p, void* user) {
        auto* self = static_cast<Receiver*>(user);
        if (p.status != lora_lite::PKT_DECODED || (p.has_crc && !p.crc_ok) || !self->env) return;
        JNIEnv* e = self->env;
        jbyteArray data = e->NewByteArray(p.len);
        if (!data) return;
        e->SetByteArrayRegion(data, 0, p.len, reinterpret_cast<const jbyte*>(p.data));
        e->CallVoidMethod(self->listener, self->onPacket, data);
        e->DeleteLocalRef(data);
    }
};
extern "C" JNIEXPORT jlong JNICALL
Java_com_engperini_meshsdr_LoraReceiver_nativeCreate(JNIEnv* env, jobject thiz) {
    auto* r = new Receiver(env, thiz);
    if (!r->onPacket) { env->DeleteGlobalRef(r->listener); delete r; return 0; }
    // Meshtastic US LongFast: SF11 / BW250k / CR4/5 / sync 0x2b.
    // rtl_tcp sample rate 1 MS/s; decimate by 2 to 500k complex samples/s.
    r->demod.init(11, 1, true, 0x2b, Receiver::received, r, 250000, 906875000);
    return reinterpret_cast<jlong>(r);
}
extern "C" JNIEXPORT void JNICALL
Java_com_engperini_meshsdr_LoraReceiver_nativeFeed(JNIEnv* env, jobject, jlong handle, jbyteArray bytes, jint length) {
    auto* r = reinterpret_cast<Receiver*>(handle);
    if (!r || length < 2) return;
    const jsize size = env->GetArrayLength(bytes);
    if (length > size) length = size;
    jbyte* ptr = env->GetByteArrayElements(bytes, nullptr);
    if (!ptr) return;
    r->samples.clear();
    for (int i = 0; i + 1 < length; i += 2) {
        if (r->samplePhase++ % 2 != 0) continue;
        const float real = ((static_cast<uint8_t>(ptr[i]) - 127.5f) / 127.5f);
        const float imag = ((static_cast<uint8_t>(ptr[i+1]) - 127.5f) / 127.5f);
        r->samples.push_back({real, imag});
    }
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
