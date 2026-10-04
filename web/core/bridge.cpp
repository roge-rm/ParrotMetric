// The stand-in JNIEnv jni.cpp runs with in the browser, and the entry points
// Kotlin calls through JavaScript (WebBridge.kt).

#include "bridge.h"

#include <emscripten/emscripten.h>
#include <emscripten/html5.h>

namespace pmweb {
namespace {

std::vector<std::unique_ptr<Obj>> objects;
bool threw = false;
std::string message;

Obj* obj(jobject o) { return reinterpret_cast<Obj*>(o); }

template <class T, class A>
void getRegion(JNIEnv*, A a, jsize start, jsize n, T* to) {
    auto& v = static_cast<Arr<T>*>(obj(a))->v;
    std::memcpy(to, v.data() + start, sizeof(T) * size_t(n));
}

template <class T, class A>
void setRegion(JNIEnv*, A a, jsize start, jsize n, const T* from) {
    auto& v = static_cast<Arr<T>*>(obj(a))->v;
    std::memcpy(v.data() + start, from, sizeof(T) * size_t(n));
}

template <class T, class R>
R newArray(JNIEnv*, jsize n) {
    auto* a = make<Arr<T>>();
    a->v.resize(size_t(n));
    return reinterpret_cast<R>(static_cast<Obj*>(a));
}

JNINativeInterface_ table() {
    JNINativeInterface_ t{};
    t.GetArrayLength = [](JNIEnv*, jarray a) { return jsize(obj(a)->length()); };
    t.GetByteArrayRegion = getRegion<jbyte, jbyteArray>;
    t.GetIntArrayRegion = getRegion<jint, jintArray>;
    t.GetLongArrayRegion = getRegion<jlong, jlongArray>;
    t.GetDoubleArrayRegion = getRegion<jdouble, jdoubleArray>;
    t.SetByteArrayRegion = setRegion<jbyte, jbyteArray>;
    t.SetIntArrayRegion = setRegion<jint, jintArray>;
    t.SetLongArrayRegion = setRegion<jlong, jlongArray>;
    t.SetFloatArrayRegion = setRegion<jfloat, jfloatArray>;
    t.SetDoubleArrayRegion = setRegion<jdouble, jdoubleArray>;
    t.NewByteArray = newArray<jbyte, jbyteArray>;
    t.NewIntArray = newArray<jint, jintArray>;
    t.NewLongArray = newArray<jlong, jlongArray>;
    t.NewFloatArray = newArray<jfloat, jfloatArray>;
    t.NewDoubleArray = newArray<jdouble, jdoubleArray>;
    t.NewObjectArray = [](JNIEnv*, jsize n, jclass, jobject init) {
        auto* a = make<ObjArr>();
        a->v.assign(size_t(n), obj(init));
        return reinterpret_cast<jobjectArray>(static_cast<Obj*>(a));
    };
    t.GetObjectArrayElement = [](JNIEnv*, jobjectArray a, jsize i) {
        return reinterpret_cast<jobject>(static_cast<ObjArr*>(obj(a))->v[size_t(i)]);
    };
    t.SetObjectArrayElement = [](JNIEnv*, jobjectArray a, jsize i, jobject v) {
        static_cast<ObjArr*>(obj(a))->v[size_t(i)] = obj(v);
    };
    t.NewStringUTF = [](JNIEnv*, const char* s) {
        auto* o = make<Str>();
        o->s = s;
        return reinterpret_cast<jstring>(static_cast<Obj*>(o));
    };
    t.GetStringUTFChars = [](JNIEnv*, jstring s, jboolean* copy) {
        if (copy) *copy = JNI_FALSE;
        return static_cast<Str*>(obj(s))->s.c_str();
    };
    t.ReleaseStringUTFChars = [](JNIEnv*, jstring, const char*) {};
    t.DeleteLocalRef = [](JNIEnv*, jobject) {};
    // Any class will do: the only use is naming RuntimeException and String.
    t.FindClass = [](JNIEnv*, const char*) { return reinterpret_cast<jclass>(1); };
    t.ThrowNew = [](JNIEnv*, jclass, const char* why) {
        throwMessage(why);
        return jint(0);
    };
    return t;
}

const JNINativeInterface_ functions = table();
JNIEnv env{&functions};

std::vector<uint8_t> args, result;

}  // namespace

Obj* keep(std::unique_ptr<Obj> o) {
    objects.push_back(std::move(o));
    return objects.back().get();
}

bool failed() { return threw; }

void throwMessage(const std::string& why) {
    threw = true;
    message = why;
}

}  // namespace pmweb

extern "C" {

/** Room for a call's arguments, n bytes. Good until the next call to this. */
EMSCRIPTEN_KEEPALIVE uint8_t* pm_args(int n) {
    pmweb::args.resize(size_t(n));
    return pmweb::args.data();
}

/**
 * Runs call number fn with the arguments in pm_args. Returns the result: four
 * bytes of length, then 0 and the value, or 1 and the reason it failed.
 */
EMSCRIPTEN_KEEPALIVE uint8_t* pm_call(int fn, int n) {
    using namespace pmweb;
    threw = false;
    Reader in(args.data(), size_t(n));
    Writer out;
    out.bytes.assign(5, 0);
    try {
        dispatch(fn, in, out, &env);
    } catch (const std::exception& e) {
        throwMessage(e.what());
    } catch (...) {
        throwMessage("That couldn't be done");
    }
    if (threw) {
        out.bytes.resize(5);
        out.bytes[4] = 1;
        out.bytes.insert(out.bytes.end(), message.begin(), message.end());
    }
    jint size = jint(out.bytes.size() - 4);
    std::memcpy(out.bytes.data(), &size, 4);
    result = std::move(out.bytes);
    objects.clear();
    return result.data();
}

/** Makes a WebGL 2 context on the canvas named by selector, e.g. "#pm-gl". False if the browser has none. */
EMSCRIPTEN_KEEPALIVE int pm_gl_start(const char* selector) {
    EmscriptenWebGLContextAttributes a;
    emscripten_webgl_init_context_attributes(&a);
    a.majorVersion = 2;
    a.minorVersion = 0;
    a.alpha = false;
    a.depth = true;
    a.antialias = true;
    a.premultipliedAlpha = false;
    EMSCRIPTEN_WEBGL_CONTEXT_HANDLE context = emscripten_webgl_create_context(selector, &a);
    if (context <= 0) return 0;
    return emscripten_webgl_make_context_current(context) == EMSCRIPTEN_RESULT_SUCCESS ? 1 : 0;
}

/** Sizes the canvas's drawing buffer, in device pixels. */
EMSCRIPTEN_KEEPALIVE void pm_gl_resize(const char* selector, int width, int height) {
    emscripten_set_canvas_element_size(selector, width, height);
}

}  // extern "C"
