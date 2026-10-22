package io.schemat.connector.core.routing

import io.schemat.connector.core.ipc.IpcPlatform
import io.schemat.connector.core.validation.InputValidator
import io.schemat.connector.core.validation.ValidationResult

enum class DownloadKind { SCHEMATIC, QUICK_SHARE }

/** Builds the `/schematio ...` command (without the leading slash) forwarded to a server plugin. */
object ServerDownloadCommand {

    /** A null [platform] (pre-v2 hello) is treated as Paper. */
    fun build(platform: IpcPlatform?, kind: DownloadKind, id: String, password: String?): String {
        if (kind == DownloadKind.SCHEMATIC) return "schematio download $id"

        if (platform == IpcPlatform.FABRIC_SERVER) {
            val code = (InputValidator.validateQuickShareCode(id) as? ValidationResult.Valid)?.value ?: id
            val prefixed = if (code.startsWith("qs_")) code else "qs_$code"
            return "schematio download $prefixed"
        }
        return buildString {
            append("schematio quickshareget ").append(id)
            if (!password.isNullOrEmpty()) append(' ').append(password)
        }
    }
}
