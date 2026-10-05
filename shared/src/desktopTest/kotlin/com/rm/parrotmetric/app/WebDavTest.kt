package com.rm.parrotmetric.app

import kotlin.test.Test
import kotlin.test.assertEquals

class WebDavTest {
    @Test
    fun readsDates() {
        assertEquals(0L, httpDate("Thu, 01 Jan 1970 00:00:00 GMT"))
        assertEquals(1791124080000L, httpDate("Sun, 04 Oct 2026 14:28:00 GMT"))
    }

    @Test
    fun readsPropfind() {
        val xml = """<?xml version="1.0"?>
<D:multistatus xmlns:D="DAV:"><D:response><D:href>/dav/</D:href><D:propstat><D:prop><D:getlastmodified>Sun, 04 Oct 2026 14:28:00 GMT</D:getlastmodified></D:prop></D:propstat></D:response>
<D:response><D:href>/dav/Project%20box.pmet</D:href><D:propstat><D:prop><D:getlastmodified>Sun, 04 Oct 2026 14:28:00 GMT</D:getlastmodified></D:prop></D:propstat></D:response></D:multistatus>"""
        val e = davEntries(xml)
        assertEquals(2, e.size)
        assertEquals("/dav/Project%20box.pmet", e[1].first)
        assertEquals("Project box.pmet", decodePath(e[1].first.substringAfterLast('/')))
        assertEquals(1791124080000L, e[1].second)
    }

    @Test
    fun encodesNames() {
        assertEquals("Project%20box%20%C3%A9.pmet", encodePath("Project box é.pmet"))
        assertEquals("Project box é.pmet", decodePath("Project%20box%20%C3%A9.pmet"))
    }
}
