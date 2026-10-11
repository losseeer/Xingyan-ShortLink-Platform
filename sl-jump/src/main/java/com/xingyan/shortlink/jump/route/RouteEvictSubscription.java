package com.xingyan.shortlink.jump.route;

import com.xingyan.shortlink.common.route.RouteCacheContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;

/**
 * 路由失效广播的订阅端（DESIGN 8.4，M3-02）：管理面每改一条链接发一次，本实例收到就清掉本地那一份。
 *
 * <p>它是<b>尽力而为的加速器，不在正确性链路上</b>：Redis 重启或订阅瞬断都会丢消息，那时退化成 L1 的
 * 30s TTL 自愈（新值早已在 link_route 与 L2 里，只是这个实例还要等一会儿才看得到）。所以这里不重试、
 * 不落盘、也不因订阅失败而影响启动——容器自带的重连足够；DESIGN 8.3 那条「Redis 挂时跳转不能停」
 * 才是这里的硬约束。
 */
@Configuration
public class RouteEvictSubscription {

    private static final Logger log = LoggerFactory.getLogger(RouteEvictSubscription.class);

    @Bean
    public RedisMessageListenerContainer routeEvictContainer(StringRedisTemplate redis, RouteRepository routes) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(redis.getRequiredConnectionFactory());
        container.addMessageListener((MessageListener) (message, pattern) -> {
            String code = new String(message.getBody(), StandardCharsets.UTF_8);
            log.debug("[jump] 收到路由失效广播 code={}", code);
            routes.evictLocal(code);
        }, new ChannelTopic(RouteCacheContract.EVICT_CHANNEL));
        return container;
    }
}
