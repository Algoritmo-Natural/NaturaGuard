package com.algoritmonatural.naturaguard.usageaudit

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings
import com.algoritmonatural.naturaguard.shared.EventLogger
import com.algoritmonatural.naturaguard.shared.SecurityEvent
import com.algoritmonatural.naturaguard.shared.Severity
import java.util.concurrent.TimeUnit

/**
 * PACKAGE_USAGE_STATS so pode ser concedida pelo utilizador nas Definicoes.
 * Esta classe nunca tenta contornar isso: verifica e encaminha para o ecra certo.
 */
class UsageAuditManager(private val context: Context) {

    private val eventLogger = EventLogger(context)

    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        // unsafeCheckOpNoThrow so existe a partir do Android 10 (API 29); a app aceita Android 8.
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun buildGrantAccessIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    /** Le o uso em primeiro plano das ultimas 24h e regista um resumo. */
    fun auditLast24Hours(): List<AppUsageSummary> {
        check(hasUsageAccess()) { "Acesso a dados de utilizacao nao concedido" }

        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val start = end - TimeUnit.HOURS.toMillis(24)

        val summaries = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
            .filter { it.totalTimeInForeground > 0 }
            .map { AppUsageSummary(it.packageName, it.totalTimeInForeground) }
            .sortedByDescending { it.totalForegroundMillis }

        eventLogger.log(
            SecurityEvent(
                type = "usage_audit_completed",
                severity = Severity.INFO,
                message = "Auditoria de uso das ultimas 24h: ${summaries.size} apps com atividade.",
                source = "usageaudit",
            ),
        )
        return summaries
    }
}

data class AppUsageSummary(
    val packageName: String,
    val totalForegroundMillis: Long,
)
