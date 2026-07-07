package n7.bondcast.obs

import org.junit.Test
import kotlin.test.assertEquals

class ObsAuthTest {

    @Test
    fun `токен считается по формуле base64(sha256(base64(sha256(password+salt)) + challenge))`() {
        // эталон посчитан независимой реализацией (.NET SHA256 + Base64)
        val token = obsAuthToken(
            password = "supersecret",
            salt = "c2FsdA==",
            challenge = "Y2hhbGxlbmdl",
        )
        assertEquals("W9C7NVmerPX51e7i9FKYqUWhC3AHNYLlEhkHOq1yktQ=", token)
    }
}
