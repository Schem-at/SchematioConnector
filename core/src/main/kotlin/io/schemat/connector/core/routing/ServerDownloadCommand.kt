package io.schemat.connector.core.routing

import io.schemat.connector.core.ipc.IpcPlatform
import io.schemat.connector.core.validation.InputValidator
import io.schemat.connector.core.validation.ValidationResult

enum class DownloadKind { SCHEMATIC, QUICK_SHARE }

/** Builds the `/schematio ...` command (without the leading slash) forwarded to a server plugin. */
object ServerDownloadCommand {

    /** A null [platform] (pre-v2 hello) is treated as Paper. */
    fun build(platform: IpcPlatform?, kind: DownloadKind, id: String, password: String?): String {
        if (kind == DownloadKind.SCHEMATIC) {
            if (platform == IpcPlatform.FABRIC_SERVER) return "schematio download $id"
            // Paper: a bare `download <id>` (2 args) is the plugin's command-ownership
            // SCHEMATIC_DETAIL handoff, which would bounce the forwarded download back to
            // the client UI. The explicit format (released plugins accept `schem` as the
            // 2nd positional) makes it 3 args, so the plugin always runs the real download.
            return "schematio download $id schem"
        }

        if (platform == IpcPlatform.FABRIC_SERVER) {
            val result = InputValidator.validateQuickShareCode(id)
            require(result is ValidationResult.Valid) { "Invalid quick share code: $id" }
            val code = result.value
            return buildString {
                append("schematio download ").append(if (code.startsWith("qs_")) code else "qs_$code")
                if (!password.isNullOrEmpty()) append(' ').append(password)
            }
        }
        return buildString {
            append("schematio quickshareget ").append(id)
            if (!password.isNullOrEmpty()) append(' ').append(password)
        }
    }
}
