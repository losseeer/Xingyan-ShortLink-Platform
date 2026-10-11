package com.xingyan.shortlink.mock;

import com.xingyan.shortlink.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * mock 侧的拒收信号：带着该返回的状态码与错误码，由控制层翻成响应体（坏数据不落到台账/订单表里）。
 *
 * <p>放模块根包而不是某个 feature 包，是因为回传（M3-03）与订单（M3-04）两条入口都要用它——
 * 一条"哪个包在用"的依赖方向不该存在，拒收就是拒收。
 */
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
