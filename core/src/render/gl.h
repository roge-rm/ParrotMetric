#pragma once

// The GL calls the core makes. Android and the browser link OpenGL ES 3
// (WebGL 2) directly. Desktop builds define PM_GL_LOADED and load the same
// calls at run time from whatever GL the machine has (see gl_loader.cpp), so
// nothing links against a GL library.

#include <cstdint>

#if defined(PM_GL_LOADED)
#define GL_GLES_PROTOTYPES 0
#include <GLES3/gl3.h>

// Every call used, as X(pointer type, name).
#define PM_GL_CALLS(X) \
    X(PFNGLACTIVETEXTUREPROC, glActiveTexture) \
    X(PFNGLATTACHSHADERPROC, glAttachShader) \
    X(PFNGLBINDBUFFERPROC, glBindBuffer) \
    X(PFNGLBINDFRAMEBUFFERPROC, glBindFramebuffer) \
    X(PFNGLBINDRENDERBUFFERPROC, glBindRenderbuffer) \
    X(PFNGLBINDTEXTUREPROC, glBindTexture) \
    X(PFNGLBINDVERTEXARRAYPROC, glBindVertexArray) \
    X(PFNGLBLENDFUNCPROC, glBlendFunc) \
    X(PFNGLBUFFERDATAPROC, glBufferData) \
    X(PFNGLBUFFERSUBDATAPROC, glBufferSubData) \
    X(PFNGLCLEARPROC, glClear) \
    X(PFNGLCLEARCOLORPROC, glClearColor) \
    X(PFNGLCOMPILESHADERPROC, glCompileShader) \
    X(PFNGLCREATEPROGRAMPROC, glCreateProgram) \
    X(PFNGLCREATESHADERPROC, glCreateShader) \
    X(PFNGLDELETEBUFFERSPROC, glDeleteBuffers) \
    X(PFNGLDELETEFRAMEBUFFERSPROC, glDeleteFramebuffers) \
    X(PFNGLDELETERENDERBUFFERSPROC, glDeleteRenderbuffers) \
    X(PFNGLDELETESHADERPROC, glDeleteShader) \
    X(PFNGLDELETETEXTURESPROC, glDeleteTextures) \
    X(PFNGLDELETEVERTEXARRAYSPROC, glDeleteVertexArrays) \
    X(PFNGLDEPTHMASKPROC, glDepthMask) \
    X(PFNGLDISABLEPROC, glDisable) \
    X(PFNGLDRAWELEMENTSPROC, glDrawElements) \
    X(PFNGLENABLEPROC, glEnable) \
    X(PFNGLENABLEVERTEXATTRIBARRAYPROC, glEnableVertexAttribArray) \
    X(PFNGLFRAMEBUFFERRENDERBUFFERPROC, glFramebufferRenderbuffer) \
    X(PFNGLFRAMEBUFFERTEXTURE2DPROC, glFramebufferTexture2D) \
    X(PFNGLGENBUFFERSPROC, glGenBuffers) \
    X(PFNGLGENFRAMEBUFFERSPROC, glGenFramebuffers) \
    X(PFNGLGENRENDERBUFFERSPROC, glGenRenderbuffers) \
    X(PFNGLGENTEXTURESPROC, glGenTextures) \
    X(PFNGLGENVERTEXARRAYSPROC, glGenVertexArrays) \
    X(PFNGLGETUNIFORMLOCATIONPROC, glGetUniformLocation) \
    X(PFNGLLINKPROGRAMPROC, glLinkProgram) \
    X(PFNGLPIXELSTOREIPROC, glPixelStorei) \
    X(PFNGLPOLYGONOFFSETPROC, glPolygonOffset) \
    X(PFNGLREADPIXELSPROC, glReadPixels) \
    X(PFNGLRENDERBUFFERSTORAGEPROC, glRenderbufferStorage) \
    X(PFNGLSHADERSOURCEPROC, glShaderSource) \
    X(PFNGLTEXIMAGE2DPROC, glTexImage2D) \
    X(PFNGLTEXPARAMETERIPROC, glTexParameteri) \
    X(PFNGLTEXSUBIMAGE2DPROC, glTexSubImage2D) \
    X(PFNGLUNIFORM1FPROC, glUniform1f) \
    X(PFNGLUNIFORM1IPROC, glUniform1i) \
    X(PFNGLUNIFORM1UIPROC, glUniform1ui) \
    X(PFNGLUNIFORM2FPROC, glUniform2f) \
    X(PFNGLUNIFORM4FVPROC, glUniform4fv) \
    X(PFNGLUNIFORMMATRIX3FVPROC, glUniformMatrix3fv) \
    X(PFNGLUNIFORMMATRIX4FVPROC, glUniformMatrix4fv) \
    X(PFNGLUSEPROGRAMPROC, glUseProgram) \
    X(PFNGLVERTEXATTRIBIPOINTERPROC, glVertexAttribIPointer) \
    X(PFNGLVERTEXATTRIBPOINTERPROC, glVertexAttribPointer) \
    X(PFNGLVIEWPORTPROC, glViewport) \
    X(PFNGLBLITFRAMEBUFFERPROC, glBlitFramebuffer) \
    X(PFNGLRENDERBUFFERSTORAGEMULTISAMPLEPROC, glRenderbufferStorageMultisample) \
    X(PFNGLCHECKFRAMEBUFFERSTATUSPROC, glCheckFramebufferStatus) \
    X(PFNGLGETSTRINGPROC, glGetString) \
    X(PFNGLFINISHPROC, glFinish)

#define PM_GL_DECLARE(type, name) extern type pm_##name;
PM_GL_CALLS(PM_GL_DECLARE)
#undef PM_GL_DECLARE

#define glActiveTexture pm_glActiveTexture
#define glAttachShader pm_glAttachShader
#define glBindBuffer pm_glBindBuffer
#define glBindFramebuffer pm_glBindFramebuffer
#define glBindRenderbuffer pm_glBindRenderbuffer
#define glBindTexture pm_glBindTexture
#define glBindVertexArray pm_glBindVertexArray
#define glBlendFunc pm_glBlendFunc
#define glBufferData pm_glBufferData
#define glBufferSubData pm_glBufferSubData
#define glClear pm_glClear
#define glClearColor pm_glClearColor
#define glCompileShader pm_glCompileShader
#define glCreateProgram pm_glCreateProgram
#define glCreateShader pm_glCreateShader
#define glDeleteBuffers pm_glDeleteBuffers
#define glDeleteFramebuffers pm_glDeleteFramebuffers
#define glDeleteRenderbuffers pm_glDeleteRenderbuffers
#define glDeleteShader pm_glDeleteShader
#define glDeleteTextures pm_glDeleteTextures
#define glDeleteVertexArrays pm_glDeleteVertexArrays
#define glDepthMask pm_glDepthMask
#define glDisable pm_glDisable
#define glDrawElements pm_glDrawElements
#define glEnable pm_glEnable
#define glEnableVertexAttribArray pm_glEnableVertexAttribArray
#define glFramebufferRenderbuffer pm_glFramebufferRenderbuffer
#define glFramebufferTexture2D pm_glFramebufferTexture2D
#define glGenBuffers pm_glGenBuffers
#define glGenFramebuffers pm_glGenFramebuffers
#define glGenRenderbuffers pm_glGenRenderbuffers
#define glGenTextures pm_glGenTextures
#define glGenVertexArrays pm_glGenVertexArrays
#define glGetUniformLocation pm_glGetUniformLocation
#define glLinkProgram pm_glLinkProgram
#define glPixelStorei pm_glPixelStorei
#define glPolygonOffset pm_glPolygonOffset
#define glReadPixels pm_glReadPixels
#define glRenderbufferStorage pm_glRenderbufferStorage
#define glShaderSource pm_glShaderSource
#define glTexImage2D pm_glTexImage2D
#define glTexParameteri pm_glTexParameteri
#define glTexSubImage2D pm_glTexSubImage2D
#define glUniform1f pm_glUniform1f
#define glUniform1i pm_glUniform1i
#define glUniform1ui pm_glUniform1ui
#define glUniform2f pm_glUniform2f
#define glUniform4fv pm_glUniform4fv
#define glUniformMatrix3fv pm_glUniformMatrix3fv
#define glUniformMatrix4fv pm_glUniformMatrix4fv
#define glUseProgram pm_glUseProgram
#define glVertexAttribIPointer pm_glVertexAttribIPointer
#define glVertexAttribPointer pm_glVertexAttribPointer
#define glViewport pm_glViewport
#define glBlitFramebuffer pm_glBlitFramebuffer
#define glRenderbufferStorageMultisample pm_glRenderbufferStorageMultisample
#define glCheckFramebufferStatus pm_glCheckFramebufferStatus
#define glGetString pm_glGetString
#define glFinish pm_glFinish

namespace pm {
/** Loads every call with get, on a thread whose GL context is current. False if one is missing. */
bool loadGl(void* (*get)(const char* name));
}
#else
#include <GLES3/gl3.h>
#endif

namespace pm {
/** The framebuffer frames are drawn into: 0, the window's own, unless the platform draws offscreen. */
extern uint32_t targetFramebuffer;
/** True for desktop OpenGL 3.3, whose shaders say so in place of "#version 300 es". */
extern bool desktopGl;
}
