package com.xingyan.shortlink.jump.risk;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * M2-11 活体验收（compose Redis @127.0.0.1:6380）：窗口计数的原子性、EXPIRE 只在首次设置、
 * 维度隔离、链接级阈值，以及 Redis 不可达时的进程内降级（DESIGN 8.3）。基础设施不通则整体跳过。
 */
class RateLimiterLiveTest {

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private final List<String> mine = new ArrayList<>();

    @BeforeAll
    static void up() {
        assumeTrue(portOpen(6380));
        factory = new LettuceConnectionFactory("127.0.0.1", 6380);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void down() {
        if (factory != null) {
            factory.destroy();
        }
    }

    @AfterEach
    void clean() {
        for (String code : mine) {
            Set<String> keys = windowKeys(code);
            if (!keys.isEmpty()) {
                redis.delete(keys);
            }
        }
        mine.clear();
    }

    private Set<String> windowKeys(String code) {
        Set<String> keys = redis.keys("sl:rl:" + code + ":*");
        return keys == null ? Set.of() : keys;
    }

    private static boolean portOpen(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static RateLimiter limiter(StringRedisTemplate template, int permits, int window) {
        return new RateLimiter(template, new SimpleMeterRegistry(), true, permits, window);
    }

    @Test
    void countsWithinWindowAndBlocksOverLimit() {
        String code = "rlive01";
        mine.add(code);
        RateLimiter limiter = limiter(redis, 3, 600);

        for (int i = 1; i <= 3; i++) {
            RateLimiter.Decision d = limiter.check(code, "iphash-a", null);
            assertTrue(d.allowed(), "第 " + i + " 次应放行");
            assertEquals(i, d.counted());
            assertFalse(d.degraded());
        }
        RateLimiter.Decision fourth = limiter.check(code, "iphash-a", null);
        assertFalse(fourth.allowed(), "第 4 次应被拦");
        assertEquals(4, fourth.counted(), "被拦的请求也计入窗口：否则边界处永远追不上真实速率，拦截也就失去意义");
        assertTrue(fourth.retryAfterSeconds() > 0 && fourth.retryAfterSeconds() <= 600);
    }

    @Test
    void windowKeyGetsTtlOnFirstHitOnlyAndIsSharedAcrossCalls() {
        String code = "rlive02";
        mine.add(code);
        RateLimiter limiter = limiter(redis, 100, 600);
        limiter.check(code, "iphash-b", null);

        Set<String> keys = windowKeys(code);
        assertEquals(1, keys.size(), "一个窗口只应有一个键（slot 由时钟决定，不该每次新建）");
        String key = keys.iterator().next();
        Long ttl = redis.getExpire(key);
        assertNotNull(ttl);
        assertTrue(ttl > 300 && ttl <= 600, "EXPIRE 要在首次 INCR 时设上，实测 ttl=" + ttl);

        limiter.check(code, "iphash-b", null);
        assertEquals(1, windowKeys(code).size(), "同窗口内复用同一个键");
        assertEquals("2", redis.opsForValue().get(key));
    }

    @Test
    void dimensionsAreIsolatedPerCodeAndPerIp() {
        String code = "rlive03";
        String other = "rlive04";
        mine.add(code);
        mine.add(other);
        RateLimiter limiter = limiter(redis, 1, 600);

        assertTrue(limiter.check(code, "iphash-c", null).allowed());
        assertTrue(limiter.check(code, "iphash-d", null).allowed(), "换 IP 不受影响");
        assertTrue(limiter.check(other, "iphash-c", null).allowed(), "换链接不受影响");
        assertFalse(limiter.check(code, "iphash-c", null).allowed(), "同 (code, ip) 第二次即拦");
    }

    @Test
    void perLinkOverrideBeatsGlobalDefault() {
        String code = "rlive05";
        mine.add(code);
        RateLimiter limiter = limiter(redis, 1, 600);   // 全局阈值 1：不给覆盖的话第二次就被拦
        assertTrue(limiter.check(code, "iphash-e", 5).allowed());
        assertTrue(limiter.check(code, "iphash-e", 5).allowed());
        RateLimiter.Decision third = limiter.check(code, "iphash-e", 5);
        assertTrue(third.allowed(), "链接级阈值 5 生效（全局 1 早就拦下了）");
        assertEquals(3, third.counted());
    }

    /** Redis 挂了不能把跳转一起打死：退化到进程内计数（DESIGN 8.3 的"频控退化为进程内计数"）。 */
    @Test
    void fallsBackToLocalWindowWhenRedisIsUnreachable() {
        LettuceConnectionFactory dead = new LettuceConnectionFactory("127.0.0.1", 6399);
        dead.setTimeout(300);
        dead.afterPropertiesSet();
        StringRedisTemplate deadTemplate = new StringRedisTemplate(dead);
        deadTemplate.afterPropertiesSet();
        try {
            RateLimiter limiter = limiter(deadTemplate, 2, 60);
            assertTrue(limiter.check("rlivedown1", "iphash-f", null).allowed());
            assertTrue(limiter.check("rlivedown1", "iphash-f", null).allowed());
            RateLimiter.Decision third = limiter.check("rlivedown1", "iphash-f", null);
            assertTrue(third.degraded(), "降级样本要能被 mode=\"local\" 单独查出来");
            assertFalse(third.allowed(), "进程内计数仍然生效（阈值不因降级而消失）");
        } finally {
            dead.destroy();
        }
    }

    @Test
    void disabledFlagBypassesEverything() {
        RateLimiter off = new RateLimiter(redis, new SimpleMeterRegistry(), false, 1, 60);
        for (int i = 0; i < 5; i++) {
            assertTrue(off.check("rlive06", "iphash-g", null).allowed());
        }
        mine.add("rlive06");
        assertEquals(0, windowKeys("rlive06").size(), "关闭状态下不该产生任何键");
    }
}
