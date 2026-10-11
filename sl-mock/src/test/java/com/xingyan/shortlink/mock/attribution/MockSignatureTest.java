package com.xingyan.shortlink.mock.attribution;

import com.xingyan.shortlink.common.security.HmacSigner;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验签的六种失败与一种通过（DESIGN 7.3 ④）。这层写歪了不会让链路报错，只会让坏请求也能进账。 */
class MockSignatureTest {

    private static final String KEY = "xy-key-push-mock-001";
    private static final String PATH = "/mock/attribution/batches";
    private static final byte[] BODY = "{\"batch_id\":\"xsl-1\"}".getBytes(StandardCharsets.UTF_8);
    private static final long NOW = 1_790_000_000_000L;

    private static String sign(String key, long ts, String nonce, byte[] body) {
        return HmacSigner.sign(key, HmacSigner.canonical("POST", PATH, ts, nonce, body));
    }

    private MockSignature.Result run(String key, String ts, String nonce, String sig, byte[] body) {
        return MockSignature.verify(KEY, key, ts, nonce, sig, "POST", PATH, body, NOW);
    }

    @Test
    void aProperlySignedRequestPasses() {
        String nonce = "n-1";
        MockSignature.Result r = run(KEY, String.valueOf(NOW), nonce, sign(KEY, NOW, nonce, BODY), BODY);
        assertTrue(r.ok(), r.reason());
    }

    @Test
    void missingAnyHeaderFails() {
        String nonce = "n-2";
        String sig = sign(KEY, NOW, nonce, BODY);
        assertEquals("缺少签名头", run(KEY, String.valueOf(NOW), nonce, null, BODY).reason());
        assertEquals("缺少签名头", run(KEY, null, nonce, sig, BODY).reason());
        assertEquals("缺少签名头", run(KEY, String.valueOf(NOW), " ", sig, BODY).reason());
    }

    @Test
    void badTimestampFormatFails() {
        assertTrue(run(KEY, "not-a-number", "n-3", "s", BODY).reason().contains("时间戳"));
    }

    @Test
    void timestampOutsideTheWindowFails() {
        long stale = NOW - HmacSigner.TIMESTAMP_WINDOW_MILLIS - 1000;
        assertEquals("时间戳越出 5 分钟窗口", run(KEY, String.valueOf(stale), "n-4", sign(KEY, stale, "n-4", BODY), BODY).reason());
    }

    @Test
    void otherApiKeyFailsBeforeSignatureCheck() {
        String nonce = "n-5";
        MockSignature.Result r = run("xy-key-someone-else", String.valueOf(NOW), nonce,
                sign("xy-key-someone-else", NOW, nonce, BODY), BODY);
        assertFalse(r.ok());
        assertEquals("api key 不匹配", r.reason());
    }

    @Test
    void tamperedBodyFails() {
        String nonce = "n-6";
        byte[] tampered = "{\"batch_id\":\"xsl-2\"}".getBytes(StandardCharsets.UTF_8);
        assertEquals("签名不匹配", run(KEY, String.valueOf(NOW), nonce, sign(KEY, NOW, nonce, BODY), tampered).reason());
    }

    @Test
    void wrongPathFails() {
        // 签名把 path 也盖住了：换个路径重放同一个 body 不会被当成合法请求
        String nonce = "n-7";
        String sig = HmacSigner.sign(KEY, HmacSigner.canonical("POST", "/mock/attribution/other", NOW, nonce, BODY));
        MockSignature.Result r = MockSignature.verify(KEY, KEY, String.valueOf(NOW), nonce, sig,
                "POST", PATH, BODY, NOW);
        assertFalse(r.ok());
        assertEquals("签名不匹配", r.reason());
    }
}
