package io.schemat.connector.core.auth

sealed class AuthResult {
    data class Success(val jwt: String) : AuthResult()
    data class Failure(val reason: String) : AuthResult()
}
