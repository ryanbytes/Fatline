package dev.scanrelay.app.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerEndpointPolicyTest {
    @Test fun blocksThinLineHostedDomainsAndSubdomains() {
        assertTrue(ScannerEndpointPolicy.isBlockedHost("app.thinlineradio.com"))
        assertTrue(ScannerEndpointPolicy.isBlockedHost("api.thinlineradio.com"))
        assertTrue(ScannerEndpointPolicy.isBlockedHost("thinlineradio.com"))
        assertTrue(ScannerEndpointPolicy.isBlockedHost("APP.THINLINEradio.com."))
    }

    @Test fun blocksVendorServiceUrlsBeforeConnecting() {
        assertTrue(ScannerEndpointPolicy.isBlockedUrl("https://app.thinlineradio.com"))
        assertTrue(ScannerEndpointPolicy.isBlockedUrl("wss://app.thinlineradio.com/api/ws"))
        assertFalse(ScannerEndpointPolicy.isBlockedUrl("https://scanner.example.net"))
    }

    @Test fun allowsUserOwnedScannerHosts() {
        assertFalse(ScannerEndpointPolicy.isBlockedHost("scanner.example.net"))
        assertFalse(ScannerEndpointPolicy.isBlockedHost("thinlineradio.com.attacker.example"))
        assertFalse(ScannerEndpointPolicy.isBlockedHost("localhost"))
    }
}
