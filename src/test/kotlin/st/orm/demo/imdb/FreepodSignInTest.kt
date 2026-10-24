package st.orm.demo.imdb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.service.FreepodSignIn
import st.orm.demo.imdb.service.ProviderProfile

class FreepodSignInTest {

    @Test
    fun `on Freepod the platform's headers are the account, the name percent-decoded`() {
        val freepod = FreepodSignIn(enabled = true)
        assertEquals(
            ProviderProfile(subject = "fp-42", username = "Amélie Poulain", name = "Amélie Poulain", avatarUrl = null),
            freepod.profile("fp-42", "Am%C3%A9lie%20Poulain")
        )
        // Without a name the account id stands in for the login name.
        assertEquals(ProviderProfile("fp-42", "fp-42", null, null), freepod.profile("fp-42", null))
        // No account header: the visitor is not signed in at Freepod.
        assertNull(freepod.profile(null, "Alice"))
    }

    @Test
    fun `anywhere else the headers are ignored, as a client could have set them`() {
        assertNull(FreepodSignIn(enabled = false).profile("fp-42", "Alice"))
    }

    @Test
    fun `the platform's sign-in returns to the application's path`() {
        assertEquals(
            "/.freepod/auth/login?rd=%2Fsignin%2Ffreepod%3Frd%3D%252Fseen",
            FreepodSignIn(enabled = true).loginUrl("/signin/freepod?rd=%2Fseen")
        )
    }
}
