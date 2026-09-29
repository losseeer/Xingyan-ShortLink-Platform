package com.xingyan.shortlink.common.api;

import com.xingyan.shortlink.common.error.ErrorCode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 管理面统一响应信封（DESIGN 7.2）：code=00000 成功，其余见 ErrorCode。
 */
public final class ApiResponse {

    public static final String FIELD_CODE = "code";
    public static final String FIELD_MESSAGE = "message";
    public static final String FIELD_DATA = "data";
    public static final String FIELD_REQUEST_ID = "request_id";

    private ApiResponse() {
    }

    public static Map<String, Object> ok(Object data) {
        return body(ErrorCode.OK.getCode(), ErrorCode.OK.getMessage(), data);
    }

    public static Map<String, Object> error(ErrorCode errorCode, String message) {
        return body(errorCode.getCode(), message == null ? errorCode.getMessage() : message, null);
    }

    private static Map<String, Object> body(String code, String message, Object data) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(FIELD_CODE, code);
        map.put(FIELD_MESSAGE, message);
        map.put(FIELD_DATA, data);
        map.put(FIELD_REQUEST_ID, UUID.randomUUID().toString());
        return map;
    }
}
