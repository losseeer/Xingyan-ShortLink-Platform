package com.xingyan.shortlink.jump;

import org.springframework.http.HttpStatus;

/**
 * 跳转决议（M1-07 纯 302；M2-11 增 429 的 {@code Retry-After}；M2-33 增中间页）。
 *
 * <p>三种形态靠 {@code location}／{@code message}／{@code htmlBody} 哪个非空来区分：
 * 302 带 Location，错误页带 message（controller 生成极简模板），中间页自带完整 HTML 且<b>必须</b>
 * no-store（页里有本次点击的 xy_click_id，缓存会把 A 的 click_id 发给 B，见 DESIGN 6.2）。
 *
 * @param retryAfterSeconds 频控命中时回 {@code Retry-After}（到本窗口结束的秒数），其余为 0 表示不发该头
 */
public record JumpDecision(HttpStatus status, String location, String message, int retryAfterSeconds, String htmlBody) {

    public static JumpDecision redirect(String location) {
        return new JumpDecision(HttpStatus.FOUND, location, null, 0, null);
    }

    public static JumpDecision of(HttpStatus status, String message) {
        return new JumpDecision(status, null, message, 0, null);
    }

    public static JumpDecision rateLimited(String message, int retryAfterSeconds) {
        return new JumpDecision(HttpStatus.TOO_MANY_REQUESTS, null, message, retryAfterSeconds, null);
    }

    /** 中间页：200 + 我们自己的 HTML，controller 会给它加 no-store。 */
    public static JumpDecision page(String html) {
        return new JumpDecision(HttpStatus.OK, null, null, 0, html);
    }
}
