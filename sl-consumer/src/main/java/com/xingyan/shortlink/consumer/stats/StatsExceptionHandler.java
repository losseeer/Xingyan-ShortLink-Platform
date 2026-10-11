package com.xingyan.shortlink.consumer.stats;

import com.xingyan.shortlink.common.api.ApiResponse;
import com.xingyan.shortlink.common.error.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 看板只读接口的错误映射（M3-05）。
 *
 * <p>两种失败分开是因为它们的读者不同：400 是"参数写错了"（窗口倒置、id 非法、limit 越界），
 * 页面可以直接把这句话显示出来；503 是"ClickHouse 读不到"（容器重启、口令不对、查询超时），
 * 那种情况下页面要显示"数据源不可用"而不是"这个窗口没有点击"——
 * <b>把后端故障渲染成空数据，是看板最坏的一种撒谎方式</b>，所以这两条必须用不同的 HTTP 状态与不同的 code。
 *
 * <p>响应继续走 {@link ApiResponse} 那个信封（管理面统一约定，DESIGN 7.2），不另造一种错误形状。
 */
@RestControllerAdvice(assignableTypes = StatsController.class)
public class StatsExceptionHandler {

    @ExceptionHandler(StatsRequestException.class)
    public ResponseEntity<Map<String, Object>> onBadRequest(StatsRequestException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ErrorCode.VALIDATION_FAILED, e.getMessage()));
    }

    @ExceptionHandler(StatsBackendException.class)
    public ResponseEntity<Map<String, Object>> onBackendDown(StatsBackendException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR, e.getMessage()));
    }
}
