package com.xingyan.shortlink.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 租户 API 请求 HMAC-SHA256 签名（DESIGN 9.4）。
 * 规范化串：METHOD \n path \n timestamp-millis \n nonce \n sha256hex(body)，密钥 = tenant.api_key。
 */
public final class HmacSigner {

    public static final long TIMESTAMP_WINDOW_MILLIS = 5 * 60 * 1000L;
    public static final String HEADER_API_KEY = "X-Api-Key";
    public static final String HEADER_TIMESTAMP = "X-Timestamp";
    public static final String HEADER_NONCE = "X-Nonce";
    public static final String HEADER_SIGNATURE = "X-Signature";
    public static final String HEADER_TENANT_ID = "X-Tenant-Id";

    private HmacSigner() {
    }

    public static String canonical(String method, String path, long timestampMillis, String nonce, byte[] body) {
        return method.toUpperCase() + "\n" + path + "\n" + timestampMillis + "\n" + nonce + "\n" + sha256Hex(body);
    }

    public static String sign(String secret, String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    public static boolean verify(String secret, String canonical, String signature) {
        byte[] expected = sign(secret, canonical).getBytes(StandardCharsets.UTF_8);
        byte[] actual = signature == null ? new byte[0] : signature.toLowerCase().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    public static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    data == null ? new byte[0] : data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static boolean timestampInWindow(long timestampMillis, long nowMillis) {
        return Math.abs(nowMillis - timestampMillis) <= TIMESTAMP_WINDOW_MILLIS;
    }
}
