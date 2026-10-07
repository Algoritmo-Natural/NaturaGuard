package com.algoritmonatural.naturaguard.wireguard

/**
 * Verificação rápida de um .conf do WireGuard antes de o guardar, com
 * mensagens em português. Não substitui o parser da biblioteca (que valida
 * as chaves e endereços a fundo); serve para explicar ao utilizador o que
 * falta e para avisar quando o túnel não protege todo o tráfego.
 */
object TunnelConfigCheck {

    data class Result(val errors: List<String>, val warnings: List<String>) {
        val ok: Boolean get() = errors.isEmpty()
    }

    fun check(text: String): Result {
        val sections = parse(text)
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val iface = sections.filter { it.first == "interface" }
        val peers = sections.filter { it.first == "peer" }

        if (iface.size != 1) errors += "Tem de haver exatamente uma secção [Interface]."
        iface.firstOrNull()?.second?.let { keys ->
            if ("privatekey" !in keys) errors += "Falta a PrivateKey em [Interface]."
            if ("address" !in keys) errors += "Falta o Address em [Interface]."
            if ("dns" !in keys) warnings += "Sem DNS definido: os pedidos de nomes podem sair fora do túnel."
        }

        if (peers.isEmpty()) errors += "Falta a secção [Peer] (o servidor)."
        peers.forEachIndexed { i, (_, keys) ->
            val n = if (peers.size > 1) " (servidor ${i + 1})" else ""
            if ("publickey" !in keys) errors += "Falta a PublicKey em [Peer]$n."
            val endpoint = keys["endpoint"]
            if (endpoint == null) {
                errors += "Falta o Endpoint em [Peer]$n."
            } else if (!hasPort(endpoint)) {
                errors += "O Endpoint$n tem de indicar a porta (ex.: vpn.exemplo.pt:51820)."
            }
            val allowed = keys["allowedips"]
            if (allowed == null) {
                errors += "Falta o AllowedIPs em [Peer]$n."
            } else {
                val nets = allowed.split(',').map { it.trim() }
                if ("0.0.0.0/0" !in nets) {
                    warnings += "AllowedIPs$n não inclui 0.0.0.0/0: só parte do tráfego passa pelo túnel."
                } else if ("::/0" !in nets) {
                    warnings += "AllowedIPs$n não inclui ::/0: o tráfego IPv6 pode sair fora do túnel."
                }
            }
        }
        return Result(errors, warnings)
    }

    /** "host:porta" ou "[ipv6]:porta", com porta 1–65535. */
    private fun hasPort(endpoint: String): Boolean {
        val port = endpoint.substringAfterLast(':', "")
        if (endpoint.startsWith("[") && !endpoint.substringBeforeLast(':').endsWith("]")) return false
        return port.toIntOrNull()?.let { it in 1..65535 } ?: false
    }

    /** Lista de (secção, chaves em minúsculas → valor). Ignora comentários e linhas vazias. */
    private fun parse(text: String): List<Pair<String, Map<String, String>>> {
        val result = mutableListOf<Pair<String, MutableMap<String, String>>>()
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                result += line.substring(1, line.length - 1).trim().lowercase() to mutableMapOf()
                continue
            }
            val eq = line.indexOf('=')
            if (eq <= 0 || result.isEmpty()) continue
            val key = line.substring(0, eq).trim().lowercase()
            val value = line.substring(eq + 1).trim()
            val map = result.last().second
            map[key] = if (key in map) map[key] + "," + value else value
        }
        return result
    }
}
