package com.algoritmonatural.naturaguard.rootdetection

import android.content.Context
import com.algoritmonatural.naturaguard.shared.Notifier
import com.algoritmonatural.naturaguard.shared.SecurityEvent
import com.algoritmonatural.naturaguard.shared.Severity
import java.io.File

/**
 * Deteccao heuristica de root. Pode ser enganada por quem esconde o root:
 * e um sinal, nao uma garantia. A atestacao Play Integrity (no servidor)
 * seria a verificacao forte e nao faz parte desta app offline.
 */
class RootDetector(private val context: Context) {

    private val suspiciousPaths = listOf(
        "/system/app/Superuser.apk",
        "/sbin/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/data/local/su",
        "/su/bin/su",
    )

    fun isSuspected(): Boolean = suspiciousPaths.any { File(it).exists() } || hasSuInPath()

    /** Verificacao manual: regista e notifica se houver indicios. */
    fun checkAndLog(): Boolean {
        val suspected = isSuspected()
        if (suspected) {
            Notifier.raise(
                context,
                SecurityEvent(
                    type = "root_suspected",
                    severity = Severity.CRITICAL,
                    message = "Indicios de root neste telemovel (heuristica, nao e atestacao criptografica).",
                    source = "rootdetection",
                ),
            )
        }
        return suspected
    }

    private fun hasSuInPath(): Boolean {
        val pathEnv = System.getenv("PATH") ?: return false
        return pathEnv.split(":").any { dir -> dir.isNotEmpty() && File(dir, "su").exists() }
    }
}
