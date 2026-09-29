package com.xingyan.shortlink.common.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HmacSignerTest {

    private static final String SECRET = "xy-key-alice-001";

    @Test
    void signAndVerifyRoundTrip() {
        String canonical = HmacSigner.canonical("POST", "/api/v1/links", 1767225600000L, "n-1", "{\"a\":1}".getBytes());
        String signature = HmacSigner.sign(SECRET, canonical);
        assertTrue(HmacSigner.verify(SECRET, canonical, signature));
        assertEquals(64, signature.length());
    }

    @Test
    void oneByteTamperFailsVerification() {
        String canonical = HmacSigner.canonical("POST", "/api/v1/links", 1767225600000L, "n-1", "{\"a\":1}".getBytes());
        String signature = HmacSigner.sign(SECRET, canonical);
        String flipped = (signature.charAt(0) == 'a' ? 'b' : 'a') + signature.substring(1);
        assertFalse(HmacSigner.verify(SECRET, canonical, flipped));
        assertFalse(HmacSigner.verify(SECRET, canonical.toUpperCase(), signature));
        assertFalse(HmacSigner.verify("other-secret", canonical, signature));
    }

    @Test
    void emptyBodyIsStableSha256OfEmptyString() {
        assertEquals(HmacSigner.sha256Hex(new byte[0]), HmacSigner.sha256Hex(null));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", HmacSigner.sha256Hex(null));
    }

    @Test
    void timestampWindowAllowsClockSkewBothSides() {
        long now = 1_000_000_000_000L;
        assertTrue(HmacSigner.timestampInWindow(now - HmacSigner.TIMESTAMP_WINDOW_MILLIS, now));
        assertTrue(HmacSigner.timestampInWindow(now + HmacSigner.TIMESTAMP_WINDOW_MILLIS, now));
        assertFalse(HmacSigner.timestampInWindow(now - HmacSigner.TIMESTAMP_WINDOW_MILLIS - 1, now));
    }
}
