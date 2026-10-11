package com.xingyan.shortlink.admin.route;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingyan.shortlink.common.route.RouteCacheContract;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 缓存回写那一半的方向盘（DESIGN 8.4，M3-02，活体 compose Redis @6380）：
 * 补偿链路会把历史 outbox 整批重放一遍，"只许变新"必须是实测性质而不是注释里的承诺。
 */
class RouteCachePublisherLiveTest {

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;

    private final List<String> codes = new ArrayList<>();
    private RouteCachePublisher publisher;
    private SimpleMeterRegistry registry;

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

    @BeforeEach
    void wire() {
        assumeTrue(portOpen(6380));
        registry = new SimpleMeterRegistry();
        publisher = new RouteCachePublisher(redis, registry);
    }

    @AfterEach
    void clean() {
        for (String code : codes) {
            redis.delete("sl:r:" + code);
        }
        codes.clear();
    }

    private String track(String prefix) {
        String code = prefix + System.nanoTime() % 1000000;
        codes.add(code);
        return code;
    }

    private static String routeJson(String code, long version, String origin) {
        return "{\"origin_url\":\"" + origin + "\",\"tenant_id\":1001,\"redirect_type\":1,"
                + "\"expire_time\":null,\"access_limit\":null,\"status\":0,\"version\":" + version
                + ",\"short_code\":\"" + code + "\"}";
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private long cachedVersion(String code) throws Exception {
        String raw = redis.opsForValue().get("sl:r:" + code);
        assertNotNull(raw, "publish 之后 L2 必须有值");
        return JSON.readTree(raw).path("version").asLong();
    }

    @Test
    void olderVersionNeverOverwritesANewerCache() throws Exception {
        String code = track("m3pb");
        publisher.publish(code, routeJson(code, 5, "https://mock.ticketsales.test/v5"), 5);
        assertEquals(5, cachedVersion(code));

        publisher.publish(code, routeJson(code, 3, "https://mock.ticketsales.test/OLD"), 3);
        assertEquals(5, cachedVersion(code), "旧事件的补偿重放不能把缓存写回 v3");

        publisher.publish(code, routeJson(code, 7, "https://mock.ticketsales.test/v7"), 7);
        assertEquals(7, cachedVersion(code), "同一条缓存要能继续变新");
    }

    /** TTL 落在 24h 与 26h 之间：下限是设计值，上限那段是打散同批过期的抖动。 */
    @Test
    void ttlCarriesJitter() {
        String code = track("m3ttl");
        publisher.publish(code, routeJson(code, 1, "https://mock.ticketsales.test/ttl"), 1);
        Long ttl = redis.getExpire("sl:r:" + code);
        assertNotNull(ttl);
        assertTrue(ttl >= Duration.ofHours(24).toSeconds() && ttl <= Duration.ofHours(26).toSeconds(),
                "TTL 应在 24–26h（含抖动）之间，实际 " + ttl + "s");
    }

    /**
     * 广播跟着"缓存到底有没有被写动"走（DESIGN 8.4，M3-02）。两件事一起测：
     * ① 发布端真的把短码发上了那个通道——通道串写错时两端各自都"成功"，缓存照旧等 30s 才收敛；
     * ② 被 version 守卫挡下时**不**发广播——重放历史积压时绝大多数事件一行都不改，
     *    为它们各发一条通知只会让两个 jump 实例白清一遍本地缓存。
     * 订阅端与双实例的收敛时间在 {@code scripts/accept-m3-02.sh} 里测。
     */
    @Test
    void broadcastFollowsWhetherTheCacheActuallyChanged() throws Exception {
        BlockingQueue<String> seen = new LinkedBlockingQueue<>();
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener((message, pattern) ->
                seen.add(new String(message.getBody(), StandardCharsets.UTF_8)),
                new ChannelTopic(RouteCacheContract.EVICT_CHANNEL));
        container.afterPropertiesSet();
        container.start();
        try {
            // 订阅建立是异步的：先拿探针码确认通道接通（版本号要递增，反复同一版本会被守卫挡下、不广播）
            String probe = track("m3probe");
            String arrived = null;
            for (int version = 1; version <= 15 && (arrived = awaitCode(seen, probe, 200)) == null; version++) {
                publisher.publish(probe, routeJson(probe, version, "https://mock.ticketsales.test/probe"), version);
            }
            assertEquals(probe, arrived, "收不到失效广播：发布端或通道串断了（xsl:route:evict）");

            String code = track("m3bc");
            publisher.publish(code, routeJson(code, 5, "https://mock.ticketsales.test/v5"), 5);
            assertEquals(code, awaitCode(seen, code, 2000), "写动了缓存就该广播");

            publisher.publish(code, routeJson(code, 3, "https://mock.ticketsales.test/OLD"), 3);
            assertNull(awaitCode(seen, code, 800), "被 version 守卫挡下时不该发广播");
        } finally {
            container.destroy();
        }
    }

    /** 只等我要的那个短码；别人的通知照常收下但不影响断言（通道是共享的）。 */
    private static String awaitCode(BlockingQueue<String> seen, String code, long millis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < deadline) {
            String got = seen.poll();
            if (code.equals(got)) {
                return got;
            }
            if (got == null) {
                Thread.sleep(10);
            }
        }
        return null;
    }

    private static boolean portOpen(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
