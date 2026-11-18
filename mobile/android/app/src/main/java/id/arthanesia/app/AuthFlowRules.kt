package id.arthanesia.app

/** Shared auth validation rules: legacy passwords remain valid for sign-in. */
internal object AuthFlowRules {
    fun canSignIn(password: String): Boolean = password.isNotBlank()

    fun canSignUp(password: String): Boolean = password.length >= 8

    fun isExistingEmail(identityCount: Int?): Boolean = identityCount == 0
}
