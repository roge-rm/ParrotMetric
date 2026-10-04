package com.rm.parrotmetric

/** The desktop view's offscreen GL (desktop/native/offscreen.cpp). Every call is on the GL thread. */
object DesktopGl {
    init {
        // The same library as Core's.
        Core.hashCode()
    }

    /** Makes the GL context on this thread. Null if it worked, else why not. */
    external fun start(): String?
    external fun resize(width: Int, height: Int)
    /** The last frame drawn, as RGBA rows from the top, width * height * 4 bytes. */
    external fun read(pixels: ByteArray)
}
