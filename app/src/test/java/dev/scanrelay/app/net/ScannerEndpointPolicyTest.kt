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
        assertTrue(ScannerEndpointPolicy.isBlockedHost("thinlineds.com"))
        assertTrue(ScannerEndpointPolicy.isBlockedHost("relay.thinlineds.com"))
        assertTrue(ScannerEndpointPolicy.isBlockedHost("TRANSCRIPTS.THINLINEDS.COM."))
    }

    @Test fun blocksVendorServiceUrlsBeforeConnecting() {
        assertTrue(ScannerEndpointPolicy.isBlockedUrl("https://app.thinlineradio.com"))
        assertTrue(ScannerEndpointPolicy.isBlockedUrl("wss://app.thinlineradio.com/api/ws"))
        assertTrue(ScannerEndpointPolicy.isBlockedUrl("https://relay.thinlineds.com/api/mobile-app/listener-scanners"))
        assertFalse(ScannerEndpointPolicy.isBlockedUrl("https://scanner.example.net"))
    }

    @Test fun allowsUserOwnedScannerHosts() {
        assertFalse(ScannerEndpointPolicy.isBlockedHost("scanner.example.net"))
        assertFalse(ScannerEndpointPolicy.isBlockedHost("thinlineradio.com.attacker.example"))
        assertFalse(ScannerEndpointPolicy.isBlockedHost("thinlineds.com.attacker.example"))
        assertFalse(ScannerEndpointPolicy.isBlockedHost("scanner.local.thinlineds.example"))
        assertFalse(ScannerEndpointPolicy.isBlockedHost("localhost"))
    }
}
