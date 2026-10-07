package com.algoritmonatural.naturaguard

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.algoritmonatural.naturaguard.admin.GuardAdminReceiver
import com.algoritmonatural.naturaguard.rootdetection.RootDetector
import com.algoritmonatural.naturaguard.scan.ScanWorker
import com.algoritmonatural.naturaguard.scan.Scanner
import com.algoritmonatural.naturaguard.shared.Alert
import com.algoritmonatural.naturaguard.shared.EventLogger
import com.algoritmonatural.naturaguard.shared.Notifier
import com.algoritmonatural.naturaguard.shared.SecurityEvent
import com.algoritmonatural.naturaguard.shared.Severity
import com.algoritmonatural.naturaguard.shared.localTime
import com.algoritmonatural.naturaguard.usageaudit.UsageAuditManager
import com.algoritmonatural.naturaguard.wireguard.SecureTunnelManager

/**
 * A app só abre depois de o utilizador provar quem é com o desbloqueio do
 * próprio telemóvel (biometria ou PIN/padrão). Volta a bloquear ao sair;
 * a única exceção é uma ida curta às Definições pedida pela própria app.
 */
class MainActivity : AppCompatActivity() {

    private val authenticators =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    private lateinit var logger: EventLogger
    private lateinit var prompt: BiometricPrompt
    private lateinit var lockView: LinearLayout
    private lateinit var lockText: TextView
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var notifWarning: LinearLayout
    private lateinit var alertsBox: LinearLayout
    private lateinit var tunnelStatus: TextView
    private lateinit var tunnel: SecureTunnelManager

    private var unlocked = false
    private var authenticating = false
    private var leavingForSettings = false
    private var stoppedAt = 0L
    private var failedReads = 0

    // Registados antes do onStart, como o Android exige.
    private val pickConfig = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importConfig(uri)
    }
    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (VpnService.prepare(this) == null) tunnel.connect() else tunnelStatus.text = "Túnel: autorização VPN recusada."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        logger = EventLogger(applicationContext)
        tunnel = SecureTunnelManager(applicationContext)
        // Criado uma só vez no onCreate: numa rotação o Android volta a ligar o callback ao prompt aberto.
        prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), callback)
        // Só numa rotação (mesma sessão) se conserva o estado; nunca depois de a app ir para segundo plano.
        unlocked = savedInstanceState?.getBoolean(KEY_UNLOCKED) ?: false
        authenticating = savedInstanceState?.getBoolean(KEY_AUTHENTICATING) ?: false
        buildUi()
        if (unlocked) showContent()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (isChangingConfigurations) {
            outState.putBoolean(KEY_UNLOCKED, unlocked)
            outState.putBoolean(KEY_AUTHENTICATING, authenticating)
        }
    }

    override fun onStart() {
        super.onStart()
        if (unlocked && leavingForSettings && SystemClock.elapsedRealtime() - stoppedAt > SETTINGS_GRACE_MS) {
            lock()
        }
        leavingForSettings = false
        if (!unlocked && !authenticating) authenticate()
    }

    override fun onResume() {
        super.onResume()
        // Um diálogo do sistema (ex.: autorização VPN) não passa pelo onStop/onStart; sem isto a
        // exceção "ida às Definições" ficava ligada e a próxima saída por Home não bloqueava.
        leavingForSettings = false
        tunnel.setListener { state -> runOnUiThread { if (unlocked) showTunnelState(state) } }
        if (unlocked) refresh()
    }

    override fun onPause() {
        super.onPause()
        tunnel.setListener(null)
    }

    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations || authenticating) return
        stoppedAt = SystemClock.elapsedRealtime()
        // Sair por Home/recentes (ou qualquer saída que não seja a ida às Definições) bloqueia já.
        if (!leavingForSettings) lock()
    }

    // ---------- bloqueio ----------

    private val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            authenticating = false
            failedReads = 0
            unlock()
        }

        override fun onAuthenticationFailed() {
            // Chamado a cada leitura de dedo que não coincide; só a 3.ª na mesma tentativa conta como alerta.
            failedReads++
            if (failedReads == FAILED_READS_ALERT) {
                Notifier.raise(
                    applicationContext,
                    SecurityEvent(
                        "entrada_app_falhada", Severity.WARNING,
                        "$FAILED_READS_ALERT leituras falhadas seguidas ao abrir o NaturaGuard.", "auth",
                    ),
                )
            }
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            authenticating = false
            failedReads = 0
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
    }

    private fun authenticate() {
        val can = BiometricManager.from(this).canAuthenticate(authenticators)
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            lockText.text = "Defina um bloqueio de ecrã no telemóvel (PIN, padrão ou biometria) para usar o NaturaGuard."
            return
        }
        authenticating = true
        failedReads = 0
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
        showContent()
        if (Build.VERSION.SDK_INT >= 33 && !notificationsAllowed()) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        ScanWorker.schedule(applicationContext)
        refresh()
    }

    private fun showContent() {
        lockView.visibility = View.GONE
        content.visibility = View.VISIBLE
    }

    private fun lock() {
        unlocked = false
        content.visibility = View.GONE
        lockView.visibility = View.VISIBLE
        lockText.text = "Bloqueado."
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (unlocked) refresh()
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
        notifWarning = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(TextView(context).apply {
                text = "\nNotificações DESLIGADAS: os alertas não chegam até abrir a app."
                setTextColor(Color.RED)
            })
            addView(button("Ligar notificações") { openNotificationSettings() })
        }
        alertsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(TextView(context).apply { text = "NaturaGuard"; textSize = 24f })
            addView(status)
            addView(notifWarning)
            addView(button("Verificar agora") { scanNow() })
            addView(button("Ativar deteção de tentativas de desbloqueio") { enableAdmin() })
            addView(button("Verificar root") { rootCheck() })
            addView(button("Auditoria de uso de apps (24h)") { usageAudit() })
            addView(TextView(context).apply { text = "\nTúnel seguro (WireGuard)"; textSize = 16f })
            tunnelStatus = TextView(context)
            addView(tunnelStatus)
            addView(button("Importar configuração (.conf)") { openPicker() })
            addView(button("Ligar / desligar túnel") { toggleTunnel() })
            addView(button("Apagar configuração do túnel") { tunnel.deleteConfig(); showTunnelState(null) })
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

    private fun notificationsAllowed(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun refresh() {
        val alerts = logger.alerts(200)
        val pending = alerts.count { !it.marked && it.severity.rank >= Severity.WARNING.rank }
        status.text = "Deteção de tentativas de desbloqueio: " + (if (isAdminActive()) "ATIVA" else "INATIVA") +
            "\nAlertas por ver: $pending"
        notifWarning.visibility = if (notificationsAllowed()) View.GONE else View.VISIBLE
        alertsBox.removeAllViews()
        if (alerts.isEmpty()) {
            alertsBox.addView(TextView(this).apply { text = "Sem alertas." })
        }
        alerts.take(50).forEach { alertsBox.addView(alertRow(it)) }
        showTunnelState(null)
    }

    private fun showTunnelState(event: String?) {
        val base = when {
            !tunnel.hasStoredConfig() -> "sem configuração"
            tunnel.isConnected() -> "LIGADO"
            else -> "desligado"
        }
        tunnelStatus.text = "Túnel: $base" + (event?.takeIf { it.startsWith("erro") }?.let { " ($it)" } ?: "")
    }

    // ---------- túnel ----------

    private fun openPicker() {
        leavingForSettings = true
        try {
            pickConfig.launch(arrayOf("*/*"))
        } catch (_: ActivityNotFoundException) {
            leavingForSettings = false
            tunnelStatus.text = "Este telemóvel não tem seletor de ficheiros."
        }
    }

    private fun importConfig(uri: Uri) {
        val text = try {
            contentResolver.openInputStream(uri)?.use { input ->
                // Um .conf tem poucas centenas de bytes; recusar ficheiros grandes evita abusos.
                val bytes = input.readNBytesCompat(MAX_CONF_BYTES + 1)
                if (bytes.size > MAX_CONF_BYTES) null else String(bytes, Charsets.UTF_8)
            }
        } catch (_: Exception) {
            null
        }
        if (text == null) {
            tunnelStatus.text = "Túnel: ficheiro ilegível ou demasiado grande."
            return
        }
        tunnelStatus.text = try {
            val check = tunnel.saveConfig(text)
            "Túnel: configuração guardada (cifrada)." +
                if (check.warnings.isEmpty()) "" else "\nAtenção: " + check.warnings.joinToString(" ")
        } catch (e: IllegalArgumentException) {
            "Túnel: configuração recusada. ${e.message}"
        }
    }

    private fun toggleTunnel() {
        if (!tunnel.hasStoredConfig()) {
            tunnelStatus.text = "Túnel: importe primeiro uma configuração."
            return
        }
        if (tunnel.isConnected()) {
            tunnel.disconnect()
            return
        }
        val consent = VpnService.prepare(this)
        if (consent == null) {
            tunnel.connect()
        } else {
            leavingForSettings = true
            vpnConsent.launch(consent)
        }
    }

    private fun alertRow(alert: Alert): TextView = TextView(this).apply {
        val tag = when (alert.severity) {
            Severity.CRITICAL -> "CRÍTICO"
            Severity.WARNING -> "AVISO"
            Severity.INFO -> "info"
        }
        text = (if (alert.marked) "✓ " else "") + "[$tag] ${localTime(alert.timestamp)}\n${alert.message}"
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
            val message = try {
                val findings = Scanner.run(applicationContext)
                if (findings.isEmpty()) "Sem alterações desde a última verificação." else "${findings.size} resultado(s)."
            } catch (e: Exception) {
                "Erro na verificação: ${e.javaClass.simpleName}"
            }
            runOnUiThread {
                if (unlocked) {
                    refresh()
                    status.text = status.text.toString() + "\n" + message
                }
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
        openSettings(
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent())
                .putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Só serve para ser avisado de PIN/palavra-passe errados no ecrã de bloqueio. " +
                        "Não apaga nem bloqueia nada.",
                )
        )
    }

    private fun openNotificationSettings() {
        openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    /** Abre um ecrã do sistema; se o fabricante o removeu, cai para as definições da app. */
    private fun openSettings(intent: Intent) {
        leavingForSettings = true
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (_: ActivityNotFoundException) {
                leavingForSettings = false
                status.text = "Este telemóvel não tem esse ecrã de Definições."
            }
        }
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
            openSettings(manager.buildGrantAccessIntent())
            status.text = "Conceda 'Dados de utilização' ao NaturaGuard nas Definições e volte."
            return
        }
        val summaries = manager.auditLast24Hours()
        refresh()
        status.text = status.text.toString() + "\nAuditoria: ${summaries.size} apps com atividade nas últimas 24h."
    }

    private companion object {
        const val KEY_UNLOCKED = "unlocked"
        const val KEY_AUTHENTICATING = "authenticating"
        const val SETTINGS_GRACE_MS = 60_000L
        const val FAILED_READS_ALERT = 3
        const val MAX_CONF_BYTES = 16 * 1024
    }
}

/** InputStream.readNBytes só existe a partir do Android 13. */
private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(4096)
    while (out.size() < limit) {
        val n = read(buf, 0, minOf(buf.size, limit - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
