package io.schemat.axiom;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Axiom-specific classes are loaded only after the optional dependency has been checked. */
public final class SchematioAxiomClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("schematio-axiom");

    @Override public void onInitializeClient() {
        if (FabricLoader.getInstance().isModLoaded("schematioconnector")) {
            LOG.info("Connector owns the Axiom integration; standalone initialization skipped");
            return;
        }
        var axiom = FabricLoader.getInstance().getModContainer("axiom");
        if (axiom.isEmpty()) {
            LOG.info("Axiom is absent; Schematio's Axiom integration is inactive");
            return;
        }
        String version = axiom.get().getMetadata().getVersion().getFriendlyString();
        if (!version.equals("6.0.5")) {
            LOG.warn("Axiom {} is outside this proof of concept's tested contract (6.0.5); integration inactive", version);
            return;
        }
        try {
            Class.forName("io.schemat.axiom.AxiomLibraryTool").getMethod("register").invoke(null);
            LOG.info("Registered Schematio inside Axiom 6.0.5; no overlay or input hooks installed");
        } catch (ReflectiveOperationException | LinkageError e) {
            LOG.error("Axiom integration could not initialize; Axiom remains available", e);
        }
    }
}
