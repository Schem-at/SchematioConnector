package io.schemat.connector.core.dialog

data class SchematicSummary(
    val shortId: String,
    val name: String,
    val isPublic: Boolean,
    val downloadCount: Int,
    val width: Int,
    val height: Int,
    val length: Int,
    val authorName: String?
)

data class PaginationMeta(
    val currentPage: Int,
    val lastPage: Int,
    val total: Int
)

data class ListOptions(
    val search: String?,
    val visibility: String,
    val sort: String,
    val order: String
)
