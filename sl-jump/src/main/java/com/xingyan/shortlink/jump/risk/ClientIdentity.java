package com.xingyan.shortlink.jump.risk;

import jakarta.servlet.http.HttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 客户端标识（DESIGN 5.3 第 2 层 / 4.2 ip_hash 共用口径）。
 *
 * <p>IP 来源只认 nginx 覆盖写入的 {@code X-Real-IP}（{@code $remote_addr}）：M1 版取的是
 * {@code X-Forwarded-For} 首元素，而 XFF 首元素由客户端自控——拿它当频控维度等于可以随意绕过，
 * 拿它做 ip_hash 则刷量数据不可信。XFF 只在没有 X-Real-IP 时兜底，且取<b>末段</b>
 * （{@code $proxy_add_x_forwarded_for} 由本机 nginx 追加的那一跳）。
 */
public final class ClientIdentity {

    private ClientIdentity() {
    }

    public static String ip(HttpServletRequest request) {
        if (request == null) {
            return "";
        }
        String real = request.getHeader("X-Real-IP");
        if (notBlank(real)) {
            return real.trim();
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (notBlank(xff)) {
            String[] hops = xff.split(",");
            String last = hops[hops.length - 1].trim();
            if (notBlank(last)) {
                return last;
            }
        }
        return request.getRemoteAddr() == null ? "" : request.getRemoteAddr();
    }

    /** 加盐 SHA-256 截断 16 位（PIPL 脱敏，盐轮换见 DESIGN 9.6）；频控键与 ClickEvent.ip_hash 同源。 */
    public static String hash(String salt, String ip) {
        if (ip == null || ip.isBlank()) {
            return "";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((salt + "|" + ip).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().withoutPadding().encodeToString(digest).substring(0, 16);
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
