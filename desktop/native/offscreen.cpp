// The desktop 3D view's GL: a context with no window, and a framebuffer the
// renderer draws into, read back for the app to show. On Linux it's GLX (or
// EGL where there's no X), OpenGL ES 3 where offered and else OpenGL 3.3; on
// Windows, OpenGL 3.3 through WGL. Both are loaded at run
// time, so the library needs no GL to load. Every call here runs on the app's
// one GL thread (DesktopGl.kt).

#include <jni.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "render/gl.h"

#if defined(_WIN32)
#include <windows.h>
#else
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <dlfcn.h>
#endif

namespace {

std::string problem;
GLuint msaaFbo = 0, msaaColour = 0, msaaDepth = 0;
GLuint readFbo = 0, readColour = 0;
int width = 0, height = 0;
std::vector<uint8_t> rows;

#if defined(_WIN32)

HMODULE opengl32 = nullptr;
using WglGetProcAddress = PROC(WINAPI*)(LPCSTR);
WglGetProcAddress wglGet = nullptr;

void* getGl(const char* name) {
    void* p = reinterpret_cast<void*>(wglGet(name));
    // Some drivers answer 1, 2, 3 or -1 for calls they don't have.
    auto v = reinterpret_cast<intptr_t>(p);
    if (v == 0 || v == 1 || v == 2 || v == 3 || v == -1) p = reinterpret_cast<void*>(GetProcAddress(opengl32, name));
    return p;
}

bool makeContext() {
    opengl32 = LoadLibraryA("opengl32.dll");
    if (!opengl32) return problem = "No OpenGL on this computer", false;
    wglGet = reinterpret_cast<WglGetProcAddress>(GetProcAddress(opengl32, "wglGetProcAddress"));
    auto createContext = reinterpret_cast<HGLRC(WINAPI*)(HDC)>(GetProcAddress(opengl32, "wglCreateContext"));
    auto makeCurrent = reinterpret_cast<BOOL(WINAPI*)(HDC, HGLRC)>(GetProcAddress(opengl32, "wglMakeCurrent"));
    auto deleteContext = reinterpret_cast<BOOL(WINAPI*)(HGLRC)>(GetProcAddress(opengl32, "wglDeleteContext"));

    // A hidden window only to get a device context; drawing goes to a framebuffer.
    HINSTANCE instance = GetModuleHandleA(nullptr);
    WNDCLASSA wc{};
    wc.style = CS_OWNDC;
    wc.lpfnWndProc = DefWindowProcA;
    wc.hInstance = instance;
    wc.lpszClassName = "ParrotMetricGL";
    RegisterClassA(&wc);
    HWND window = CreateWindowA("ParrotMetricGL", "", WS_OVERLAPPEDWINDOW, 0, 0, 16, 16, nullptr, nullptr, instance, nullptr);
    HDC dc = GetDC(window);
    PIXELFORMATDESCRIPTOR pfd{};
    pfd.nSize = sizeof(pfd);
    pfd.nVersion = 1;
    pfd.dwFlags = PFD_DRAW_TO_WINDOW | PFD_SUPPORT_OPENGL | PFD_DOUBLEBUFFER;
    pfd.iPixelType = PFD_TYPE_RGBA;
    pfd.cColorBits = 32;
    pfd.cDepthBits = 24;
    SetPixelFormat(dc, ChoosePixelFormat(dc, &pfd), &pfd);

    // An old-style context first, to find the call that makes a 3.3 core one.
    HGLRC old = createContext(dc);
    if (!old || !makeCurrent(dc, old)) return problem = "OpenGL didn't start", false;
    using CreateWithAttribs = HGLRC(WINAPI*)(HDC, HGLRC, const int*);
    auto create = reinterpret_cast<CreateWithAttribs>(wglGet("wglCreateContextAttribsARB"));
    const int attribs[] = {0x2091, 3, 0x2092, 3, 0x9126, 0x1, 0};  // Version 3.3, core profile.
    HGLRC context = create ? create(dc, nullptr, attribs) : nullptr;
    if (!context) return problem = "This computer's OpenGL is older than 3.3", false;
    makeCurrent(dc, context);
    deleteContext(old);
    pm::desktopGl = true;
    return true;
}

#else

void* libGles = nullptr;
PFNEGLGETPROCADDRESSPROC eglGet = nullptr;

void* getEgl(const char* name) {
    void* p = libGles ? dlsym(libGles, name) : nullptr;
    return p ? p : reinterpret_cast<void*>(eglGet(name));
}

bool makeEglContext() {
    void* libEgl = dlopen("libEGL.so.1", RTLD_NOW | RTLD_LOCAL);
    if (!libEgl) return problem = "No EGL on this computer (libEGL.so.1)", false;
    libGles = dlopen("libGLESv2.so.2", RTLD_NOW | RTLD_LOCAL);
    eglGet = reinterpret_cast<PFNEGLGETPROCADDRESSPROC>(dlsym(libEgl, "eglGetProcAddress"));
#define EGL(name) auto name = reinterpret_cast<decltype(&::name)>(dlsym(libEgl, #name))
    EGL(eglGetDisplay);
    EGL(eglInitialize);
    EGL(eglBindAPI);
    EGL(eglChooseConfig);
    EGL(eglCreateContext);
    EGL(eglCreatePbufferSurface);
    EGL(eglMakeCurrent);
    EGL(eglGetError);
    EGL(eglQueryString);
#undef EGL
    // Why the last EGL call failed, for the message.
    auto code = [&] {
        char s[24];
        snprintf(s, sizeof s, " (EGL 0x%x)", eglGetError ? unsigned(eglGetError()) : 0u);
        return std::string(s);
    };
    if (!eglGet || !eglGetDisplay || !eglInitialize || !eglChooseConfig || !eglCreateContext || !eglMakeCurrent)
        return problem = "This computer's EGL is missing calls", false;

    // The desktop's own display first; without one, Mesa's surfaceless platform.
    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display == EGL_NO_DISPLAY || !eglInitialize(display, nullptr, nullptr)) {
        auto platformDisplay = reinterpret_cast<PFNEGLGETPLATFORMDISPLAYEXTPROC>(eglGet("eglGetPlatformDisplayEXT"));
        display = platformDisplay ? platformDisplay(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, nullptr) : EGL_NO_DISPLAY;
        if (display == EGL_NO_DISPLAY || !eglInitialize(display, nullptr, nullptr)) return problem = "EGL didn't start", false;
    }
    eglBindAPI(EGL_OPENGL_ES_API);
    const EGLint configAttribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_DEPTH_SIZE, 24, EGL_NONE,
    };
    EGLConfig config;
    EGLint count = 0;
    if (!eglChooseConfig(display, configAttribs, &config, 1, &count) || count == 0)
        return problem = "This computer has no OpenGL ES 3" + code(), false;
    const EGLint contextAttribs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttribs);
    if (context == EGL_NO_CONTEXT) return problem = "OpenGL ES 3 didn't start" + code(), false;
    // Current with no surface where EGL allows it, else with a small pbuffer.
    // Drawing goes to a framebuffer either way.
    if (eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, context)) return true;
    const EGLint surfaceAttribs[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE};
    EGLSurface surface = eglCreatePbufferSurface ? eglCreatePbufferSurface(display, config, surfaceAttribs) : EGL_NO_SURFACE;
    if (surface == EGL_NO_SURFACE || !eglMakeCurrent(display, surface, surface, context))
        return problem = "OpenGL ES 3 didn't start" + code() + " " + (eglQueryString ? eglQueryString(display, EGL_VENDOR) : ""), false;
    return true;
}


// GLX, the way Compose itself draws on Linux. Two GL vendors in one process
// (Compose on GLX and this on EGL) can't both be current, so GLX comes first.
// Declared here rather than from GLX's headers, which need X11's.
using XDisplay = void;
using GlxConfig = void*;
using GlxContext = void*;
constexpr int kGlxRenderType = 0x8011, kGlxRgbaBit = 0x1, kGlxDrawableType = 0x8010, kGlxPbufferBit = 0x4;
constexpr int kGlxPbufferWidth = 0x8041, kGlxPbufferHeight = 0x8040;
constexpr int kContextMajor = 0x2091, kContextMinor = 0x2092, kContextProfile = 0x9126;
constexpr int kCoreProfile = 0x1, kEsProfile = 0x4;

using GlxGetProc = void* (*)(const unsigned char*);
GlxGetProc glxGet = nullptr;

void* getGlx(const char* name) { return glxGet(reinterpret_cast<const unsigned char*>(name)); }

int ignoreXError(XDisplay*, void*) { return 0; }

bool makeGlxContext() {
    if (!getenv("DISPLAY")) return false;
    void* libX11 = dlopen("libX11.so.6", RTLD_NOW | RTLD_LOCAL);
    void* libGl = dlopen("libGL.so.1", RTLD_NOW | RTLD_LOCAL);
    if (!libX11 || !libGl) return false;
    auto openDisplay = reinterpret_cast<XDisplay* (*)(const char*)>(dlsym(libX11, "XOpenDisplay"));
    auto setErrorHandler = reinterpret_cast<void* (*)(int (*)(XDisplay*, void*))>(dlsym(libX11, "XSetErrorHandler"));
    auto sync = reinterpret_cast<int (*)(XDisplay*, int)>(dlsym(libX11, "XSync"));
    auto defaultScreen = reinterpret_cast<int (*)(XDisplay*)>(dlsym(libX11, "XDefaultScreen"));
    glxGet = reinterpret_cast<GlxGetProc>(dlsym(libGl, "glXGetProcAddressARB"));
    auto chooseConfig = reinterpret_cast<GlxConfig* (*)(XDisplay*, int, const int*, int*)>(dlsym(libGl, "glXChooseFBConfig"));
    auto createPbuffer = reinterpret_cast<unsigned long (*)(XDisplay*, GlxConfig, const int*)>(dlsym(libGl, "glXCreatePbuffer"));
    auto makeCurrent = reinterpret_cast<int (*)(XDisplay*, unsigned long, unsigned long, GlxContext)>(dlsym(libGl, "glXMakeContextCurrent"));
    if (!openDisplay || !setErrorHandler || !sync || !defaultScreen || !glxGet || !chooseConfig || !createPbuffer || !makeCurrent) return false;
    using CreateWithAttribs = GlxContext (*)(XDisplay*, GlxConfig, GlxContext, int, const int*);
    auto create = reinterpret_cast<CreateWithAttribs>(getGlx("glXCreateContextAttribsARB"));
    XDisplay* display = openDisplay(nullptr);
    if (!create || !display) return false;

    const int configAttribs[] = {kGlxRenderType, kGlxRgbaBit, kGlxDrawableType, kGlxPbufferBit, 0};
    int count = 0;
    GlxConfig* configs = chooseConfig(display, defaultScreen(display), configAttribs, &count);
    if (!configs || count == 0) return false;
    GlxConfig config = configs[0];

    // OpenGL ES 3 where GLX offers it, so the shaders are the same as on
    // Android; else desktop OpenGL 3.3. A refused context is an X error,
    // which would end the program without a handler that ignores it.
    auto old = setErrorHandler(ignoreXError);
    const int es[] = {kContextMajor, 3, kContextMinor, 0, kContextProfile, kEsProfile, 0};
    const int core[] = {kContextMajor, 3, kContextMinor, 3, kContextProfile, kCoreProfile, 0};
    GlxContext context = create(display, config, nullptr, 1, es);
    sync(display, 0);
    if (!context) {
        context = create(display, config, nullptr, 1, core);
        sync(display, 0);
        pm::desktopGl = context != nullptr;
    }
    setErrorHandler(reinterpret_cast<int (*)(XDisplay*, void*)>(old));
    if (!context) return false;
    // A small pbuffer to be current with; drawing goes to a framebuffer.
    const int pbufferAttribs[] = {kGlxPbufferWidth, 16, kGlxPbufferHeight, 16, 0};
    unsigned long pbuffer = createPbuffer(display, config, pbufferAttribs);
    if (!makeCurrent(display, pbuffer, pbuffer, context)) {
        pm::desktopGl = false;
        return false;
    }
    return true;
}

bool usingGlx = false;

bool makeContext() {
    usingGlx = makeGlxContext();
    return usingGlx || makeEglContext();
}

void* getGl(const char* name) { return usingGlx ? getGlx(name) : getEgl(name); }

#endif

void releaseTargets() {
    if (msaaFbo) {
        glDeleteFramebuffers(1, &msaaFbo);
        glDeleteRenderbuffers(1, &msaaColour);
        glDeleteRenderbuffers(1, &msaaDepth);
        glDeleteFramebuffers(1, &readFbo);
        glDeleteRenderbuffers(1, &readColour);
        msaaFbo = msaaColour = msaaDepth = readFbo = readColour = 0;
    }
}

}  // namespace

extern "C" {

/** Makes the GL context on this thread. Null if it worked, else why not. */
JNIEXPORT jstring JNICALL Java_com_rm_parrotmetric_DesktopGl_start(JNIEnv* env, jobject) {
    if (!makeContext() || !pm::loadGl(getGl)) {
        if (problem.empty()) problem = "This computer's OpenGL is missing calls";
        return env->NewStringUTF(problem.c_str());
    }
    return nullptr;
}

/** Sizes the framebuffer the renderer draws into: four samples, resolved into a plain one to read. */
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_DesktopGl_resize(JNIEnv*, jobject, jint w, jint h) {
    releaseTargets();
    width = w < 1 ? 1 : w;
    height = h < 1 ? 1 : h;
    glGenFramebuffers(1, &msaaFbo);
    glBindFramebuffer(GL_FRAMEBUFFER, msaaFbo);
    glGenRenderbuffers(1, &msaaColour);
    glBindRenderbuffer(GL_RENDERBUFFER, msaaColour);
    glRenderbufferStorageMultisample(GL_RENDERBUFFER, 4, GL_RGBA8, width, height);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, msaaColour);
    glGenRenderbuffers(1, &msaaDepth);
    glBindRenderbuffer(GL_RENDERBUFFER, msaaDepth);
    glRenderbufferStorageMultisample(GL_RENDERBUFFER, 4, GL_DEPTH_COMPONENT24, width, height);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, msaaDepth);
    glGenFramebuffers(1, &readFbo);
    glBindFramebuffer(GL_FRAMEBUFFER, readFbo);
    glGenRenderbuffers(1, &readColour);
    glBindRenderbuffer(GL_RENDERBUFFER, readColour);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, width, height);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, readColour);
    pm::targetFramebuffer = msaaFbo;
}

/** Copies the last frame drawn into pixels, RGBA top row first, width * height * 4 bytes. */
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_DesktopGl_read(JNIEnv* env, jobject, jbyteArray pixels) {
    glBindFramebuffer(GL_READ_FRAMEBUFFER, msaaFbo);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, readFbo);
    glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST);
    glBindFramebuffer(GL_FRAMEBUFFER, readFbo);
    size_t row = size_t(width) * 4;
    rows.resize(row * size_t(height));
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, rows.data());
    glBindFramebuffer(GL_FRAMEBUFFER, msaaFbo);
    jsize size = env->GetArrayLength(pixels);
    if (size_t(size) < rows.size()) return;
    // GL's rows run bottom up.
    for (int y = 0; y < height; ++y)
        env->SetByteArrayRegion(pixels, jsize(size_t(y) * row), jsize(row),
                                reinterpret_cast<const jbyte*>(rows.data() + size_t(height - 1 - y) * row));
}

}  // extern "C"
