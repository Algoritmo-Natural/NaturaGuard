package com.algoritmonatural.naturaguard.scan

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.algoritmonatural.naturaguard.admin.GuardAdminReceiver
import com.algoritmonatural.naturaguard.rootdetection.RootDetector
import com.algoritmonatural.naturaguard.shared.Notifier
import com.algoritmonatural.naturaguard.shared.SecurityEvent
import com.algoritmonatural.naturaguard.shared.Severity
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Le o estado atual, compara com a referencia e levanta alertas. */
object Scanner {

    /** Devolve os achados desta verificacao (ja registados e notificados). */
    @Synchronized
    fun run(context: Context): List<Finding> {
        val app = context.applicationContext
        val store = SnapshotStore(app)
        val now = collect(app)
        val findings = when (val loaded = store.load()) {
            is Loaded.Ok -> Diff.compare(loaded.snapshot, now)
            Loaded.Missing -> Diff.compare(null, now)
            // Estragada: nao a tratar como "primeira vez" em silencio. Compara com um
            // telemovel limpo, para que tudo o que existe apareca como novo.
            Loaded.Corrupted -> listOf(
                Finding(
                    "referencia_corrompida", Severity.WARNING,
                    "A referência guardada estava estragada e foi recriada. Reveja os alertas seguintes.",
                )
            ) + Diff.compare(Snapshot(), now)
        }
        findings.forEach {
            Notifier.raise(app, SecurityEvent(it.type, it.severity, it.message, "scan"))
        }
        store.save(now)
        return findings
    }

    fun collect(context: Context): Snapshot {
        val cr = context.contentResolver
        val self = context.packageName
        val own = ComponentName(context, GuardAdminReceiver::class.java).flattenToString()

        val accessibility = (Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .split(':').filter { it.isNotBlank() }.toSet()

        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admins = (dpm?.activeAdmins ?: emptyList<ComponentName>())
            .map { it.flattenToString() }.filter { it != own }.toSet()

        val listeners = NotificationManagerCompat.getEnabledListenerPackages(context)
            .filter { it != self }.toSet()

        val sideloaded = context.packageManager.getInstalledApplications(0)
            .filter { it.packageName != self && isUserApp(it) }
            .filter { !isTrusted(context, it.packageName) }
            .map { it.packageName }.toSet()

        val keyguard = context.getSystemService(KeyguardManager::class.java)
        return Snapshot(
            accessibility = accessibility,
            admins = admins,
            listeners = listeners,
            sideloaded = sideloaded,
            adbEnabled = Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 1,
            devOptions = Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1,
            deviceSecure = keyguard?.isDeviceSecure ?: true,
            patchOld = patchAgeDays()?.let { it > 120 } ?: false,
            rootSuspected = RootDetector(context).isSuspected(),
        )
    }

    private fun isUserApp(info: ApplicationInfo): Boolean =
        info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0

    private fun isTrusted(context: Context, pkg: String): Boolean {
        val pm = context.packageManager
        return try {
            if (Build.VERSION.SDK_INT >= 30) {
                val info = pm.getInstallSourceInfo(pkg)
                InstallTrust.isTrusted(Build.VERSION.SDK_INT, info.installingPackageName, info.initiatingPackageName)
            } else {
                @Suppress("DEPRECATION")
                InstallTrust.isTrusted(Build.VERSION.SDK_INT, pm.getInstallerPackageName(pkg), null)
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun patchAgeDays(): Long? = try {
        ChronoUnit.DAYS.between(LocalDate.parse(Build.VERSION.SECURITY_PATCH), LocalDate.now())
    } catch (_: Exception) {
        null
    }
}
