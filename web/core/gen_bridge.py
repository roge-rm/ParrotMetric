#!/usr/bin/env python3
"""Writes the browser's two halves of every NativeCore call from its signatures:
web/core/dispatch.cpp, which decodes a call and runs jni.cpp's function for it,
and WebCore.kt, which encodes the call on the Kotlin side. Run it after
changing NativeCore.kt:  web/core/gen_bridge.py"""
import os
import re

root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
src = open(os.path.join(root, "shared/src/commonMain/kotlin/com/rm/parrotmetric/app/NativeCore.kt")).read()
body = src[src.index("interface NativeCore {"):src.index("\n}\n")]
funs = re.findall(r"^\s+fun (\w+)\((.*?)\)(?::\s*([\w<>?]+))?\s*$", body, re.M)

# Kotlin type: (JNI type, reader on the C++ side, writer on the Kotlin side, result reader in Kotlin)
types = {
    "Int": ("jint", "i32", "int", "int()"),
    "Long": ("jlong", "i64", "long", "long()"),
    "Float": ("jfloat", "f32", "float", "float()"),
    "Double": ("jdouble", "f64", "double", "double()"),
    "Boolean": ("jboolean", "boolean", "boolean", "boolean()"),
    "String": ("jstring", "string", "string", "string()"),
    "IntArray": ("jintArray", "ints", "ints", "ints()"),
    "LongArray": ("jlongArray", "longs", "longs", "longs()"),
    "FloatArray": ("jfloatArray", "floats", "floats", "floats()"),
    "DoubleArray": ("jdoubleArray", "doubles", "doubles", "doubles()"),
    "ByteArray": ("jbyteArray", "bytes", "bytes", "bytes()"),
    "Array<String>": ("jobjectArray", "strings", "strings", "strings()"),
}

cpp_decl, cpp_cases, kt = [], [], []
for n, (name, params, ret) in enumerate(funs):
    args = [p.strip() for p in params.split(",") if p.strip()]
    parsed = [(a.split(":")[0].strip(), a.split(":")[1].strip()) for a in args]
    nullable = ret.endswith("?")
    base = ret.rstrip("?") if ret else None
    jret = types[base][0] if base else "void"
    jparams = "".join(", " + types[t][0] for _, t in parsed)
    cpp_decl.append(f"JNIEXPORT {jret} JNICALL Java_com_rm_parrotmetric_Core_{name}(JNIEnv*, jobject{jparams});")
    reads = "".join(f"            auto a{i} = in.{types[t][1]}();\n" for i, (_, t) in enumerate(parsed))
    call = f"Java_com_rm_parrotmetric_Core_{name}(env, nullptr{''.join(f', a{i}' for i in range(len(parsed)))})"
    if base:
        run = f"            auto r = {call};\n            if (!failed()) out.put(r);\n"
    else:
        run = f"            {call};\n"
    cpp_cases.append(f"        case {n}: {{  // {name}\n{reads}{run}            break;\n        }}")
    writes = "".join(f"        args.{types[t][2]}({pn})\n" for pn, t in parsed)
    sig = ", ".join(f"{pn}: {t}" for pn, t in parsed)
    if base:
        get = "call(%d, args).%s" % (n, types[base][3])
        if not nullable and base not in ("Int", "Long", "Float", "Double", "Boolean"):
            get += "!!"
        kt.append(f"    override fun {name}({sig}): {ret} {{\n        val args = Args()\n{writes}        return {get}\n    }}\n")
    else:
        kt.append(f"    override fun {name}({sig}) {{\n        val args = Args()\n{writes}        call({n}, args)\n    }}\n")

open(os.path.join(root, "web/core/dispatch.cpp"), "w").write(
    "// Written by gen_bridge.py from NativeCore.kt; run that instead of editing this.\n"
    "// Runs jni.cpp's function for each call, numbered in NativeCore's order.\n\n"
    "#include \"bridge.h\"\n\nextern \"C\" {\n" + "\n".join(cpp_decl) + "\n}\n\n"
    "namespace pmweb {\n\nvoid dispatch(int call, Reader& in, Writer& out, JNIEnv* env) {\n    switch (call) {\n"
    + "\n".join(cpp_cases) + "\n        default:\n            throwMessage(\"No such call\");\n    }\n}\n\n}  // namespace pmweb\n")
open(os.path.join(root, "web/app/src/wasmJsMain/kotlin/com/rm/parrotmetric/web/WebCore.kt"), "w").write(
    "// Written by web/core/gen_bridge.py from NativeCore.kt; run that instead of editing this.\n"
    "package com.rm.parrotmetric.web\n\nimport com.rm.parrotmetric.app.NativeCore\n\n"
    "/** The core in WebAssembly (web/core), each call numbered in NativeCore's order. */\n"
    "object WebCore : NativeCore {\n" + "\n".join(kt) + "}\n")
print(len(funs), "calls")
