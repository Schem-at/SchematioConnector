package io.schemat.connector.fabric.client.ipc

import io.schemat.connector.core.ipc.HelloServer

/** Per-connection state about the server's Schematio plugin. Reset on join/disconnect. */
object ServerSession {
    @Volatile var pluginPresent: Boolean = false
        private set
    @Volatile var pluginVersion: String? = null
        private set
    @Volatile var protocolVersion: Int = 0
        private set
    @Volatile var capabilities: Int = 0
        private set

    /** Whether we've already announced ourselves to the server this connection. */
    @Volatile private var helloSent: Boolean = false

    fun adopt(hello: HelloServer) {
        pluginVersion = hello.pluginVersion
        protocolVersion = hello.protocolVersion
        capabilities = hello.capabilities
        pluginPresent = true
    }

    /**
     * Marks the client HELLO as sent; returns true only the first time per connection.
     * Both the join-fallback and the reply-to-HELLO_SERVER paths call this, so it keeps
     * us to a single HELLO_CLIENT on the wire when both fire (the common case).
     */
    fun markHelloSent(): Boolean = if (helloSent) false else { helloSent = true; true }

    fun reset() {
        pluginPresent = false
        pluginVersion = null
        protocolVersion = 0
        capabilities = 0
        helloSent = false
    }
}
