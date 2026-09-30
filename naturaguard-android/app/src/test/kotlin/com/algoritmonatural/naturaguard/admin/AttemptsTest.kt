package com.algoritmonatural.naturaguard.admin

import org.junit.Assert.assertEquals
import org.junit.Test

class AttemptsTest {
    private val min = 60_000L
    private val now = 100 * min

    @Test
    fun soContaOsUltimos10Minutos() {
        val text = listOf(now - 11 * min, now - 9 * min, now - 1 * min).joinToString(",")
        assertEquals(listOf(now - 9 * min, now - 1 * min), Attempts.recent(text, now))
    }

    @Test
    fun relogioParaTrasOuLixoIgnorados() {
        val text = "${now + 5 * min},abc,,${now - 2 * min}"
        assertEquals(listOf(now - 2 * min), Attempts.recent(text, now))
    }

    @Test
    fun vazioOuNulo() {
        assertEquals(emptyList<Long>(), Attempts.recent(null, now))
        assertEquals(emptyList<Long>(), Attempts.recent("", now))
    }
}
