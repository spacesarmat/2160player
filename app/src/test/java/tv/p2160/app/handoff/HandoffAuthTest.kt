package tv.p2160.app.handoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HandoffAuthTest {
    private val store = MemoryHandoffStore()

    @Before
    fun setUp() {
        store.keys().toList().forEach { store.remove(it) }
        HandoffAuth.initForTest(store)
        HandoffAuth.mode = HandoffAuth.Mode.CUSTOM
        HandoffAuth.customCode = "123456"
    }

    @Test
    fun newCustomPinIsSixToEightDigitsLegacyStillWorks() {
        assertFalse(HandoffAuth.isValidCustomCode("1234"))
        assertFalse(HandoffAuth.isValidCustomCode("12345"))
        assertTrue(HandoffAuth.isValidCustomCode("123456"))
        assertTrue(HandoffAuth.isValidCustomCode("12345678"))
        assertFalse(HandoffAuth.isValidCustomCode("123456789"))
        assertTrue(HandoffAuth.isUsableCustomCode("1234"))
        HandoffAuth.customCode = "9876"
        assertEquals("9876", HandoffAuth.currentCode())
    }

    @Test
    fun issuedTokenSurvivesPinChange() {
        val begun = HandoffAuth.beginChallenge("phone", "Phone", "192.168.1.9") as HandoffAuth.ChallengeBegin.Created
        val proof = HandoffAuth.proofFor("123456", begun.nonce, "phone")
        val (result, token) = HandoffAuth.verify(begun.nonce, "phone", "Phone", proof)
        assertEquals(HandoffAuth.PairResult.OK, result)
        HandoffAuth.customCode = "654321"
        assertTrue(HandoffAuth.isAuthorized(token))
        assertFalse(HandoffAuth.isAuthorized("0".repeat(32)))
    }

    @Test
    fun challengeRateLimitPerIp() {
        repeat(HandoffAuth.CHALLENGE_LIMIT) { i ->
            val begun = HandoffAuth.beginChallenge("c$i", "N", "10.0.0.8")
            assertTrue(begun is HandoffAuth.ChallengeBegin.Created)
        }
        assertTrue(HandoffAuth.beginChallenge("overflow", "N", "10.0.0.8") is HandoffAuth.ChallengeBegin.Limited)
        assertTrue(HandoffAuth.beginChallenge("other", "N", "10.0.0.9") is HandoffAuth.ChallengeBegin.Created)
    }

    @Test
    fun rateLimitIsCheckedBeforeDeny() {
        val first = HandoffAuth.beginChallenge("blocked", "N", "10.1.0.1") as HandoffAuth.ChallengeBegin.Created
        HandoffAuth.deny(first.nonce)
        repeat(HandoffAuth.CHALLENGE_LIMIT - 1) {
            assertTrue(HandoffAuth.beginChallenge("blocked", "N", "10.1.0.1") is HandoffAuth.ChallengeBegin.Denied)
        }
        assertTrue(HandoffAuth.beginChallenge("blocked", "N", "10.1.0.1") is HandoffAuth.ChallengeBegin.Limited)
    }

    @Test
    fun statusReleasesTokenOnlyToTheChallengeIp() {
        val begun = HandoffAuth.beginChallenge("tv", "TV", "192.168.0.20") as HandoffAuth.ChallengeBegin.Created
        assertTrue(HandoffAuth.approve(begun.nonce))
        val stranger = HandoffAuth.status(begun.nonce, "192.168.0.99")
        assertEquals(false, stranger!!.sameIp)
        assertNull(stranger.token)
        val owner = HandoffAuth.status(begun.nonce, "192.168.0.20")
        assertEquals(true, owner!!.sameIp)
        assertEquals(HandoffAuth.Decision.APPROVED, owner.decision)
        assertEquals(32, owner.token!!.length)
        assertNull(HandoffAuth.status(begun.nonce, "192.168.0.20"))
        assertTrue(HandoffAuth.isAuthorized(owner.token))
    }

    @Test
    fun constantTimeEqualsDoesNotTreatLengthAsAFastPath() {
        assertTrue(constantTimeEquals("abc", "abc"))
        assertFalse(constantTimeEquals("abc", "abd"))
        assertFalse(constantTimeEquals("abc", "abcd"))
        assertFalse(constantTimeEquals("", "a"))
    }

    @Test
    fun offWarningFlag() {
        assertFalse(HandoffAuth.offWarningSeen)
        HandoffAuth.offWarningSeen = true
        assertTrue(HandoffAuth.offWarningSeen)
        HandoffAuth.initForTest(store)
        assertTrue(HandoffAuth.offWarningSeen)
        assertNotEquals("", HandoffAuth.deviceId)
    }
}
