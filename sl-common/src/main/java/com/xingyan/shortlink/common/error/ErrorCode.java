package com.xingyan.shortlink.common.error;

/**
 * 全模块统一错误码（DESIGN 7.1/7.2 响应约定的编码化）。
 */
public enum ErrorCode {

    OK("00000", "success"),
    LINK_NOT_FOUND("SL-4040", "short code not found"),
    LINK_EXPIRED("SL-4101", "link expired or access limit exceeded"),
    LINK_FORBIDDEN("SL-4030", "link disabled or banned"),
    URL_NOT_ALLOWED("SL-4001", "origin url rejected by admission check"),
    TENANT_QUOTA_EXCEEDED("SL-4290", "tenant quota exceeded"),
    RATE_LIMITED("SL-4291", "too many requests"),
    SIGN_INVALID("SL-4010", "signature invalid or replayed"),
    SHORT_CODE_TAKEN("SL-4090", "custom short code already used"),
    INTERNAL_ERROR("SL-5000", "internal error");

    private final String code;
    private final String message;

    ErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
