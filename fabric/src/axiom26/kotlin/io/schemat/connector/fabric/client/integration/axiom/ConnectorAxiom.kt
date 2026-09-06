package io.schemat.connector.fabric.client.integration.axiom

import io.schemat.axiom.AxiomLibraryTool
import io.schemat.axiom.LibraryClient
import io.schemat.connector.core.modapi.ApiResult
import io.schemat.connector.core.modapi.BrowseQuery
import io.schemat.connector.fabric.client.services.ClientServices
import io.schemat.connector.fabric.client.ui.foundation.toUserMessage
import kotlinx.coroutines.runBlocking
import java.io.IOException

/** Called only after checking the optional Axiom version. No separate UI or HTTP stack. */
object ConnectorAxiom {
    @JvmStatic fun register(services: ClientServices) {
        AxiomLibraryTool.register(object : LibraryClient {
            override fun search(query: String, page: Int): LibraryClient.Page = runBlocking {
                when (val result = services.cached.schematics(BrowseQuery(search = query, page = page, perPage = 8))) {
                    is ApiResult.Success -> result.value.value.let { response ->
                        LibraryClient.Page(response.items.map { build ->
                            LibraryClient.Build(build.id, build.name, build.authors.firstOrNull()?.lastSeenName ?: "", build.shortId ?: build.id)
                        }, response.meta.currentPage, response.meta.lastPage, response.meta.total)
                    }
                    is ApiResult.Failure -> throw IOException(result.error.toUserMessage())
                }
            }

            override fun download(id: String): ByteArray = runBlocking {
                when (val result = services.api.download(id, "schem")) {
                    is ApiResult.Success -> result.value.also {
                        if (it.size > 16 * 1024 * 1024) throw IOException("Axiom imports are limited to 16 MiB.")
                    }
                    is ApiResult.Failure -> throw IOException(result.error.toUserMessage())
                }
            }
        })
    }
}
