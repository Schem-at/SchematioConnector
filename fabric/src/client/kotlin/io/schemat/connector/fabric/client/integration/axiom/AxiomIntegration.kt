package io.schemat.connector.fabric.client.integration.axiom

import io.schemat.connector.fabric.client.services.ClientServices
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory

object AxiomIntegration {
    var available = false
        private set
    fun initialize(services: ClientServices) {
        val loader = FabricLoader.getInstance()
        val axiom = loader.getModContainer("axiom").orElse(null) ?: return
        val minecraft = loader.getModContainer("minecraft").orElseThrow().metadata.version.friendlyString
        if (minecraft !in setOf("1.21.8", "1.21.9", "1.21.10", "1.21.11", "26.1", "26.2") || axiom.metadata.version.friendlyString != "6.0.5") return
        try {
            Class.forName("io.schemat.connector.fabric.client.integration.axiom.ConnectorAxiom")
                .getMethod("register", ClientServices::class.java).invoke(null, services)
            available = true
        } catch (e: ReflectiveOperationException) {
            LoggerFactory.getLogger("schematioconnector").warn("Axiom integration unavailable", e)
        } catch (e: LinkageError) {
            LoggerFactory.getLogger("schematioconnector").warn("Axiom contract changed; integration disabled", e)
        }
    }
}
