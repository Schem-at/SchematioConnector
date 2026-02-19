package io.schemat.connector.core.dialog

import io.schemat.connector.core.api.ClickAction
import io.schemat.connector.core.api.MessageColor
import io.schemat.connector.core.api.dialog.*
import io.schemat.connector.core.validation.ValidationConstants

object DialogBuilders {

    // -- Quick Share (upload) --

    fun quickShareDialog(sizeKb: Int): DialogDefinition = DialogDefinition(
        title = DialogText("Quick Share", color = MessageColor.GOLD, bold = true),
        externalTitle = "Quick Share Configuration",
        body = listOf(
            DialogText("Share your clipboard (${sizeKb}KB) with a temporary link", color = MessageColor.GRAY)
        ),
        inputs = listOf(
            DialogInputDef.SingleOption(
                id = "expiration",
                label = DialogText("Expires in"),
                width = 200,
                options = listOf(
                    DialogInputDef.OptionEntry("3600", DialogText("1 hour")),
                    DialogInputDef.OptionEntry("86400", DialogText("24 hours"), isDefault = true),
                    DialogInputDef.OptionEntry("604800", DialogText("7 days"))
                )
            ),
            DialogInputDef.SingleOption(
                id = "limit",
                label = DialogText("Download limit"),
                width = 200,
                options = listOf(
                    DialogInputDef.OptionEntry("0", DialogText("Unlimited"), isDefault = true),
                    DialogInputDef.OptionEntry("1", DialogText("1 download")),
                    DialogInputDef.OptionEntry("10", DialogText("10 downloads"))
                )
            ),
            DialogInputDef.Text(
                id = "password",
                label = DialogText("Password (optional)"),
                width = 200,
                initial = "",
                maxLength = 50
            )
        ),
        buttons = listOf(
            DialogButtonDef(
                label = DialogText("Create Share", color = MessageColor.GREEN, bold = true),
                width = 200,
                action = DialogButtonAction.CommandTemplate("/schematio quickshare --expires={expiration} --limit={limit} --password={password} --chat")
            ),
            DialogButtonDef(
                label = DialogText("Cancel", color = MessageColor.RED),
                width = 100
            )
        )
    )

    // -- Quick Share Get (download) --

    fun quickShareGetInputDialog(): DialogDefinition = DialogDefinition(
        title = DialogText("Download Quick Share", color = MessageColor.GOLD, bold = true),
        externalTitle = "Quick Share Download",
        body = listOf(
            DialogText("Enter the access code or URL to download", color = MessageColor.GRAY)
        ),
        inputs = listOf(
            DialogInputDef.Text(
                id = "code",
                label = DialogText("Access Code or URL"),
                width = 300,
                initial = "",
                maxLength = 200
            ),
            DialogInputDef.Text(
                id = "password",
                label = DialogText("Password (if required)"),
                width = 200,
                initial = "",
                maxLength = 50
            )
        ),
        buttons = listOf(
            DialogButtonDef(
                label = DialogText("Download", color = MessageColor.GREEN, bold = true),
                width = 150,
                action = DialogButtonAction.CommandTemplate("/schematio download {code} {password} --dialog")
            ),
            DialogButtonDef(
                label = DialogText("Cancel", color = MessageColor.RED),
                width = 100
            )
        )
    )

    fun quickShareGetPasswordDialog(accessCode: String): DialogDefinition = DialogDefinition(
        title = DialogText("Password Required?", color = MessageColor.GOLD, bold = true),
        externalTitle = "Quick Share Password",
        body = listOf(
            DialogText("Enter password if the share is protected", color = MessageColor.GRAY)
        ),
        inputs = listOf(
            DialogInputDef.Text(
                id = "password",
                label = DialogText("Password (leave blank if none)"),
                width = 200,
                initial = "",
                maxLength = 50
            )
        ),
        buttons = listOf(
            DialogButtonDef(
                label = DialogText("Download", color = MessageColor.GREEN, bold = true),
                width = 150,
                action = DialogButtonAction.CommandTemplate("/schematio download $accessCode {password} --dialog")
            ),
            DialogButtonDef(
                label = DialogText("Cancel", color = MessageColor.RED),
                width = 100
            )
        )
    )

    // -- Set Token --

    fun setTokenDialog(): DialogDefinition = DialogDefinition(
        title = DialogText("Set Community Token", color = MessageColor.GOLD, bold = true),
        externalTitle = "Set Community Token",
        body = listOf(
            DialogText("Paste your JWT token from schemat.io", color = MessageColor.GRAY),
            DialogText("Get it from: Community Settings -> Plugin Tokens", color = MessageColor.DARK_GRAY)
        ),
        inputs = listOf(
            DialogInputDef.Text(
                id = "token",
                label = DialogText("JWT Token"),
                width = 400,
                initial = "",
                maxLength = 2000
            )
        ),
        buttons = listOf(
            DialogButtonDef(
                label = DialogText("Save Token", color = MessageColor.GREEN, bold = true),
                width = 150,
                action = DialogButtonAction.CommandTemplate("/schematio settoken {token}")
            ),
            DialogButtonDef(
                label = DialogText("Cancel", color = MessageColor.GRAY),
                width = 100
            )
        )
    )

    // -- Set Password --

    fun setPasswordDialog(): DialogDefinition = DialogDefinition(
        title = DialogText("Set Password", color = MessageColor.GOLD, bold = true),
        externalTitle = "Set API Password",
        body = listOf(
            DialogText("Set your schemat.io API password", color = MessageColor.GRAY),
            DialogText(
                "Password must be ${ValidationConstants.MIN_PASSWORD_LENGTH}-${ValidationConstants.MAX_PASSWORD_LENGTH} characters",
                color = MessageColor.DARK_GRAY
            )
        ),
        inputs = listOf(
            DialogInputDef.Text(
                id = "password",
                label = DialogText("New Password"),
                width = 300,
                initial = "",
                maxLength = ValidationConstants.MAX_PASSWORD_LENGTH
            ),
            DialogInputDef.Text(
                id = "confirm",
                label = DialogText("Confirm Password"),
                width = 300,
                initial = "",
                maxLength = ValidationConstants.MAX_PASSWORD_LENGTH
            )
        ),
        buttons = listOf(
            DialogButtonDef(
                label = DialogText("Set Password", color = MessageColor.GREEN, bold = true),
                width = 150,
                action = DialogButtonAction.CommandTemplate("/schematio setpassword {password} {confirm} --dialog")
            ),
            DialogButtonDef(
                label = DialogText("Cancel", color = MessageColor.GRAY),
                width = 100
            )
        )
    )

    // -- Download --

    fun downloadInputDialog(): DialogDefinition = DialogDefinition(
        title = DialogText("Download Schematic", color = MessageColor.GOLD, bold = true),
        externalTitle = "Download Schematic",
        body = listOf(
            DialogText("Enter a schematic ID, quick share code, or URL", color = MessageColor.GRAY)
        ),
        inputs = listOf(
            DialogInputDef.Text(
                id = "id",
                label = DialogText("Schematic ID, Code, or URL"),
                width = 300,
                initial = "",
                maxLength = 200
            ),
            DialogInputDef.Text(
                id = "password",
                label = DialogText("Password (if required)"),
                width = 200,
                initial = "",
                maxLength = 50
            )
        ),
        buttons = listOf(
            DialogButtonDef(
                label = DialogText("Download", color = MessageColor.GREEN, bold = true),
                width = 150,
                action = DialogButtonAction.CommandTemplate("/schematio download {id} {password} --dialog")
            ),
            DialogButtonDef(
                label = DialogText("Cancel", color = MessageColor.RED),
                width = 100
            )
        )
    )

    fun downloadSuccessDialog(schematicId: String, format: String, baseUrl: String): DialogDefinition {
        val schematicUrl = "$baseUrl/schematics/$schematicId"
        return DialogDefinition(
            title = DialogText("Download Complete!", color = MessageColor.GREEN, bold = true),
            externalTitle = "Download Complete",
            body = listOf(
                DialogText("Schematic loaded into your clipboard ($format)", color = MessageColor.WHITE),
                DialogText("Use //paste to place it in the world", color = MessageColor.GRAY)
            ),
            buttons = listOf(
                DialogButtonDef(
                    label = DialogText("View on Web", color = MessageColor.AQUA),
                    width = 120,
                    action = DialogButtonAction.StaticAction(ClickAction.OpenUrl(schematicUrl))
                ),
                DialogButtonDef(
                    label = DialogText("Close", color = MessageColor.GRAY),
                    width = 80
                )
            )
        )
    }

    // -- Settings --

    data class UIModeOption(
        val id: String,
        val displayName: String,
        val isCurrent: Boolean
    )

    fun settingsDialog(
        currentMode: String,
        isUserPref: Boolean,
        availableModes: List<UIModeOption>
    ): DialogDefinition {
        val statusText = if (isUserPref) {
            "Currently using: $currentMode (your preference)"
        } else {
            "Currently using: $currentMode (server default)"
        }

        val buttons = mutableListOf(
            DialogButtonDef(
                label = DialogText("Save", color = MessageColor.GREEN, bold = true),
                width = 100,
                action = DialogButtonAction.CommandTemplate("/schematio settings ui {ui_mode}")
            )
        )

        if (isUserPref) {
            buttons.add(
                DialogButtonDef(
                    label = DialogText("Reset", color = MessageColor.YELLOW),
                    width = 80,
                    action = DialogButtonAction.StaticAction(ClickAction.RunCommand("/schematio settings reset"))
                )
            )
        }

        buttons.add(
            DialogButtonDef(
                label = DialogText("Cancel", color = MessageColor.GRAY),
                width = 80
            )
        )

        return DialogDefinition(
            title = DialogText("Settings", color = MessageColor.GOLD, bold = true),
            externalTitle = "SchematioConnector Settings",
            body = listOf(
                DialogText("Configure your SchematioConnector preferences", color = MessageColor.GRAY),
                DialogText(statusText, color = MessageColor.WHITE)
            ),
            inputs = listOf(
                DialogInputDef.SingleOption(
                    id = "ui_mode",
                    label = DialogText("UI Mode"),
                    width = 200,
                    options = availableModes.map { mode ->
                        DialogInputDef.OptionEntry(
                            value = mode.id,
                            label = DialogText(mode.displayName),
                            isDefault = mode.isCurrent
                        )
                    }
                )
            ),
            buttons = buttons
        )
    }

    // -- Schematics List --

    fun schematicsListDialog(
        schematics: List<SchematicSummary>,
        meta: PaginationMeta,
        options: ListOptions,
        commandPrefix: String = "/schematio list"
    ): DialogDefinition {
        val filterDesc = when (options.visibility) {
            "public" -> "Public"
            "private" -> "Private"
            else -> "All"
        }
        val sortDesc = when (options.sort) {
            "created_at" -> if (options.order == "desc") "Newest First" else "Oldest First"
            "updated_at" -> "Recently Updated"
            "name" -> "Name (A-Z)"
            "downloads" -> "Most Popular"
            else -> "Newest First"
        }

        val bodyElements = mutableListOf(
            DialogText("${meta.total} schematics", color = MessageColor.WHITE)
                .append(DialogText(" \u2022 $filterDesc \u2022 $sortDesc", color = MessageColor.GRAY))
        )
        if (schematics.isEmpty()) {
            bodyElements.add(DialogText("No schematics found.", color = MessageColor.YELLOW))
        }

        val searchInitial = options.search ?: ""
        val currentVisibility = options.visibility
        val currentSort = when (options.sort) {
            "created_at" -> if (options.order == "desc") "newest" else "oldest"
            "updated_at" -> "updated"
            "name" -> "name"
            "downloads" -> "popular"
            else -> "newest"
        }

        val inputs = listOf(
            DialogInputDef.Text(
                id = "search",
                label = DialogText("Search"),
                width = 200,
                initial = searchInitial,
                maxLength = 50
            ),
            DialogInputDef.SingleOption(
                id = "visibility",
                label = DialogText("Visibility"),
                width = 200,
                options = listOf(
                    DialogInputDef.OptionEntry("all", DialogText("All"), isDefault = currentVisibility == "all"),
                    DialogInputDef.OptionEntry("public", DialogText("Public"), isDefault = currentVisibility == "public"),
                    DialogInputDef.OptionEntry("private", DialogText("Private"), isDefault = currentVisibility == "private")
                )
            ),
            DialogInputDef.SingleOption(
                id = "sort",
                label = DialogText("Sort By"),
                width = 200,
                options = listOf(
                    DialogInputDef.OptionEntry("newest", DialogText("Newest First"), isDefault = currentSort == "newest"),
                    DialogInputDef.OptionEntry("updated", DialogText("Recently Updated"), isDefault = currentSort == "updated"),
                    DialogInputDef.OptionEntry("name", DialogText("Name (A-Z)"), isDefault = currentSort == "name"),
                    DialogInputDef.OptionEntry("popular", DialogText("Most Popular"), isDefault = currentSort == "popular")
                )
            )
        )

        val buttons = mutableListOf<DialogButtonDef>()

        // Search button
        buttons.add(
            DialogButtonDef(
                label = DialogText("Search", color = MessageColor.GREEN),
                width = 200,
                action = DialogButtonAction.CommandTemplate(
                    "$commandPrefix {search} --visibility={visibility} --sort={sort} --dialog 1"
                )
            )
        )

        // Per-schematic buttons
        for (schematic in schematics) {
            val visibilityDot = if (schematic.isPublic) "\u2022 " else "\u25CB "
            val truncatedName = if (schematic.name.length > 28) {
                schematic.name.take(28) + "..."
            } else {
                schematic.name
            }
            val authorSuffix = schematic.authorName?.let { " by $it" } ?: ""
            val buttonLabel = "$visibilityDot$truncatedName$authorSuffix"

            val tooltipText = buildString {
                append(schematic.name)
                append("\n${schematic.width}x${schematic.height}x${schematic.length}")
                append("\nDownloads: ${schematic.downloadCount}")
                if (schematic.authorName != null) append("\nBy: ${schematic.authorName}")
            }

            buttons.add(
                DialogButtonDef(
                    label = DialogText(buttonLabel),
                    tooltip = DialogText(tooltipText),
                    width = 280,
                    action = DialogButtonAction.StaticAction(
                        ClickAction.RunCommand("/schematio download ${schematic.shortId}")
                    )
                )
            )
        }

        // Pagination
        val baseCmd = buildDialogCommand(commandPrefix, options)
        if (meta.currentPage > 1) {
            buttons.add(
                DialogButtonDef(
                    label = DialogText("\u25C0 Previous", color = MessageColor.AQUA),
                    width = 90,
                    action = DialogButtonAction.StaticAction(
                        ClickAction.RunCommand("$baseCmd ${meta.currentPage - 1}")
                    )
                )
            )
        }

        buttons.add(
            DialogButtonDef(
                label = DialogText("Page ${meta.currentPage} of ${meta.lastPage}", color = MessageColor.GRAY),
                width = 100,
                action = DialogButtonAction.StaticAction(
                    ClickAction.SuggestCommand("$baseCmd ")
                )
            )
        )

        if (meta.currentPage < meta.lastPage) {
            buttons.add(
                DialogButtonDef(
                    label = DialogText("Next \u25B6", color = MessageColor.AQUA),
                    width = 90,
                    action = DialogButtonAction.StaticAction(
                        ClickAction.RunCommand("$baseCmd ${meta.currentPage + 1}")
                    )
                )
            )
        }

        return DialogDefinition(
            title = DialogText("Schematics", color = MessageColor.GOLD, bold = true),
            externalTitle = "Browse Schematics",
            body = bodyElements,
            inputs = inputs,
            buttons = buttons
        )
    }

    private fun buildDialogCommand(commandPrefix: String, options: ListOptions): String {
        return buildString {
            append(commandPrefix)
            if (!options.search.isNullOrBlank()) append(" ${options.search}")
            append(" --visibility=${options.visibility}")
            append(" --sort=${options.sort}")
            append(" --dialog")
        }
    }
}
