package io.schemat.connector.fabric.client.ui.panels

/**
 * The Browse panel's top-level scope. [All] and [Mine] are fixed; [Community] is one
 * per community the player belongs to (chosen from the Communities dropdown). Maps 1:1
 * onto the existing [SchematicListView.Context] query contexts.
 */
sealed class BrowseScope {
    object All : BrowseScope()
    object Mine : BrowseScope()
    data class Community(val slug: String, val name: String) : BrowseScope()

    companion object {
        fun toContext(scope: BrowseScope): SchematicListView.Context = when (scope) {
            All -> SchematicListView.Context.Public
            Mine -> SchematicListView.Context.Mine
            is Community -> SchematicListView.Context.Community(scope.slug, scope.name)
        }
    }
}
