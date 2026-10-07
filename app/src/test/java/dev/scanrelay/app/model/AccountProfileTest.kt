package dev.scanrelay.app.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountProfileTest {
    @Test
    fun parsesAccountIdentityAndAccessSummary() {
        val account = parseAccountProfile(
            JSONObject()
                .put("firstName", "Ryan")
                .put("lastName", "Stoner")
                .put("email", "ryan@example.com")
                .put("userGroupName", "Wabash")
                .put("verified", true)
                .put("subscriptionStatusDisplay", "group_admin_managed")
                .put("billingRequired", false)
                .put("hasBilling", true)
                .put("isGroupAdmin", false)
                .put("systemAdmin", true)
        )

        assertEquals("Ryan Stoner", account.displayName)
        assertEquals("ryan@example.com", account.email)
        assertEquals("Wabash", account.groupName)
        assertTrue(account.verified)
        assertEquals("group admin managed", account.subscriptionStatus)
        assertFalse(account.billingRequired)
        assertTrue(account.hasBilling)
        assertFalse(account.isGroupAdmin)
        assertTrue(account.isSystemAdmin)
    }

    @Test
    fun missingAccountFieldsHaveSafeDefaults() {
        val account = parseAccountProfile(JSONObject())

        assertEquals("", account.displayName)
        assertEquals("", account.email)
        assertEquals("", account.groupName)
        assertFalse(account.verified)
        assertEquals("", account.subscriptionStatus)
        assertFalse(account.billingRequired)
        assertFalse(account.hasBilling)
        assertFalse(account.isGroupAdmin)
        assertFalse(account.isSystemAdmin)
    }
}
