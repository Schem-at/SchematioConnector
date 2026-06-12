package io.schemat.schematioConnector

import com.comphenix.protocol.PacketType
import com.comphenix.protocol.ProtocolLibrary
import com.comphenix.protocol.events.PacketAdapter
import com.comphenix.protocol.events.PacketEvent
import org.bukkit.plugin.java.JavaPlugin
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.MessageDigest
import java.security.PrivateKey
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger
import javax.crypto.Cipher

/**
 * Handles ProtocolLib packet interception for auth features.
 * This class is only loaded when ProtocolLib is available.
 */
class ProtocolLibHandler(
    private val plugin: JavaPlugin,
    private val logger: Logger
) {
    
    data class AuthSession(
        val publicKey: ByteArray,
        var serverIdHash: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    private val authSessions = ConcurrentHashMap<String, AuthSession>()
    private var serverPrivateKey: PrivateKey? = null
    
    fun initialize() {
        findServerPrivateKey()
        setupPacketInterception()
    }
    
    private fun findServerPrivateKey() {
        try {
            val serverInstance = plugin.server
            val craftServer = serverInstance.javaClass
            val getServerMethod = craftServer.getMethod("getServer")
            val minecraftServer = getServerMethod.invoke(serverInstance)
            val getKeyPairMethod = minecraftServer.javaClass.getMethod("getKeyPair")
            val keyPair = getKeyPairMethod.invoke(minecraftServer) as KeyPair
            serverPrivateKey = keyPair.private
        } catch (e: Exception) {
            logger.severe("Failed to get server private key: ${e.message}")
        }
    }
    
    private fun setupPacketInterception() {
        if (serverPrivateKey == null) {
            return
        }
        
        val pm = ProtocolLibrary.getProtocolManager()
        
        // Capture server's public key
        pm.addPacketListener(
            object : PacketAdapter(plugin, PacketType.Login.Server.ENCRYPTION_BEGIN) {
                override fun onPacketSending(event: PacketEvent) {
                    try {
                        val packet = event.packet
                        val connectionKey = getConnectionKey(event)
                        val publicKey = packet.byteArrays.read(0)
                        authSessions[connectionKey] = AuthSession(publicKey)
                    } catch (e: Exception) {
                        // Silent fail
                    }
                }
            }
        )

        // Capture and decrypt shared secret
        pm.addPacketListener(
            object : PacketAdapter(plugin, PacketType.Login.Client.ENCRYPTION_BEGIN) {
                override fun onPacketReceiving(event: PacketEvent) {
                    try {
                        val packet = event.packet
                        val connectionKey = getConnectionKey(event)
                        val session = authSessions[connectionKey] ?: return

                        val encryptedSharedSecret = packet.byteArrays.read(0)
                        val sharedSecret = decryptSharedSecret(encryptedSharedSecret)

                        if (sharedSecret != null) {
                            val serverIdHash = computeServerIdHash("", sharedSecret, session.publicKey)
                            authSessions[connectionKey] = session.copy(serverIdHash = serverIdHash)
                        }
                    } catch (e: Exception) {
                        // Silent fail
                    }
                }
            }
        )
    }
    
    private fun getConnectionKey(event: PacketEvent): String {
        return try {
            val player = event.player
            if (player?.address != null) {
                "${player.address?.address?.hostAddress}:${player.address?.port}"
            } else {
                "conn_${System.currentTimeMillis()}_${event.hashCode()}"
            }
        } catch (e: Exception) {
            "unknown_${System.currentTimeMillis()}"
        }
    }
    
    private fun decryptSharedSecret(encryptedSecret: ByteArray): ByteArray? {
        return try {
            val privateKey = serverPrivateKey ?: return null
            val cipher = Cipher.getInstance("RSA")
            cipher.init(Cipher.DECRYPT_MODE, privateKey)
            cipher.doFinal(encryptedSecret)
        } catch (e: Exception) {
            null
        }
    }
    
    private fun computeServerIdHash(serverId: String, sharedSecret: ByteArray, publicKey: ByteArray): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-1")
            digest.update(serverId.toByteArray(StandardCharsets.ISO_8859_1))
            digest.update(sharedSecret)
            digest.update(publicKey)

            val hash = digest.digest()
            val bigInt = BigInteger(hash)

            if (bigInt.signum() < 0) {
                "-" + bigInt.negate().toString(16)
            } else {
                bigInt.toString(16)
            }
        } catch (e: Exception) {
            "ERROR"
        }
    }
    
    /**
     * Get the server ID hash for a recent connection (for verification)
     */
    fun getRecentAuthHash(): String? {
        return authSessions.values
            .filter { System.currentTimeMillis() - it.timestamp < 10000 }
            .maxByOrNull { it.timestamp }
            ?.serverIdHash
    }
    
    fun clear() {
        authSessions.clear()
    }
}
