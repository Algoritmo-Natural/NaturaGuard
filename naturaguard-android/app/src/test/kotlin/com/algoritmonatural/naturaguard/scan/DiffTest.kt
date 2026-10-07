package com.algoritmonatural.naturaguard.scan

import com.algoritmonatural.naturaguard.shared.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffTest {
    private val clean = Snapshot()

    private fun types(list: List<Finding>) = list.map { it.type }

    @Test
    fun primeiraVezTelemovelLimpoSoCriaReferencia() {
        assertEquals(listOf("referencia_criada"), types(Diff.compare(null, clean)))
    }

    @Test
    fun primeiraVezMostraAcessosQueJaExistiam() {
        val out = Diff.compare(null, clean.copy(accessibility = setOf("spy/.Svc"), sideloaded = setOf("a.b")))
        val inv = out.first { it.type == "inventario_inicial" }
        assertEquals(Severity.WARNING, inv.severity)
        assertTrue("spy/.Svc" in inv.message && "a.b" in inv.message)
        assertTrue("referencia_criada" in types(out))
    }

    @Test
    fun primeiraVezEstadosMausSaoReportados() {
        val out = Diff.compare(null, clean.copy(adbEnabled = true, rootSuspected = true, patchOld = true))
        assertTrue(types(out).containsAll(listOf("adb_ativo", "root_suspeito", "patch_antigo")))
    }

    @Test
    fun primeiraVezSemBloqueioDeEcraECritico() {
        val out = Diff.compare(null, clean.copy(deviceSecure = false))
        assertTrue("sem_bloqueio_ecra" in types(out))
        assertEquals(Severity.CRITICAL, out.first { it.type == "sem_bloqueio_ecra" }.severity)
    }

    @Test
    fun semMudancasNaoAlerta() {
        val s = clean.copy(adbEnabled = true, sideloaded = setOf("a.b"))
        assertTrue(Diff.compare(s, s).isEmpty())
    }

    @Test
    fun acessibilidadeNovaECritica() {
        val out = Diff.compare(clean, clean.copy(accessibility = setOf("evil/.Svc")))
        assertEquals(listOf("acessibilidade_nova"), types(out))
        assertEquals(Severity.CRITICAL, out[0].severity)
    }

    @Test
    fun adminNovoECritico() {
        val out = Diff.compare(clean, clean.copy(admins = setOf("evil/.Admin")))
        assertEquals(listOf("admin_novo"), types(out))
        assertEquals(Severity.CRITICAL, out[0].severity)
    }

    @Test
    fun leitorDeNotificacoesEAppForaDaLojaSaoAviso() {
        val out = Diff.compare(clean, clean.copy(listeners = setOf("l.p"), sideloaded = setOf("s.p")))
        assertEquals(setOf("leitor_notificacoes_novo", "app_fora_da_loja"), types(out).toSet())
        assertTrue(out.all { it.severity == Severity.WARNING })
    }

    @Test
    fun soReportaQuandoEstadoMauMuda() {
        val bad = clean.copy(adbEnabled = true, rootSuspected = true, patchOld = true)
        val first = Diff.compare(clean, bad)
        assertEquals(setOf("adb_ativo", "root_suspeito", "patch_antigo"), types(first).toSet())
        assertTrue(Diff.compare(bad, bad).isEmpty())
    }

    @Test
    fun coisaRemovidaNaoAlerta() {
        val old = clean.copy(accessibility = setOf("a/b"), admins = setOf("c/d"))
        assertTrue(Diff.compare(old, clean).isEmpty())
    }

    @Test
    fun certificadoNovoDoUtilizadorECritico() {
        val out = Diff.compare(clean, clean.copy(userCerts = setOf("CN=Proxy (user:abc.0)")))
        assertEquals(listOf("certificado_utilizador_novo"), types(out))
        assertEquals(Severity.CRITICAL, out[0].severity)
    }

    @Test
    fun primeiraVezMostraCertificadosQueJaExistiam() {
        val out = Diff.compare(null, clean.copy(userCerts = setOf("CN=Empresa (user:1.0)")))
        assertTrue(out.first { it.type == "inventario_inicial" }.message.contains("CN=Empresa"))
    }
}
