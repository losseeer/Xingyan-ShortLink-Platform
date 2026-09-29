package com.xingyan.shortlink.admin.link;

import com.xingyan.shortlink.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 业务异常：错误码 + HTTP 状态，由 GlobalExceptionHandler 统一转响应信封。
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;
    private final HttpStatus status;

    public BizException(ErrorCode errorCode, HttpStatus status, String message) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
