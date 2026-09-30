package com.algoritmonatural.naturaguard.scan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallTrustTest {
    private val play = "com.android.vending"

    @Test
    fun lojaVerdadeiraEConfiavel() {
        assertTrue(InstallTrust.isTrusted(34, play, play))
    }

    @Test
    fun adbComInstaladorFalsificadoNaoEConfiavel() {
        // adb install -i com.android.vending: o sistema regista a shell como quem iniciou.
        assertFalse(InstallTrust.isTrusted(34, play, "com.android.shell"))
        assertFalse(InstallTrust.isTrusted(34, play, null))
    }

    @Test
    fun instaladorDesconhecidoOuNuloNaoEConfiavel() {
        assertFalse(InstallTrust.isTrusted(34, null, null))
        assertFalse(InstallTrust.isTrusted(34, "com.google.android.packageinstaller", play))
        assertFalse(InstallTrust.isTrusted(28, null, null))
    }

    @Test
    fun antesDoAndroid11SoHaInstalador() {
        assertTrue(InstallTrust.isTrusted(29, play, null))
    }
}
