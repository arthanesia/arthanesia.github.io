package id.arthanesia.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthFlowRulesTest {
    @Test
    fun legacyPasswordCanStillSignIn() {
        assertTrue(AuthFlowRules.canSignIn("12345"))
        assertTrue(AuthFlowRules.canSignIn("older-password"))
        assertFalse(AuthFlowRules.canSignIn(""))
    }

    @Test
    fun newSignupStillRequiresEightCharacters() {
        assertFalse(AuthFlowRules.canSignUp("1234567"))
        assertTrue(AuthFlowRules.canSignUp("12345678"))
    }

    @Test
    fun onlyEmptyIdentityArraySignalsExistingEmail() {
        assertTrue(AuthFlowRules.isExistingEmail(0))
        assertFalse(AuthFlowRules.isExistingEmail(1))
        assertFalse(AuthFlowRules.isExistingEmail(null))
    }
}
