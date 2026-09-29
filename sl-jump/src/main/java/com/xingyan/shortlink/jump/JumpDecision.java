package com.xingyan.shortlink.jump;

import org.springframework.http.HttpStatus;

/**
 * 跳转决议（M1-07 纯 302；M2 动态路由在 JumpResolver 接口上加实现，
 * DESIGN 第十章"纯 302 开关"即 direct302 形态为默认）。
 */
public record JumpDecision(HttpStatus status, String location, String message) {

    public static JumpDecision redirect(String location) {
        return new JumpDecision(HttpStatus.FOUND, location, null);
    }

    public static JumpDecision of(HttpStatus status, String message) {
        return new JumpDecision(status, null, message);
    }
}
