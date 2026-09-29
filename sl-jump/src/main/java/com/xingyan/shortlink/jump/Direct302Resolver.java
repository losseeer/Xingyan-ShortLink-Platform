package com.xingyan.shortlink.jump;

import com.xingyan.shortlink.common.route.RouteConfig;
import com.xingyan.shortlink.jump.route.RouteRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 纯 302 决议（M1-07 默认形态）：路由存在性 → status → 过期 → 次数上限 → 302。
 * M1-08 归因参数拼接将从 origin_url 与 Location 之间插入。
 */
@Service
public class Direct302Resolver implements JumpResolver {

    private final RouteRepository routes;
    private final AccessCounter accessCounter;
    private final MeterRegistry registry;

    public Direct302Resolver(RouteRepository routes, AccessCounter accessCounter, MeterRegistry registry) {
        this.routes = routes;
        this.accessCounter = accessCounter;
        this.registry = registry;
    }

    @Override
    public JumpDecision resolve(String code, HttpServletRequest request) {
        Optional<RouteConfig> route = routes.find(code);
        if (route.isEmpty()) {
            return count(JumpDecision.of(HttpStatus.NOT_FOUND, "短链不存在或已删除"));
        }
        RouteConfig rc = route.get();
        if (rc.status() != 0) {
            return count(JumpDecision.of(HttpStatus.FORBIDDEN, "短链已停用或被封禁"));
        }
        if (rc.expireTime() != null && LocalDateTime.now().isAfter(rc.expireTime())) {
            return count(JumpDecision.of(HttpStatus.GONE, "短链已过期"));
        }
        if (rc.accessLimit() != null && !accessCounter.tryConsume(code, rc.accessLimit())) {
            return count(JumpDecision.of(HttpStatus.GONE, "短链访问次数已达上限"));
        }
        return count(JumpDecision.redirect(rc.originUrl()));
    }

    private JumpDecision count(JumpDecision decision) {
        registry.counter("xsl_jump_requests_total", "outcome", decision.status().name().toLowerCase()).increment();
        return decision;
    }
}
