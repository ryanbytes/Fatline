package dev.scanrelay.app.net

import org.junit.Assert.assertEquals
import org.junit.Test

class ThinLineSocketTest {
    @Test fun httpServerUrlUsesRootWebsocketEndpoint() {
        assertEquals(
            "wss://scanner.example.com/",
            ThinLineSocket.webSocketUrl("https://scanner.example.com/verify?foo=bar")
        )
    }

    @Test fun cleartextServerUrlPreservesPortAtRoot() {
        assertEquals(
            "ws://192.0.2.10:3000/",
            ThinLineSocket.webSocketUrl("http://192.0.2.10:3000/some/page")
        )
    }

    @Test fun bareHostnameDefaultsToSecureWebsocket() {
        assertEquals(
            "wss://scanner.example.com/",
            ThinLineSocket.webSocketUrl("scanner.example.com")
        )
    }
}
