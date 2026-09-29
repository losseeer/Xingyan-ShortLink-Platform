package com.xingyan.shortlink.admin.api;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * M1-04 鉴权探针：验证网关 HMAC 过滤器放行后注入的 X-Tenant-Id，
 * 并借 requestId 观察 Idempotency-Key 重放行为。M1-06 后仍保留作运维自检。
 */
@RestController
@RequestMapping("/api/v1/ping")
public class PingController {

    @RequestMapping(method = {RequestMethod.GET, RequestMethod.POST})
    public Map<String, Object> ping(@RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "00000");
        body.put("tenantId", tenantId == null ? "anonymous" : tenantId);
        body.put("requestId", UUID.randomUUID().toString());
        return body;
    }
}
