package dev.scanrelay.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountLoginPolicyTest {
    @Test fun detectsServerRequestedPasswordReset() {
        assertTrue(AccountLoginPolicy.requiresForcedPasswordReset(
            JSONObject("""{"user":{"needsPasswordReset":true}}""")
        ))
        assertFalse(AccountLoginPolicy.requiresForcedPasswordReset(
            JSONObject("""{"user":{"needsPasswordReset":false}}""")
        ))
        assertFalse(AccountLoginPolicy.requiresForcedPasswordReset(JSONObject("""{"user":{}}""")))
    }

    @Test fun validatesPasswordsUsingServerRequirements() {
        assertNull(AccountLoginPolicy.passwordValidationError("Strongpass1"))
        assertEquals("Use at least 8 characters", AccountLoginPolicy.passwordValidationError("Aa1!"))
        assertEquals("Add an uppercase letter", AccountLoginPolicy.passwordValidationError("strongpass1"))
        assertEquals("Add a lowercase letter", AccountLoginPolicy.passwordValidationError("STRONGPASS1"))
        assertEquals("Add a number", AccountLoginPolicy.passwordValidationError("Strongpass"))
    }
}
