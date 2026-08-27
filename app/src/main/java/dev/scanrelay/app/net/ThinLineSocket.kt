package dev.scanrelay.app.net

import dev.scanrelay.app.model.ServerProfile
import dev.scanrelay.app.model.SystemConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ThinLineSocket(
    private val profile: ServerProfile,
    private val listener: Listener
) {
    interface Listener {
        fun onOpen()
        fun onEnvelope(envelope: ThinLineProtocol.Envelope)
        fun onFailure(message: String, cause: Throwable? = null)
        fun onClosed(reason: String)
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private var socket: WebSocket? = null
    private val shutdown = AtomicBoolean(false)
    private val savedPinAttempted = AtomicBoolean(false)

    fun connect() {
        val request = Request.Builder().url(webSocketUrl(profile.baseUrl)).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                savedPinAttempted.set(false)
                listener.onOpen()
                // Match ThinLine's public client: negotiate version, then request config.
                // Protected servers answer CFG with PIN; only then do we submit a saved PIN.
                webSocket.send(ThinLineProtocol.command(ThinLineProtocol.VERSION))
                webSocket.send(ThinLineProtocol.command(ThinLineProtocol.CONFIG))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { ThinLineProtocol.parseEnvelope(text) }
                    .onSuccess { envelope ->
                        if (
                            envelope.command == ThinLineProtocol.PIN &&
                            profile.pin.isNotBlank() &&
                            savedPinAttempted.compareAndSet(false, true)
                        ) {
                            if (!webSocket.send(ThinLineProtocol.pin(profile.pin))) {
                                listener.onFailure("Failed to submit saved PIN")
                            }
                        } else {
                            listener.onEnvelope(envelope)
                        }
                    }
                    .onFailure {
                        listener.onFailure("Invalid server message", it)
                        webSocket.close(1002, "Invalid protocol message")
                    }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onFailure(response?.let { "WebSocket HTTP ${it.code}" } ?: t.message ?: "WebSocket failure", t)
                shutdownClient()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onClosed(if (reason.isBlank()) "Closed ($code)" else reason)
                shutdownClient()
            }
        })
    }

    fun sendLivefeed(systems: List<SystemConfig>): Boolean = send(ThinLineProtocol.livefeed(systems))
    fun requestConfig(): Boolean = send(ThinLineProtocol.command(ThinLineProtocol.CONFIG))
    fun requestHistory(limit: Int, offset: Int, systemRef: Long?, talkgroups: Collection<Long>): Boolean =
        send(ThinLineProtocol.listCalls(limit, offset, -1, systemRef, talkgroups))
    fun requestCall(callId: Long, download: Boolean = false): Boolean = send(ThinLineProtocol.call(callId, download))
    fun send(text: String): Boolean = socket?.send(text) ?: false

    fun close() {
        socket?.close(1000, "Client disconnect")
        socket = null
        shutdownClient()
    }

    private fun shutdownClient() {
        if (!shutdown.compareAndSet(false, true)) return
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    companion object {
        /** ThinLine's listener WebSocket is served at the server origin/root. */
        internal fun webSocketUrl(baseUrl: String): String {
            val normalized = baseUrl.trim().let {
                if (it.startsWith("http://") || it.startsWith("https://") || it.startsWith("ws://") || it.startsWith("wss://")) it
                else "https://$it"
            }
            val uri = URI(normalized)
            val scheme = when (uri.scheme?.lowercase()) {
                "http" -> "ws"
                "https" -> "wss"
                "ws", "wss" -> uri.scheme.lowercase()
                else -> error("Unsupported server URL scheme: ${uri.scheme}")
            }
            require(!uri.host.isNullOrBlank()) { "Server URL must include a host" }
            return URI(scheme, uri.userInfo, uri.host, uri.port, "/", null, null).toString()
        }
    }
}
