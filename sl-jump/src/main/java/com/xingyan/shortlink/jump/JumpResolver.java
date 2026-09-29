package com.xingyan.shortlink.jump;

import jakarta.servlet.http.HttpServletRequest;

public interface JumpResolver {

    JumpDecision resolve(String code, HttpServletRequest request);
}
