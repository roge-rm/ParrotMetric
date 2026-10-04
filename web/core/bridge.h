#pragma once

// The browser's way into jni.cpp. Kotlin encodes each NativeCore call's
// arguments into bytes (WebBridge.kt), dispatch.cpp decodes them into
// stand-ins for Java strings and arrays, runs jni.cpp's function with a
// stand-in JNIEnv, and encodes what it returns. All little-endian.

#include <jni.h>

#include <cstdint>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

namespace pmweb {

/** A Java object as jni.cpp sees it: a string or an array. Freed after each call. */
struct Obj {
    virtual ~Obj() = default;
    virtual size_t length() const { return 0; }
};
struct Str : Obj {
    std::string s;
};
template <class T>
struct Arr : Obj {
    std::vector<T> v;
    size_t length() const override { return v.size(); }
};
struct ObjArr : Obj {
    std::vector<Obj*> v;
    size_t length() const override { return v.size(); }
};

/** Keeps a call's objects until it's done. */
Obj* keep(std::unique_ptr<Obj> o);
template <class T>
T* make() {
    return static_cast<T*>(keep(std::make_unique<T>()));
}

/** True if the call threw; the message goes back to Kotlin as a RuntimeException. */
bool failed();
void throwMessage(const std::string& why);

class Reader {
public:
    Reader(const uint8_t* p, size_t n) : p_(p), end_(p + n) {}
    jint i32() { return get<jint>(); }
    jlong i64() { return get<jlong>(); }
    jfloat f32() { return get<jfloat>(); }
    jdouble f64() { return get<jdouble>(); }
    jboolean boolean() { return get<uint8_t>() ? JNI_TRUE : JNI_FALSE; }
    jstring string() {
        auto* s = make<Str>();
        jint n = i32();
        s->s.assign(reinterpret_cast<const char*>(p_), size_t(n));
        p_ += n;
        return reinterpret_cast<jstring>(s);
    }
    jintArray ints() { return reinterpret_cast<jintArray>(array<jint>()); }
    jlongArray longs() { return reinterpret_cast<jlongArray>(array<jlong>()); }
    jfloatArray floats() { return reinterpret_cast<jfloatArray>(array<jfloat>()); }
    jdoubleArray doubles() { return reinterpret_cast<jdoubleArray>(array<jdouble>()); }
    jbyteArray bytes() { return reinterpret_cast<jbyteArray>(array<jbyte>()); }
    jobjectArray strings() {
        auto* a = make<ObjArr>();
        jint n = i32();
        for (jint i = 0; i < n; ++i) a->v.push_back(reinterpret_cast<Obj*>(string()));
        return reinterpret_cast<jobjectArray>(a);
    }

private:
    template <class T>
    T get() {
        T v;
        std::memcpy(&v, p_, sizeof v);
        p_ += sizeof v;
        return v;
    }
    template <class T>
    Arr<T>* array() {
        auto* a = make<Arr<T>>();
        jint n = i32();
        a->v.resize(size_t(n));
        std::memcpy(a->v.data(), p_, sizeof(T) * size_t(n));
        p_ += sizeof(T) * size_t(n);
        return a;
    }
    const uint8_t* p_;
    const uint8_t* end_;
};

class Writer {
public:
    std::vector<uint8_t> bytes;
    void put(jint v) { add(v); }
    void put(jlong v) { add(v); }
    void put(jfloat v) { add(v); }
    void put(jdouble v) { add(v); }
    void put(jboolean v) { add(uint8_t(v ? 1 : 0)); }
    void put(jstring s) {
        add(uint8_t(s ? 1 : 0));
        if (!s) return;
        const auto& t = reinterpret_cast<Str*>(s)->s;
        add(jint(t.size()));
        raw(t.data(), t.size());
    }
    void put(jintArray a) { array<jint>(a); }
    void put(jlongArray a) { array<jlong>(a); }
    void put(jfloatArray a) { array<jfloat>(a); }
    void put(jdoubleArray a) { array<jdouble>(a); }
    void put(jbyteArray a) { array<jbyte>(a); }
    void put(jobjectArray a) {
        add(uint8_t(a ? 1 : 0));
        if (!a) return;
        auto* o = reinterpret_cast<ObjArr*>(a);
        add(jint(o->v.size()));
        for (Obj* s : o->v) {
            const std::string& t = s ? static_cast<Str*>(s)->s : std::string();
            add(jint(t.size()));
            raw(t.data(), t.size());
        }
    }

private:
    template <class T>
    void add(T v) { raw(&v, sizeof v); }
    void raw(const void* p, size_t n) {
        auto* b = static_cast<const uint8_t*>(p);
        bytes.insert(bytes.end(), b, b + n);
    }
    template <class T>
    void array(void* a) {
        add(uint8_t(a ? 1 : 0));
        if (!a) return;
        auto* o = static_cast<Arr<T>*>(reinterpret_cast<Obj*>(a));
        add(jint(o->v.size()));
        raw(o->v.data(), sizeof(T) * o->v.size());
    }
};

void dispatch(int call, Reader& in, Writer& out, JNIEnv* env);

}  // namespace pmweb
