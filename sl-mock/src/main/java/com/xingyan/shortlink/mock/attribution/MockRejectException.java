package com.xingyan.shortlink.mock.attribution;

import com.xingyan.shortlink.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

/** 拒收信号：带着该返回的状态码与错误码，由控制层翻成响应体（坏批次不落到台账）。 */
public class MockRejectException extends RuntimeException {

    private final HttpStatus status;
    private final ErrorCode code;

    public MockRejectException(HttpStatus status, ErrorCode code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public ErrorCode code() {
        return code;
    }
}
