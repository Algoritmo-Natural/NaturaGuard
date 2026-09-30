package com.algoritmonatural.naturaguard.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.algoritmonatural.naturaguard.shared.Notifier
import com.algoritmonatural.naturaguard.shared.SecurityEvent
import com.algoritmonatural.naturaguard.shared.Severity

/**
 * Administrador do dispositivo so com "watch-login": o Android avisa esta app
 * de cada PIN/palavra-passe errado no ecra de bloqueio. Nao pode apagar,
 * bloquear nem alterar nada. Nota: o Android marca este mecanismo como
 * descontinuado; em algumas versoes ou marcas pode nao ser entregue.
 */
class GuardAdminReceiver : DeviceAdminReceiver() {

    override fun onPasswordFailed(context: Context, intent: Intent) {
        val count = Attempts.recordFailure(context)
        val severity = if (count >= CRITICAL_AFTER) Severity.CRITICAL else Severity.WARNING
        Notifier.raise(
            context,
            SecurityEvent(
                "desbloqueio_falhado", severity,
                "Tentativa falhada de desbloquear o telemovel ($count nos ultimos 10 minutos).",
                "admin",
            ),
        )
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent) {
        val before = Attempts.consumeRecent(context)
        if (before > 0) {
            Notifier.raise(
                context,
                SecurityEvent(
                    "desbloqueio_apos_falhas", Severity.WARNING,
                    "O telemovel foi desbloqueado depois de $before tentativa(s) falhada(s). Foi voce?",
                    "admin",
                ),
            )
        }
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Notifier.raise(
            context,
            SecurityEvent(
                "protecao_desativada", Severity.CRITICAL,
                "A deteccao de tentativas de desbloqueio foi desativada.", "admin",
            ),
        )
    }

    private companion object {
        const val CRITICAL_AFTER = 3
    }
}

/** Contador de falhas na janela dos ultimos 10 minutos. */
object Attempts {
    private const val WINDOW_MS = 10 * 60 * 1000L

    @Synchronized
    fun recordFailure(context: Context): Int {
        val prefs = context.getSharedPreferences("attempts", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val kept = recent(prefs.getString("times", ""), now) + now
        prefs.edit().putString("times", kept.joinToString(",")).apply()
        return kept.size
    }

    @Synchronized
    fun consumeRecent(context: Context): Int {
        val prefs = context.getSharedPreferences("attempts", Context.MODE_PRIVATE)
        val n = recent(prefs.getString("times", ""), System.currentTimeMillis()).size
        prefs.edit().remove("times").apply()
        return n
    }

    private fun recent(text: String?, now: Long): List<Long> =
        (text ?: "").split(',').mapNotNull { it.toLongOrNull() }.filter { now - it in 0..WINDOW_MS }
}
