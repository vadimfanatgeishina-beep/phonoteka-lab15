package com.phonoteka.security

import org.junit.Assert.assertEquals
import org.junit.Test

class RequestSigningTest {
    @Test fun futureTimestampCannotBeReplayedBeforeInclusiveExpiry() {
        val signer = HmacRequestSigner.forMockServer(ByteArray(32) { it.toByte() }) { 130L }
        val signed = signer.sign("GET", "/api/test", nonce = "future_nonce_00000001")
        val guard = ReplayGuard()
        assertEquals(VerificationResult.ACCEPTED, signer.verify("GET", "/api/test", byteArrayOf(), signed, guard, 100))
        assertEquals(VerificationResult.REPLAY, signer.verify("GET", "/api/test", byteArrayOf(), signed, guard, 131))
        assertEquals(VerificationResult.REPLAY, signer.verify("GET", "/api/test", byteArrayOf(), signed, guard, 160))
        assertEquals(VerificationResult.EXPIRED, signer.verify("GET", "/api/test", byteArrayOf(), signed, guard, 161))
        assertEquals(true, guard.consume("another_nonce_000001", 161, 191))
    }

    @Test fun overflowingTimestampIsExpiredInsteadOfPassingTheWindowCheck() {
        val signer = HmacRequestSigner.forMockServer(ByteArray(32) { it.toByte() }) { Long.MIN_VALUE }
        val signed = signer.sign("GET", "/api/test", nonce = "overflow_nonce_00001")
        assertEquals(VerificationResult.EXPIRED,
            signer.verify("GET", "/api/test", byteArrayOf(), signed, ReplayGuard(), 0))
    }

    @Test fun replayGuardBoundsNonceMemoryAndRecoversAfterWindowExpires() {
        val guard = ReplayGuard(maxEntries = 1)
        assertEquals(true, guard.consume("nonce_000000000001", 100, 130))
        assertEquals(false, guard.consume("nonce_000000000002", 100, 130))
        assertEquals(true, guard.consume("nonce_000000000002", 131, 161))
    }

    @Test fun signatureBindsMethodPathAndBodyAndRejectsReplay() {
        var now = 1_800_000_000L
        val signer = HmacRequestSigner.forMockServer("test-only-key-for-local-fixture".toByteArray()) { now }
        val body = "{\"track\":1}".toByteArray()
        val signed = signer.sign("POST", "/api/tracks", body, "0123456789abcdef0123456789abcdef0123")
        val guard = ReplayGuard()
        assertEquals(VerificationResult.ACCEPTED, signer.verify("POST", "/api/tracks", body, signed, guard))
        assertEquals(VerificationResult.REPLAY, signer.verify("POST", "/api/tracks", body, signed, guard))
        assertEquals(VerificationResult.INVALID_SIGNATURE,
            signer.verify("POST", "/api/tracks", "tampered".toByteArray(), signed, ReplayGuard()))
        now += 31
        assertEquals(VerificationResult.EXPIRED, signer.verify("POST", "/api/tracks", body, signed, ReplayGuard()))
    }

    @Test fun signatureBindsEncodedQueryToo() {
        val signer = HmacRequestSigner.forMockServer("local-fixture-only".toByteArray()) { 1_800_000_000L }
        val signed = signer.sign("GET", "/api/test?item=one", byteArrayOf(), "0123456789abcdef0123456789abcdef0123")
        assertEquals(VerificationResult.INVALID_SIGNATURE,
            signer.verify("GET", "/api/test?item=two", byteArrayOf(), signed, ReplayGuard()))
    }
}
