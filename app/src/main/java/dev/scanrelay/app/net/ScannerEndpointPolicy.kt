package dev.scanrelay.app.net

internal object ScannerEndpointPolicy {
    private const val VENDOR_DOMAIN = "thinlineradio.com"

    fun isBlockedHost(host: String): Boolean {
        val normalized = host.trim().trimEnd('.').lowercase()
        return normalized == VENDOR_DOMAIN || normalized.endsWith(".$VENDOR_DOMAIN")
    }

    fun isBlockedUrl(url: String): Boolean {
        val normalized = url.trim().let {
            if (it.contains("://")) it else "https://$it"
        }
        val host = runCatching { java.net.URI(normalized).host }.getOrNull() ?: return false
        return isBlockedHost(host)
    }

    fun requireAllowedHost(host: String) {
        require(!isBlockedHost(host)) {
            "ThinLine Radio hosted endpoints are blocked for privacy"
        }
    }
}
