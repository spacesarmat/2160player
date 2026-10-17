package tv.p2160.core.secret

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldSecretsTest {
    @Test
    fun migrateSealsPlaintextAndLeavesCiphertext() {
        val json = """[{"id":"1","pass":"secret","user":"me"},{"id":"2","pass":""},{"id":"3","pass":"enc1:already"}]"""
        val next = migrateSmbJson(json) { "enc1:$it" }
        assertTrue(next.contains(""""pass":"enc1:secret""""))
        assertTrue(next.contains(""""pass":"""""))
        assertTrue(next.contains(""""pass":"enc1:already""""))
        assertFalse(next.contains(""""pass":"secret""""))
        assertEquals(next, migrateSmbJson(next) { "enc1:$it" })
    }

    @Test
    fun emptyAndPrefixedFieldsStay() {
        assertEquals("", migrateSecretField("", { "enc1:$it" }))
        assertEquals("enc1:abc", migrateSecretField("enc1:abc") { "enc1:$it" })
        assertEquals("enc1:key", migrateSecretField("key") { "enc1:$it" })
    }
}
