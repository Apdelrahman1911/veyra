package dev.veyra.source.network

import dev.veyra.model.FailureCode
import io.ktor.http.URLBuilder
import io.ktor.http.Url

/** Pure lexical policy. A selected URL must additionally pass [SafeHttp.validateUrl]. */
object PublicUrlPolicy {
    fun normalize(value: String): String {
        if (value.length !in 1..8_192 || value.any { it.code < 32 || it.code == 127 } || !value.startsWith("https://", true)) {
            sourceFailure(FailureCode.UNSUPPORTED_SOURCE, "https_url_required")
        }
        val url = try { Url(value) } catch (_: Exception) { sourceFailure(FailureCode.UNSUPPORTED_SOURCE, "invalid_url") }
        if (url.protocol.name != "https" || url.port != 443 || url.user != null || url.password != null) {
            sourceFailure(FailureCode.UNSUPPORTED_SOURCE, "unsafe_url_origin")
        }
        val host = url.host.lowercase().removeSuffix(".")
        val labels = host.split('.')
        val forbiddenSuffix = setOf("local", "localhost", "internal", "lan", "home", "invalid", "test", "onion", "arpa")
        if (host.length > 253 || labels.size < 2 || labels.last() in forbiddenSuffix ||
            labels.any { !Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?").matches(it) } ||
            labels.last().none { it in 'a'..'z' } || host.startsWith("0x") ||
            host == "metadata.google.internal") {
            sourceFailure(FailureCode.UNSUPPORTED_SOURCE, "public_dns_name_required")
        }
        return URLBuilder(url).apply { this.host = host; fragment = "" }.buildString()
    }

    fun isPublicAddress(value: String): Boolean {
        if ('%' in value || value.isEmpty()) return false
        if (':' in value) {
            val words = ipv6Words(value) ?: return false
            // Only global unicast; exclude transition/tunnel and documentation ranges.
            return words[0] in 0x2000..0x3fff && words[0] != 0x2002 &&
                !(words[0] == 0x2001 && (words[1] == 0 || words[1] == 0x0db8))
        }
        val octets = value.split('.').map { it.toIntOrNull() ?: return false }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        val (a, b, c) = octets
        return !(a == 0 || a == 10 || a == 127 || a >= 224 ||
            (a == 100 && b in 64..127) || (a == 169 && b == 254) ||
            (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
            (a == 192 && b == 0) || (a == 192 && b == 88 && c == 99) ||
            (a == 198 && b in 18..19) || (a == 198 && b == 51 && c == 100) ||
            (a == 203 && b == 0 && c == 113))
    }

    private fun ipv6Words(value: String): List<Int>? {
        if ('.' in value || value.count { it == ':' } < 2) return null
        val split = value.split("::")
        if (split.size > 2) return null
        fun side(text: String): List<Int>? = if (text.isEmpty()) emptyList() else text.split(':').map {
            if (it.length !in 1..4) return null
            it.toIntOrNull(16)?.takeIf { word -> word in 0..0xffff } ?: return null
        }
        val left = side(split[0]) ?: return null
        if (split.size == 1) return left.takeIf { it.size == 8 }
        val right = side(split[1]) ?: return null
        val zeros = 8 - left.size - right.size
        return if (zeros < 1) null else left + List(zeros) { 0 } + right
    }
}
