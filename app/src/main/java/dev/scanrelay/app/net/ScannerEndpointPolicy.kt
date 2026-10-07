package dev.scanrelay.app.net

internal object ScannerEndpointPolicy {
    private const val VENDOR_DOMAIN = "thinlineradio.com"

    fun isBlockedHost(host: String): Boolean {
        val normalized = host.trim().trimEnd('.').lowercase()
        return normalized == VENDOR_DOMAIN || normalized.endsWith(".$VENDOR_DOMAIN")
    }

    fun requireAllowedHost(host: String) {
        require(!isBlockedHost(host)) {
            "ThinLine Radio hosted endpoints are blocked for privacy"
        }
    }
}
