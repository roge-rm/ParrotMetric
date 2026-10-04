#include "render/gl.h"

#if defined(PM_GL_LOADED)

#define PM_GL_DEFINE(type, name) type pm_##name = nullptr;
PM_GL_CALLS(PM_GL_DEFINE)
#undef PM_GL_DEFINE

namespace pm {

bool loadGl(void* (*get)(const char* name)) {
    bool all = true;
#define PM_GL_LOAD(type, name) \
    pm_##name = reinterpret_cast<type>(get(#name)); \
    if (!pm_##name) all = false;
    PM_GL_CALLS(PM_GL_LOAD)
#undef PM_GL_LOAD
    return all;
}

}  // namespace pm

#endif
