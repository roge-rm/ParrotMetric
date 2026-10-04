# Licences

ParrotMetric is under the GPL 3 (see LICENSE). It is built with these libraries, each under its own licence:

| Library | Licence | File |
|---|---|---|
| Open CASCADE Technology | LGPL 2.1 with the Open CASCADE exception | occt-LGPL-2.1.txt, occt-LGPL-exception.txt |
| Manifold | Apache 2.0 | manifold-Apache-2.0.txt |
| Eigen | MPL 2.0 | eigen-MPL-2.0.txt |
| zlib (desktop and browser builds) | zlib | zlib.txt |
| stb_truetype, for text | MIT (or public domain) | stb-MIT.txt |
| Noto Sans font, for text | SIL Open Font License 1.1 | noto-OFL-1.1.txt |

Their sources are in third_party/, as git submodules except stb_truetype (third_party/stb) and the Noto Sans fonts (third_party/fonts), which are kept in the repository. The OpenGL ES and EGL headers in third_party/khronos are the Khronos Group's, under the MIT and Apache 2.0 licences given in each file.
