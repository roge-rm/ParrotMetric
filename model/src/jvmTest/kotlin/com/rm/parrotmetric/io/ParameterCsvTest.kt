package com.rm.parrotmetric.io

import com.rm.parrotmetric.design.Parameter
import kotlin.test.Test
import kotlin.test.assertEquals

class ParameterCsvTest {
    @Test
    fun parametersGoOutWithTheirValues() {
        val list = listOf(Parameter("wall", "2"), Parameter("width", "wall*10+0.5"), Parameter("odd", "2\""), Parameter("bad", "nope*2"))
        assertEquals(
            "name,expression,value\nwall,2,2\nwidth,wall*10+0.5,20.5\nodd,\"2\"\"\",50.8\nbad,nope*2,\n",
            ParameterCsv.write(list),
        )
    }

    @Test
    fun readingSetsMatchingNamesAndAddsNewOnes() {
        val list = listOf(Parameter("wall", "2"), Parameter("width", "40"))
        val text = "Name,Expression,Value\r\nwidth,wall*25,50\r\nheight,,12.5\r\n\"lid\",\"wall, 1\",\r\n2bad,3,3\r\n,,\r\nwall,2,2"
        val r = ParameterCsv.read(text, list)
        assertEquals(listOf(Parameter("wall", "2"), Parameter("width", "wall*25"), Parameter("height", "12.5"), Parameter("lid", "wall, 1")), r.parameters)
        assertEquals(1, r.changed)
        assertEquals(2, r.added)
        assertEquals(1, r.skipped)
    }

    @Test
    fun whatIsWrittenReadsBackTheSame() {
        val list = listOf(Parameter("a", "1"), Parameter("b", "a*2\""), Parameter("c", "a, 3"))
        assertEquals(list, ParameterCsv.read(ParameterCsv.write(list), emptyList()).parameters)
    }

    @Test
    fun semicolonsAndNoHeadingAlsoRead() {
        val r = ParameterCsv.read("﻿depth;30;30\nwall;;1,5\n", emptyList())
        assertEquals(listOf(Parameter("depth", "30"), Parameter("wall", "1,5")), r.parameters)
    }
}
