package com.example.lxb_ignition.service

class CoreClientGateway(val coreHost: String = DEFAULT_CORE_HOST) {

    companion object {
        const val DEFAULT_CORE_HOST = "127.0.0.1"
    }

    val host: String
        get() = coreHost

    inline fun <T> withClient(
        port: Int,
        connectTimeoutMs: Int = 3000,
        handshakeTimeoutMs: Int? = 3000,
        tolerateHandshakeFailure: Boolean = true,
        block: (LocalLinkClient) -> T
    ): T {
        return LocalLinkClient(coreHost, port, connectTimeoutMs).use { client ->
            if (handshakeTimeoutMs != null) {
                if (tolerateHandshakeFailure) {
                    runCatching { client.handshake(handshakeTimeoutMs) }
                } else {
                    client.handshake(handshakeTimeoutMs)
                }
            }
            block(client)
        }
    }

    fun probeHandshakeReady(port: Int, timeoutMs: Int): Boolean {
        return runCatching {
            withClient(
                port = port,
                connectTimeoutMs = timeoutMs,
                handshakeTimeoutMs = timeoutMs,
                tolerateHandshakeFailure = false
            ) {
                true
            }
            true
        }.getOrDefault(false)
    }
}
