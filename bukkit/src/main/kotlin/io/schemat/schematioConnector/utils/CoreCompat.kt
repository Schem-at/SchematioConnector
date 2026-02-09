@file:Suppress("UNUSED")
package io.schemat.schematioConnector.utils

// Re-export core classes for backward compatibility with existing imports
// This allows existing code to continue using io.schemat.schematioConnector.utils.* imports

// Validation
typealias ValidationConstants = io.schemat.connector.core.validation.ValidationConstants
typealias ValidationResult<T> = io.schemat.connector.core.validation.ValidationResult<T>
typealias InputValidator = io.schemat.connector.core.validation.InputValidator

// Cache
typealias SchematicCache = io.schemat.connector.core.cache.SchematicCache
typealias RateLimiter = io.schemat.connector.core.cache.RateLimiter

// Offline
typealias OfflineMode = io.schemat.connector.core.offline.OfflineMode

// HTTP
typealias HttpUtil = io.schemat.connector.core.http.HttpUtil

// JSON extensions - need to re-export as functions
// These are imported as extension functions so we need to re-export them
