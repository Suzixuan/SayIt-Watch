package com.sayit.watch.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ComputerAliasPolicyTest {

    @Test
    fun `normalizes whitespace controls and length for the round-screen label`() {
        assertEquals("书房 台式机", ComputerAliasPolicy.normalize("  书房\n\t台式机  "))
        assertEquals("abcdefghijklmnop", ComputerAliasPolicy.normalize("abcdefghijklmnop-more"))
    }

    @Test
    fun `blank aliases restore the fallback`() {
        assertEquals("", ComputerAliasPolicy.normalize(" \n\t "))
    }

    @Test
    fun `preference key keeps endpoints independent`() {
        val desktop = ComputerAliasPolicy.preferenceKey("192.168.12.142", 18099)
        val laptop = ComputerAliasPolicy.preferenceKey("192.168.12.153", 18099)
        assertNotEquals(desktop, laptop)
        assertEquals(desktop, ComputerAliasPolicy.preferenceKey("192.168.12.142", 18099))
    }
}
