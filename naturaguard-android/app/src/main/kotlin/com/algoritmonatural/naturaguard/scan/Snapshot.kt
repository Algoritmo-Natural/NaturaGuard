package com.algoritmonatural.naturaguard.scan

import com.algoritmonatural.naturaguard.shared.Severity

/** Fotografia do que interessa vigiar neste telemovel. */
data class Snapshot(
    val accessibility: Set<String> = emptySet(),
    val admins: Set<String> = emptySet(),
    val listeners: Set<String> = emptySet(),
    val sideloaded: Set<String> = emptySet(),
    val adbEnabled: Boolean = false,
    val devOptions: Boolean = false,
    val deviceSecure: Boolean = true,
    val patchOld: Boolean = false,
    val rootSuspected: Boolean = false,
)

data class Finding(val type: String, val severity: Severity, val message: String)

/**
 * Compara a fotografia nova com a anterior. Na primeira vez a ameaca pode ja
 * existir antes da instalacao, por isso mostra o inventario inicial como aviso
 * (para o dono confirmar) e reporta estados maus; depois so avisa de mudancas,
 * para nao repetir o mesmo alerta de 15 em 15 minutos.
 */
object Diff {
    fun compare(old: Snapshot?, now: Snapshot): List<Finding> {
        val out = mutableListOf<Finding>()
        val base = old ?: Snapshot()

        if (old == null) {
            val parts = listOf(
                "acessibilidade" to now.accessibility,
                "administradores" to now.admins,
                "leem notificações" to now.listeners,
                "fora da loja" to now.sideloaded,
            ).filter { it.second.isNotEmpty() }
                .joinToString("; ") { (label, items) -> "$label: ${items.sorted().joinToString(", ")}" }
            if (parts.isNotEmpty()) {
                out += Finding(
                    "inventario_inicial", Severity.WARNING,
                    "Confirme que reconhece estas apps com acessos especiais (já existiam ao instalar): $parts.",
                )
            }
        } else {
            (now.accessibility - old.accessibility).forEach {
                out += Finding(
                    "acessibilidade_nova", Severity.CRITICAL,
                    "Servico de acessibilidade ativado: $it. Pode ler o ecra e simular toques.",
                )
            }
            (now.admins - old.admins).forEach {
                out += Finding(
                    "admin_novo", Severity.CRITICAL,
                    "Nova app com privilegios de administrador do dispositivo: $it.",
                )
            }
            (now.listeners - old.listeners).forEach {
                out += Finding(
                    "leitor_notificacoes_novo", Severity.WARNING,
                    "App com acesso a ler as suas notificacoes: $it.",
                )
            }
            (now.sideloaded - old.sideloaded).forEach {
                out += Finding(
                    "app_fora_da_loja", Severity.WARNING,
                    "App instalada fora da loja oficial: $it.",
                )
            }
        }

        // Estados maus: reporta quando passam a maus (ou ja o sao na 1.a vez).
        if (!now.deviceSecure && (old == null || base.deviceSecure)) {
            out += Finding(
                "sem_bloqueio_ecra", Severity.CRITICAL,
                "O telemovel nao tem bloqueio de ecra (PIN, padrao ou biometria).",
            )
        }
        if (now.rootSuspected && !base.rootSuspected) {
            out += Finding(
                "root_suspeito", Severity.CRITICAL,
                "Indicios de root neste telemovel (heuristica, nao e prova).",
            )
        }
        if (now.adbEnabled && !base.adbEnabled) {
            out += Finding(
                "adb_ativo", Severity.WARNING,
                "Depuracao USB (ADB) ativada: permite controlar o telemovel por cabo.",
            )
        }
        if (now.devOptions && !base.devOptions) {
            out += Finding("opcoes_programador", Severity.INFO, "Opcoes de programador ativadas.")
        }
        if (now.patchOld && !base.patchOld) {
            out += Finding(
                "patch_antigo", Severity.WARNING,
                "As atualizacoes de seguranca do Android tem mais de 120 dias.",
            )
        }

        if (old == null) {
            out += Finding(
                "referencia_criada", Severity.INFO,
                "Referencia inicial criada. A partir de agora so aviso de mudancas.",
            )
        }
        return out
    }
}
