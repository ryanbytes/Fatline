package dev.scanrelay.app.model

import org.json.JSONObject

data class AccountProfile(
    val displayName: String,
    val email: String,
    val groupName: String,
    val verified: Boolean,
    val subscriptionStatus: String,
    val billingRequired: Boolean,
    val hasBilling: Boolean,
    val isGroupAdmin: Boolean,
    val isSystemAdmin: Boolean
)

internal fun parseAccountProfile(json: JSONObject): AccountProfile {
    val displayName = listOf(
        json.optString("firstName", "").trim(),
        json.optString("lastName", "").trim()
    ).filter(String::isNotBlank).joinToString(" ")

    return AccountProfile(
        displayName = displayName,
        email = json.optString("email", "").trim(),
        groupName = json.optString("userGroupName", "").trim(),
        verified = json.optBoolean("verified", false),
        subscriptionStatus = json.optString("subscriptionStatusDisplay",
            json.optString("subscriptionStatus", ""))
            .trim()
            .replace('_', ' '),
        billingRequired = json.optBoolean("billingRequired", false),
        hasBilling = json.optBoolean("hasBilling", false),
        isGroupAdmin = json.optBoolean("isGroupAdmin", false),
        isSystemAdmin = json.optBoolean("systemAdmin", false)
    )
}
