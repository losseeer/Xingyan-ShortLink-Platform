package com.xingyan.shortlink.jump;

import org.springframework.http.HttpStatus;

/**
 * 跳转决议（M1-07 纯 302；M2-11 增 429 的 {@code Retry-After}；
 * M2 动态路由在 JumpResolver 接口上加实现，DESIGN 第十章"纯 302 开关"即 direct302 形态为默认）。
 *
 * @param retryAfterSeconds 频控命中时回 {@code Retry-After}（到本窗口结束的秒数），其余为 0 表示不发该头
 */
public record JumpDecision(HttpStatus status, String location, String message, int retryAfterSeconds) {

    public static JumpDecision redirect(String location) {
        return new JumpDecision(HttpStatus.FOUND, location, null, 0);
    }

    public static JumpDecision of(HttpStatus status, String message) {
        return new JumpDecision(status, null, message, 0);
    }

    public static JumpDecision rateLimited(String message, int retryAfterSeconds) {
        return new JumpDecision(HttpStatus.TOO_MANY_REQUESTS, null, message, retryAfterSeconds);
    }
}
