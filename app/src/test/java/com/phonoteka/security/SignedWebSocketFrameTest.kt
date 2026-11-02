package com.phonoteka.security

import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Test

class SignedWebSocketFrameTest {
    @Test fun futureFrameCannotBeReplayedBeforeInclusiveExpiry() {
        val signer = HmacTextFrameSigner(SecretKeySpec(ByteArray(32) { it.toByte() }, "HmacSHA256")) { 130L }
        val frame = signer.sign("/lab15/events", "fixture", "future_nonce_00000001")
        val guard = FrameReplayGuard()
        assertEquals(FrameVerificationResult.ACCEPTED, signer.verify(frame, "/lab15/events", guard, 100))
        assertEquals(FrameVerificationResult.REPLAY, signer.verify(frame, "/lab15/events", guard, 131))
        assertEquals(FrameVerificationResult.REPLAY, signer.verify(frame, "/lab15/events", guard, 160))
        assertEquals(FrameVerificationResult.EXPIRED, signer.verify(frame, "/lab15/events", guard, 161))
    }

    @Test fun replayGuardRejectsNewFramesWhenItsWindowCacheIsFull() {
        val guard = FrameReplayGuard(maxEntries = 2)
        assertEquals(true, guard.consume("nonce_000000000001", 100, 130))
        assertEquals(true, guard.consume("nonce_000000000002", 100, 130))
        assertEquals(false, guard.consume("nonce_000000000003", 100, 130))
        assertEquals(true, guard.consume("nonce_000000000003", 131, 161))
    }

    @Test fun frameBindsChannelPayloadTimestampAndOneTimeNonce() {
        var now = 1_800_000_000L
        val signer = HmacTextFrameSigner(SecretKeySpec("local-websocket-fixture-key".toByteArray(), "HmacSHA256")) { now }
        val frame = signer.sign("/lab15/events", "{\"event\":\"demo\"}", "0123456789abcdef0123456789abcdef0123")
        val guard = FrameReplayGuard()
        assertEquals(FrameVerificationResult.ACCEPTED, signer.verify(frame, "/lab15/events", guard))
        assertEquals(FrameVerificationResult.REPLAY, signer.verify(frame, "/lab15/events", guard))
        assertEquals(FrameVerificationResult.INVALID_CHANNEL,
            signer.verify(frame, "/lab15/other", FrameReplayGuard()))
        assertEquals(FrameVerificationResult.INVALID_SIGNATURE,
            signer.verify(frame.copy(payloadBase64 = frame.payloadBase64.dropLast(4) + "AAAA"), "/lab15/events", FrameReplayGuard()))
        now += 31
        assertEquals(FrameVerificationResult.EXPIRED,
            signer.verify(frame, "/lab15/events", FrameReplayGuard()))
        assertEquals(frame, SignedTextFrame.parse(frame.toWire()))
    }
}
