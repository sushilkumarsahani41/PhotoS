package app.photos

import org.junit.Assert.*
import org.junit.Test

class SecretKindTest {
    @Test fun pinNeedsSixDigitsOnly() {
        assertTrue(SecretKind.PIN.valid("123456".toCharArray()))
        assertFalse(SecretKind.PIN.valid("12345".toCharArray()))
        assertFalse(SecretKind.PIN.valid("12345a".toCharArray()))
    }
    @Test fun passwordNeedsTenCharacters() {
        assertTrue(SecretKind.PASSWORD.valid("correct-ho".toCharArray()))
        assertFalse(SecretKind.PASSWORD.valid("short".toCharArray()))
    }
    @Test fun nounKeepsPinCapitalised() {
        assertEquals("PIN", SecretKind.PIN.noun)
        assertEquals("password", SecretKind.PASSWORD.noun)
    }
}
