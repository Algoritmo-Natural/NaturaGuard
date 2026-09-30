package com.algoritmonatural.naturaguard.scan

/**
 * Decide se uma app veio de uma loja de confianca. Funcao pura para testar.
 *
 * O `installingPackageName` pode ser escolhido por quem instala
 * (`adb install -i com.android.vending app.apk`), por isso a partir do
 * Android 11 (API 30) exige-se tambem o `initiatingPackageName`, que o
 * sistema preenche com quem pediu de facto a instalacao.
 */
object InstallTrust {
    val trustedStores = setOf(
        "com.android.vending",
        "com.sec.android.app.samsungapps",
        "com.amazon.venezia",
        "com.huawei.appmarket",
        "com.xiaomi.mipicks",
        "com.xiaomi.market",
        "com.heytap.market",
        "com.oppo.market",
        "com.bbk.appstore",
    )

    fun isTrusted(sdk: Int, installing: String?, initiating: String?): Boolean {
        if (installing == null || installing !in trustedStores) return false
        if (sdk < 30) return true // antes do Android 11 nao ha forma fiavel de confirmar
        return initiating != null && initiating in trustedStores
    }
}
