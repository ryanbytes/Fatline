package dev.scanrelay.app

import org.json.JSONObject

object AccountLoginPolicy {
    fun usesCentralManagementAuthentication(settings: JSONObject?): Boolean =
        settings?.optBoolean("centralManagementEnabled", false) == true

    fun requiresForcedPasswordReset(loginResponse: JSONObject): Boolean =
        loginResponse.optJSONObject("user")?.optBoolean("needsPasswordReset", false) == true

    fun passwordValidationError(password: String): String? = when {
        password.length < 8 -> "Use at least 8 characters"
        password.length > 128 -> "Use 128 characters or fewer"
        password.none(Char::isUpperCase) -> "Add an uppercase letter"
        password.none(Char::isLowerCase) -> "Add a lowercase letter"
        password.none(Char::isDigit) -> "Add a number"
        else -> null
    }
}
