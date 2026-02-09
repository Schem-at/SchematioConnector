@file:Suppress("UNUSED")
package io.schemat.schematioConnector.utils

// Re-export SchematicsApiService and related classes from core for backward compatibility

typealias SchematicsApiService = io.schemat.connector.core.service.SchematicsApiService

// Inner classes/enums from SchematicsApiService
typealias FetchResult = io.schemat.connector.core.service.SchematicsApiService.FetchResult
typealias QueryOptions = io.schemat.connector.core.service.SchematicsApiService.QueryOptions
typealias Visibility = io.schemat.connector.core.service.SchematicsApiService.Visibility
typealias SortField = io.schemat.connector.core.service.SchematicsApiService.SortField
typealias SortOrder = io.schemat.connector.core.service.SchematicsApiService.SortOrder
typealias ErrorCategory = io.schemat.connector.core.service.SchematicsApiService.ErrorCategory
