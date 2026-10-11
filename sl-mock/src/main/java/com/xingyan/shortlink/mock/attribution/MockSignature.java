package com.xingyan.shortlink.mock.attribution;

import com.xingyan.shortlink.common.security.HmacSigner;

/**
 * 回传请求的签名核验（DESIGN 7.3 ④）。
 *
 * <p>单独成一个是为了让"坏签名到底坏在哪"能被单测逐条钉住——这段逻辑放在控制器里就只能靠
 * 起整个服务才能覆盖，而它恰好是最容易被写错又最难被发现的一层：验签写歪了不会让链路失败，
 * 只会让<b>任何</b>请求都能进账（或者全都进不来），后者会在验收时被发现，前者不会。
 *
 * <p>不查 nonce：同一批内容重投由 {@code batch_id} 幂等吸收，再养一张 nonce 表没有收益（7.3 ④）。
 */
public final class MockSignature {

    public record Result(boolean ok, String reason) {

        static Result pass() {
            return new Result(true, "");
        }

        static Result fail(String reason) {
            return new Result(false, reason);
        }
    }

    private MockSignature() {
    }

    public static Result verify(String expectedKey, String apiKey, String timestamp, String nonce,
                                String signature, String method, String path, byte[] body, long nowMillis) {
        if (isBlank(apiKey) || isBlank(timestamp) || isBlank(nonce) || isBlank(signature)) {
            return Result.fail("缺少签名头");
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            return Result.fail("时间戳格式不对");
        }
        if (!HmacSigner.timestampInWindow(ts, nowMillis)) {
            return Result.fail("时间戳越出 5 分钟窗口");
        }
        // 本期 key 即 secret（DESIGN 9.4：key/secret 分离与轮换仍是欠着的那格），所以先比 key 再验签
        if (!expectedKey.equals(apiKey.trim())) {
            return Result.fail("api key 不匹配");
        }
        String canonical = HmacSigner.canonical(method, path, ts, nonce, body);
        if (!HmacSigner.verify(expectedKey, canonical, signature)) {
            return Result.fail("签名不匹配");
        }
        return Result.pass();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
