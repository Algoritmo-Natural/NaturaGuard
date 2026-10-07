package com.algoritmonatural.naturaguard.wireguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelConfigCheckTest {

    private val full = """
        [Interface]
        PrivateKey = aaaa
        Address = 10.0.0.2/32
        DNS = 1.1.1.1

        [Peer]
        PublicKey = bbbb
        Endpoint = vpn.exemplo.pt:51820
        AllowedIPs = 0.0.0.0/0, ::/0
    """.trimIndent()

    @Test
    fun configCompletaPassaSemAvisos() {
        val r = TunnelConfigCheck.check(full)
        assertTrue(r.ok)
        assertTrue(r.warnings.isEmpty())
    }

    @Test
    fun faltaChavePrivadaEServidor() {
        val r = TunnelConfigCheck.check("[Interface]\nAddress = 10.0.0.2/32\n")
        assertFalse(r.ok)
        assertTrue(r.errors.any { "PrivateKey" in it })
        assertTrue(r.errors.any { "[Peer]" in it })
    }

    @Test
    fun avisaQuandoNaoProtegeTudo() {
        val r = TunnelConfigCheck.check(full.replace("0.0.0.0/0, ::/0", "10.0.0.0/24"))
        assertTrue(r.ok)
        assertTrue(r.warnings.any { "0.0.0.0/0" in it })
    }

    @Test
    fun avisaSemDns() {
        val r = TunnelConfigCheck.check(full.lines().filterNot { it.startsWith("DNS") }.joinToString("\n"))
        assertTrue(r.ok)
        assertTrue(r.warnings.any { "DNS" in it })
    }

    @Test
    fun ignoraComentariosEMaiusculas() {
        val r = TunnelConfigCheck.check("# comentario\n" + full.replace("[Peer]", "[PEER]  # servidor"))
        assertTrue(r.ok)
    }

    @Test
    fun avisaQuandoIpv6FicaForaDoTunel() {
        val r = TunnelConfigCheck.check(full.replace("0.0.0.0/0, ::/0", "0.0.0.0/0"))
        assertTrue(r.ok)
        assertTrue(r.warnings.any { "::/0" in it })
    }

    @Test
    fun endpointSemPortaEErro() {
        val r = TunnelConfigCheck.check(full.replace("vpn.exemplo.pt:51820", "vpn.exemplo.pt"))
        assertFalse(r.ok)
        assertTrue(r.errors.any { "porta" in it })
    }

    @Test
    fun endpointIpv6ComPortaPassa() {
        assertTrue(TunnelConfigCheck.check(full.replace("vpn.exemplo.pt:51820", "[2001:db8::1]:51820")).ok)
        assertFalse(TunnelConfigCheck.check(full.replace("vpn.exemplo.pt:51820", "[2001:db8::1]")).ok)
    }

    @Test
    fun textoVazioDaErros() {
        assertEquals(false, TunnelConfigCheck.check("").ok)
    }
}
