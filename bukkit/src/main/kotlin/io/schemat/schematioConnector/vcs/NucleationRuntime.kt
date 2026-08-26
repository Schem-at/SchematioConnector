package io.schemat.schematioConnector.vcs

import com.github.schemat.nucleation.Nucleation
import java.util.logging.Logger

/**
 * Lazy gate around the bundled Nucleation native library.
 *
 * The Nucleation fat JAR embeds per-platform natives (currently macOS-arm64; see
 * docs/nucleation-build.md) that Nucleation's own NativeLoader extracts and loads on
 * first class use. On platforms without a bundled native that load throws, so every
 * diff feature must check [available] first and degrade gracefully — up/download and
 * all non-diff features keep working regardless.
 *
 * Deliberately free of Bukkit types so pure-JVM tests can consult it.
 */
object NucleationRuntime {

    /** User-facing message shown when a diff feature is invoked but the native is missing. */
    const val UNAVAILABLE_MESSAGE = "Schematic diff is not available on this server platform."

    @Volatile
    private var loadFailure: Throwable? = null

    /**
     * True when the Nucleation native loaded successfully. The first access attempts the
     * load; failures are cached (JNI load failures are not retryable within a JVM).
     */
    val available: Boolean by lazy {
        try {
            // Any static touch of a Nucleation class triggers NativeLoader.loadOnce().
            Nucleation.version()
            true
        } catch (t: Throwable) {
            loadFailure = t
            false
        }
    }

    /** Version string of the loaded native, or null when unavailable. */
    val version: String?
        get() = if (available) Nucleation.version() else null

    /**
     * Logs the runtime state once at startup: version on success, a warning with the
     * load failure otherwise.
     */
    fun logStatus(logger: Logger) {
        if (available) {
            logger.info("Nucleation ${version} loaded - in-game schematic diff enabled")
        } else {
            logger.warning(
                "Nucleation native failed to load - diff features disabled on this platform: " +
                    (loadFailure?.message ?: "unknown error"),
            )
        }
    }
}
