package com.algoritmonatural.naturaguard

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.algoritmonatural.naturaguard.admin.GuardAdminReceiver
import com.algoritmonatural.naturaguard.rootdetection.RootDetector
import com.algoritmonatural.naturaguard.scan.ScanWorker
import com.algoritmonatural.naturaguard.scan.Scanner
import com.algoritmonatural.naturaguard.shared.Alert
import com.algoritmonatural.naturaguard.shared.EventLogger
import com.algoritmonatural.naturaguard.shared.Notifier
import com.algoritmonatural.naturaguard.shared.SecurityEvent
import com.algoritmonatural.naturaguard.shared.Severity
import com.algoritmonatural.naturaguard.usageaudit.UsageAuditManager

/**
 * A app só abre depois de o utilizador provar quem é com o desbloqueio do
 * próprio telemóvel (biometria ou PIN/padrão). Volta a bloquear ao sair.
 */
class MainActivity : FragmentActivity() {

    private val authenticators =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    private lateinit var logger: EventLogger
    private lateinit var lockView: LinearLayout
    private lateinit var lockText: TextView
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var alertsBox: LinearLayout

    private var unlocked = false
    private var authenticating = false
    private var leavingForSettings = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        logger = EventLogger(applicationContext)
        buildUi()
    }

    override fun onStart() {
        super.onStart()
        if (!unlocked && !authenticating) authenticate()
    }

    override fun onStop() {
        super.onStop()
        if (!authenticating && !leavingForSettings) lock()
    }

    // ---------- bloqueio ----------

    private fun authenticate() {
        val can = BiometricManager.from(this).canAuthenticate(authenticators)
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            lockText.text = "Defina um bloqueio de ecrã no telemóvel (PIN, padrão ou biometria) para usar o NaturaGuard."
            return
        }
        authenticating = true
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                authenticating = false
                unlock()
            }

            override fun onAuthenticationFailed() {
                Notifier.raise(
                    applicationContext,
                    SecurityEvent(
                        "entrada_app_falhada", Severity.WARNING,
                        "Tentativa falhada de abrir o NaturaGuard.", "auth",
                    ),
                )
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                authenticating = false
                if (errorCode == BiometricPrompt.ERROR_LOCKOUT || errorCode == BiometricPrompt.ERROR_LOCKOUT_PERMANENT) {
                    Notifier.raise(
                        applicationContext,
                        SecurityEvent(
                            "entrada_app_bloqueada", Severity.CRITICAL,
                            "Demasiadas tentativas falhadas de abrir o NaturaGuard: acesso bloqueado pelo Android.", "auth",
                        ),
                    )
                }
                lockText.text = "Bloqueado. Toque em Desbloquear."
            }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("NaturaGuard")
                .setSubtitle("Confirme que é você")
                .setAllowedAuthenticators(authenticators)
                .build()
        )
    }

    private fun unlock() {
        unlocked = true
        lockView.visibility = View.GONE
        content.visibility = View.VISIBLE
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        ScanWorker.schedule(applicationContext)
        refresh()
    }

    private fun lock() {
        unlocked = false
        content.visibility = View.GONE
        lockView.visibility = View.VISIBLE
        lockText.text = "Bloqueado."
    }

    // ---------- ecrã ----------

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        lockText = TextView(this).apply { text = "Bloqueado."; textSize = 18f }
        lockView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(lockText)
            addView(button("Desbloquear") { if (!authenticating) authenticate() })
        }

        status = TextView(this).apply { textSize = 16f }
        alertsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(TextView(context).apply { text = "NaturaGuard"; textSize = 24f })
            addView(status)
            addView(button("Verificar agora") { scanNow() })
            addView(button("Ativar deteção de tentativas de desbloqueio") { enableAdmin() })
            addView(button("Verificar root") { rootCheck() })
            addView(button("Auditoria de uso de apps (24h)") { usageAudit() })
            addView(TextView(context).apply { text = "\nAlertas (toque para marcar como visto)"; textSize = 16f })
            addView(alertsBox)
        }

        root.addView(lockView)
        root.addView(content)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun button(label: String, action: () -> Unit) =
        Button(this).apply {
            text = label
            setOnClickListener { action() }
        }

    private fun refresh() {
        val active = isAdminActive()
        val pending = logger.unmarkedImportantCount()
        status.text = "Deteção de tentativas de desbloqueio: " + (if (active) "ATIVA" else "INATIVA") +
            "\nAlertas por ver: $pending"
        alertsBox.removeAllViews()
        val alerts = logger.alerts(50)
        if (alerts.isEmpty()) {
            alertsBox.addView(TextView(this).apply { text = "Sem alertas." })
        }
        alerts.forEach { alertsBox.addView(alertRow(it)) }
    }

    private fun alertRow(alert: Alert): TextView = TextView(this).apply {
        val tag = when (alert.severity) {
            Severity.CRITICAL -> "CRÍTICO"
            Severity.WARNING -> "AVISO"
            Severity.INFO -> "info"
        }
        text = (if (alert.marked) "✓ " else "") + "[$tag] ${alert.timestamp}\n${alert.message}"
        setPadding(0, 24, 0, 24)
        setTextColor(
            when {
                alert.marked -> Color.GRAY
                alert.severity == Severity.CRITICAL -> Color.RED
                alert.severity == Severity.WARNING -> Color.rgb(200, 110, 0)
                else -> Color.BLACK
            }
        )
        setOnClickListener {
            logger.mark(alert.id)
            refresh()
        }
    }

    // ---------- ações ----------

    private fun scanNow() {
        status.text = "A verificar…"
        Thread {
            val findings = Scanner.run(applicationContext)
            runOnUiThread {
                refresh()
                if (findings.isEmpty()) status.text = status.text.toString() + "\nSem alterações desde a última verificação."
            }
        }.start()
    }

    private fun adminComponent() = ComponentName(this, GuardAdminReceiver::class.java)

    private fun isAdminActive(): Boolean =
        getSystemService(DevicePolicyManager::class.java)?.isAdminActive(adminComponent()) == true

    private fun enableAdmin() {
        if (isAdminActive()) {
            status.text = "A deteção de tentativas de desbloqueio já está ativa."
            return
        }
        leavingForSettings = true // o ecrã do sistema tira a app do primeiro plano
        startActivity(
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent())
                .putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Só serve para ser avisado de PIN/palavra-passe errados no ecrã de bloqueio. " +
                        "Não apaga nem bloqueia nada.",
                )
        )
    }

    override fun onResume() {
        super.onResume()
        leavingForSettings = false
        if (unlocked) refresh()
    }

    private fun rootCheck() {
        val suspected = RootDetector(this).checkAndLog()
        refresh()
        status.text = status.text.toString() + "\n" +
            if (suspected) "Indícios de root detetados." else "Sem indícios de root."
    }

    private fun usageAudit() {
        val manager = UsageAuditManager(this)
        if (!manager.hasUsageAccess()) {
            leavingForSettings = true
            startActivity(manager.buildGrantAccessIntent())
            status.text = "Conceda 'Dados de utilização' ao NaturaGuard nas Definições e volte."
            return
        }
        val summaries = manager.auditLast24Hours()
        refresh()
        status.text = status.text.toString() + "\nAuditoria: ${summaries.size} apps com atividade nas últimas 24h."
    }
}
