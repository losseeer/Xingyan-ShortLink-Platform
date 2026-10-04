package com.xingyan.shortlink.jump;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingyan.shortlink.common.id.SnowflakeIdGenerator;
import com.xingyan.shortlink.common.route.RouteConfig;
import com.xingyan.shortlink.common.route.UaClassifier;
import com.xingyan.shortlink.jump.event.ClickEventProducer;
import com.xingyan.shortlink.jump.risk.RateLimiter;
import com.xingyan.shortlink.jump.route.RouteRepository;
import com.xingyan.shortlink.jump.target.Target;
import com.xingyan.shortlink.jump.target.UrlLinkProvider;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 回退形态：{@code xsl.jump.mode=direct302}（DESIGN 第十章承诺的"纯 302 回退开关"，M2-33 才成真）。
 *
 * <p>与 {@link DynamicRouteResolver} 共用 {@link JumpChain} 那条链路，只把目标固定成
 * {@code origin_url}，所以这个开关是可证伪的：该模式下任何请求都不会返回 200 中间页
 * （M2-35 的开关验证段就断言这一条）。
 */
@Service
@ConditionalOnProperty(name = "xsl.jump.mode", havingValue = "direct302")
public class Direct302Resolver extends JumpChain {

    public Direct302Resolver(RouteRepository routes,
                             AccessCounter accessCounter,
                             RateLimiter rateLimiter,
                             ClickEventProducer clickEvents,
                             SnowflakeIdGenerator idGenerator,
                             ObjectMapper mapper,
                             MeterRegistry registry,
                             UaClassifier uaClassifier,
                             UrlLinkProvider urlLinkProvider,
                             @Value("${xsl.jump.ip-salt:local-dev-salt}") String ipSalt) {
        super(routes, accessCounter, rateLimiter, clickEvents, idGenerator, mapper, registry,
                uaClassifier, urlLinkProvider, ipSalt);
    }

    @Override
    protected Target chooseTarget(RouteConfig rc, UaClassifier.Profile profile) {
        return Target.h5(rc.originUrl());
    }
}
