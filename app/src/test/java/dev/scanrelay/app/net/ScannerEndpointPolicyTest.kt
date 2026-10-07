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

    @Test fun allowsUserOwnedScannerHosts() {
        assertFalse(ScannerEndpointPolicy.isBlockedHost("scanner.example.net"))
        assertFalse(ScannerEndpointPolicy.isBlockedHost("thinlineradio.com.attacker.example"))
        assertFalse(ScannerEndpointPolicy.isBlockedHost("localhost"))
    }
}
