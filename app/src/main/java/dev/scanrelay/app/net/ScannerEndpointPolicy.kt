package dev.scanrelay.app.net

internal object ScannerEndpointPolicy {
    // Vendor-owned endpoints, including the hosted listener directory and relay services.
    // Self-hosted scanner operators using other domains remain allowed.
    private val VENDOR_DOMAINS = setOf("thinlineradio.com", "thinlineds.com")

    fun isBlockedHost(host: String): Boolean {
        val normalized = host.trim().trimEnd('.').lowercase()
        return VENDOR_DOMAINS.any { domain ->
            normalized == domain || normalized.endsWith(".$domain")
        }
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
