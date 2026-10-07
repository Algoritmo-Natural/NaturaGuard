package com.algoritmonatural.naturaguard.wireguard

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.algoritmonatural.naturaguard.shared.Notifier
import com.algoritmonatural.naturaguard.shared.SecurityEvent
import com.algoritmonatural.naturaguard.shared.Severity
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Túnel WireGuard da NaturaGuard (vindo do repositório WireGuard).
 *
 * - O .conf (que tem a chave privada) só fica guardado cifrado com uma chave
 *   AES-256-GCM do Android Keystore, que nunca sai do telemóvel.
 * - Apagar a configuração destrói também a chave: o que ficar no disco é ilegível.
 * - Liga a um servidor que o utilizador já tenha; não cria servidores.
 * - Se o túnel cair sem o utilizador o desligar, é lançado um alerta.
 */
class SecureTunnelManager(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        appContext = app
    }

    fun hasStoredConfig(): Boolean = prefs.contains(KEY_CONFIG)

    /** Valida e guarda cifrado. Lança IllegalArgumentException com a razão se o .conf for inválido. */
    fun saveConfig(text: String): TunnelConfigCheck.Result {
        val check = TunnelConfigCheck.check(text)
        require(check.ok) { check.errors.joinToString(" ") }
        try {
            parse(text) // validação completa pela biblioteca (chaves, endereços, portas)
        } catch (e: Exception) {
            throw IllegalArgumentException("Configuração rejeitada pelo WireGuard: ${e.message ?: e.javaClass.simpleName}")
        }
        prefs.edit().putString(KEY_CONFIG, encrypt(text)).apply()
        return check
    }

    fun deleteConfig() {
        disconnect()
        prefs.edit().remove(KEY_CONFIG).apply()
        try {
            KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        } catch (_: Exception) {
            // sem chave para apagar
        }
    }

    fun isConnected(): Boolean = state == Tunnel.State.UP

    /** Recebe "ligado", "desligado" ou "erro: …" na thread do túnel. */
    fun setListener(onState: ((String) -> Unit)?) {
        listener = onState
    }

    /** Liga em segundo plano. Antes, a app tem de ter a autorização VPN (VpnService.prepare). */
    fun connect() {
        val stored = prefs.getString(KEY_CONFIG, null) ?: run {
            listener?.invoke("erro: sem configuração guardada")
            return
        }
        wantUp = true
        worker.execute {
            val config = try {
                parse(decrypt(stored))
            } catch (e: Exception) {
                // Chave do Keystore perdida ou dados alterados: não há forma segura de recuperar.
                wantUp = false
                prefs.edit().remove(KEY_CONFIG).apply()
                Notifier.raise(
                    app,
                    SecurityEvent(
                        "tunel_config_ilegivel", Severity.WARNING,
                        "A configuração do túnel guardada não pôde ser decifrada e foi apagada. Importe-a de novo.",
                        "wireguard",
                    ),
                )
                listener?.invoke("erro: configuração ilegível, importe de novo")
                return@execute
            }
            try {
                backend(app).setState(tunnel, Tunnel.State.UP, config)
            } catch (e: Exception) {
                wantUp = false
                listener?.invoke("erro: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun disconnect() {
        wantUp = false
        worker.execute {
            try {
                backend(app).setState(tunnel, Tunnel.State.DOWN, null)
            } catch (_: Exception) {
                // já estava desligado
            }
        }
    }

    // ---------- cifra ----------

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val parts = stored.split(':', limit = 2)
        require(parts.size == 2) { "formato inválido" }
        val (iv, data) = parts.map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, existingKey() ?: error("chave em falta"), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(data), Charsets.UTF_8)
    }

    private fun existingKey(): SecretKey? {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        return (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    private fun key(): SecretKey {
        existingKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun parse(text: String): Config = Config.parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))

    private companion object {
        const val PREFS = "tunel"
        const val KEY_CONFIG = "conf_cifrado"
        const val KEY_ALIAS = "naturaguard_tunel"
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORM = "AES/GCM/NoPadding"

        // Um só backend e um só túnel por processo, partilhados entre ecrãs.
        @Volatile private var backend: GoBackend? = null
        @Volatile var state: Tunnel.State = Tunnel.State.DOWN
        @Volatile var wantUp = false
        @Volatile var listener: ((String) -> Unit)? = null
        @Volatile lateinit var appContext: Context
        val worker = Executors.newSingleThreadExecutor()

        fun backend(context: Context): GoBackend =
            backend ?: synchronized(this) { backend ?: GoBackend(context).also { backend = it } }

        val tunnel = object : Tunnel {
            override fun getName() = "naturaguard"

            override fun onStateChange(newState: Tunnel.State) {
                val dropped = state == Tunnel.State.UP && newState == Tunnel.State.DOWN && wantUp
                state = newState
                listener?.invoke(if (newState == Tunnel.State.UP) "ligado" else "desligado")
                if (dropped) {
                    wantUp = false
                    Notifier.raise(
                        appContext,
                        SecurityEvent(
                            "tunel_caiu", Severity.WARNING,
                            "O túnel seguro WireGuard desligou-se sem ter sido pedido. O tráfego deixou de estar protegido.",
                            "wireguard",
                        ),
                    )
                }
            }
        }
    }
}
