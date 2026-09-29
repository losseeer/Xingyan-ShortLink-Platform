package com.xingyan.shortlink.admin.link;

import com.xingyan.shortlink.common.api.ApiResponse;
import com.xingyan.shortlink.common.error.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理面链接接口（DESIGN 7.2，前缀 /api/v1，经网关 HMAC 后注入 X-Tenant-Id）。
 */
@RestController
@RequestMapping("/api/v1/links")
public class LinkController {

    private final LinkService linkService;

    public LinkController(LinkService linkService) {
        this.linkService = linkService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @RequestBody CreateLinkRequest req) {
        return ResponseEntity.status(HttpStatus.OK).body(ApiResponse.ok(linkService.create(requireTenant(tenantId), req)));
    }

    @GetMapping("/{code}")
    public Map<String, Object> get(
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @PathVariable String code) {
        return ApiResponse.ok(linkService.get(requireTenant(tenantId), code));
    }

    @PatchMapping("/{code}")
    public Map<String, Object> update(
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @PathVariable String code,
            @RequestBody UpdateLinkRequest req) {
        return ApiResponse.ok(linkService.update(requireTenant(tenantId), code, req));
    }

    private static long requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new BizException(ErrorCode.SIGN_INVALID, HttpStatus.UNAUTHORIZED,
                    "missing X-Tenant-Id: requests must pass through gateway HMAC auth");
        }
        try {
            return Long.parseLong(tenantId.trim());
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.SIGN_INVALID, HttpStatus.UNAUTHORIZED,
                    "invalid X-Tenant-Id: " + tenantId);
        }
    }
}
