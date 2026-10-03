package com.xingyan.shortlink.admin.link;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xingyan.shortlink.admin.pool.ShortCodePoolService;
import com.xingyan.shortlink.common.admission.UrlAdmissionChecker;
import com.xingyan.shortlink.common.error.ErrorCode;
import com.xingyan.shortlink.common.id.SnowflakeIdGenerator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.InetSocketAddress;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * M1-06 验收：生成/查询/更新链路对活体 compose Redis(6380)/MySQL(3307)。
 * 手工装配（不经 Spring 代理，@Transactional 原子性由 HTTP 验收脚本在活应用上覆盖）；
 * 基础设施不可达整体跳过。租户用 seed 的 1001/1002，隔离 ns=9998。
 */
class LinkServiceIntegrationTest {

    private static final int NS = 9998;
    private static final long TENANT_A = 1001;
    private static final long TENANT_B = 1002;
    private static final String OK_URL = "https://mock.ticketsales.test/show/1";

    private static LettuceConnectionFactory redisFactory;
    private static StringRedisTemplate redis;
    private static com.zaxxer.hikari.HikariDataSource ssDs;
    private static JdbcTemplate jdbc;

    private final List<String> createdCodes = new ArrayList<>();
    private final List<Long> usedTenants = List.of(TENANT_A, TENANT_B);
    private LinkService service;

    @BeforeAll
    static void up() {
        assumeTrue(infraUp());
        redisFactory = new LettuceConnectionFactory("127.0.0.1", 6380);
        redisFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(redisFactory);
        redis.afterPropertiesSet();
        com.zaxxer.hikari.HikariConfig cfg = new com.zaxxer.hikari.HikariConfig();
        cfg.setDriverClassName("org.apache.shardingsphere.driver.ShardingSphereDriver");
        cfg.setJdbcUrl("jdbc:shardingsphere:classpath:sharding.yaml?placeholder-type=environment");
        cfg.setMaximumPoolSize(10);
        ssDs = new com.zaxxer.hikari.HikariDataSource(cfg);
        jdbc = new JdbcTemplate(ssDs);
    }

    @AfterAll
    static void down() {
        if (redisFactory != null) redisFactory.destroy();
        if (ssDs != null) ssDs.close();
    }

    @BeforeEach
    void cleanAndWire() {
        assumeTrue(infraUp());
        redis.delete(List.of("sl:pool:" + NS, "sl:pool:" + NS + ":seen", "sl:pool:refill:lock:" + NS));
        jdbc.update("DELETE FROM short_code_pool WHERE namespace = ?", NS);
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        ShortCodePoolService pool = new ShortCodePoolService(redis, jdbc, NS, 200, 0.2);
        service = new LinkService(redis, jdbc, pool,
                new UrlAdmissionChecker(Set.of("mock.ticketsales.test", ".xystage.test")),
                new SnowflakeIdGenerator(3), new LinkTxWriter(jdbc, mapper), mapper, "xy1.test");
    }

    @AfterEach
    void removeCreated() {
        assumeTrue(infraUp());
        if (createdCodes.isEmpty()) {
            return;
        }
        String ph = createdCodes.stream().map(c -> "?").collect(Collectors.joining(","));
        Object[] args = createdCodes.toArray();
        jdbc.update("DELETE FROM short_link WHERE short_code IN (" + ph + ")", args);
        jdbc.update("DELETE FROM link_route WHERE short_code IN (" + ph + ")", args);
        jdbc.update("DELETE FROM code_tenant_index WHERE short_code IN (" + ph + ")", args);
        jdbc.update("DELETE FROM outbox WHERE aggregate_id IN (" + ph + ")", args);
        jdbc.update("DELETE FROM short_code_pool WHERE short_code IN (" + ph + ")", args);
        for (String code : createdCodes) {
            redis.delete(List.of("sl:r:" + code, "sl:code:" + code));
        }
        createdCodes.clear();
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private CreateLinkRequest req(String originUrl, String shortCode, LocalDateTime expire,
                                  Integer accessLimit, Integer redirectType) {
        return req(originUrl, shortCode, expire, accessLimit, redirectType, null);
    }

    private CreateLinkRequest req(String originUrl, String shortCode, LocalDateTime expire,
                                  Integer accessLimit, Integer redirectType, Integer rateLimitPerMinute) {
        return new CreateLinkRequest(originUrl, "ch-wechat", "cp-autumn", "p-9", shortCode, expire,
                accessLimit, redirectType, rateLimitPerMinute);
    }

    private Map<String, Object> createAndTrack(long tenant, CreateLinkRequest r) {
        Map<String, Object> data = service.create(tenant, r);
        createdCodes.add((String) data.get("short_code"));
        return data;
    }

    @Test
    void createWritesAllTablesRouteAndCache() {
        Map<String, Object> data = createAndTrack(TENANT_A, req(OK_URL, null, null, null, null));
        String code = (String) data.get("short_code");
        assertEquals("https://xy1.test/" + code, data.get("short_url"));
        assertEquals(7, code.length(), "池码长度 7");

        Map<String, Object> link = jdbc.queryForMap(
                "SELECT * FROM short_link WHERE tenant_id = ? AND short_code = ?", TENANT_A, code);
        assertEquals(OK_URL, link.get("origin_url"));
        assertEquals("ch-wechat", link.get("channel_id"));
        assertEquals(0, ((Number) link.get("status")).intValue());

        String routeJson = jdbc.queryForObject(
                "SELECT route_json FROM link_route WHERE short_code = ?", String.class, code);
        assertNotNull(routeJson);
        var dbNode = readJson(routeJson);
        assertEquals(OK_URL, dbNode.path("origin_url").asText());
        assertEquals(1, dbNode.path("version").asInt(), routeJson);
        Long idxTenant = jdbc.queryForObject(
                "SELECT tenant_id FROM code_tenant_index WHERE short_code = ?", Long.class, code);
        assertEquals(TENANT_A, idxTenant);

        String event = jdbc.queryForObject(
                "SELECT event_type FROM outbox WHERE aggregate_id = ? AND tenant_id = ?", String.class, code, TENANT_A);
        assertEquals("LINK_CREATED", event);
        Integer pending = jdbc.queryForObject(
                "SELECT status FROM outbox WHERE aggregate_id = ? AND tenant_id = ?", Integer.class, code, TENANT_A);
        assertEquals(0, pending, "outbox 初始待投递");

        String cached = redis.opsForValue().get("sl:r:" + code);
        assertEquals(dbNode, readJson(cached), "Redis 路由缓存与 link_route 语义一致（DB JSON 列会被 MySQL 规范化，按树比较）");
        assertNotNull(redis.opsForValue().get("sl:code:" + code));

        Integer poolStatus = jdbc.queryForObject(
                "SELECT status FROM short_code_pool WHERE short_code = ?", Integer.class, code);
        assertEquals(2, poolStatus, "池码创建后即消耗(status=2)");
    }

    @Test
    void rejectsNonWhitelistedAndNonHttps() {
        var bad = assertThrows(BizException.class, () -> service.create(TENANT_A, req("http://mock.ticketsales.test/p", null, null, null, null)));
        assertEquals(ErrorCode.URL_NOT_ALLOWED, bad.getErrorCode());
        assertEquals(HttpStatus.BAD_REQUEST, bad.getStatus());

        var evil = assertThrows(BizException.class, () -> service.create(TENANT_A, req("https://evil.test/p", null, null, null, null)));
        assertEquals(ErrorCode.URL_NOT_ALLOWED, evil.getErrorCode());

        var trick = assertThrows(BizException.class,
                () -> service.create(TENANT_A, req("https://evil.com@mock.ticketsales.test/p", null, null, null, null)));
        assertTrue(trick.getMessage().contains("userinfo"));
    }

    @Test
    void rejectsInvalidPayload() {
        var noChannel = assertThrows(BizException.class, () -> service.create(TENANT_A,
                new CreateLinkRequest(OK_URL, null, "cp1", null, null, null, null, null, null)));
        assertEquals(ErrorCode.VALIDATION_FAILED, noChannel.getErrorCode());

        var pastExpire = assertThrows(BizException.class,
                () -> service.create(TENANT_A, req(OK_URL, null, LocalDateTime.now().minusDays(1), null, null)));
        assertTrue(pastExpire.getMessage().contains("expire_time"));

        var badLimit = assertThrows(BizException.class,
                () -> service.create(TENANT_A, req(OK_URL, null, null, 0, null)));
        assertTrue(badLimit.getMessage().contains("access_limit"));

        var badRate = assertThrows(BizException.class,
                () -> service.create(TENANT_A, req(OK_URL, null, null, null, null, 0)));
        assertTrue(badRate.getMessage().contains("rate_limit_per_minute"));
    }

    /**
     * M2-11：频控阈值要有真源（short_link 列）并随快照进 link_route 与 Redis 缓存。
     * jump 只读缓存快照——这个字段不在快照里，链接级阈值就等于没实现（只能吃全局默认）。
     */
    @Test
    void rateLimitPerMinuteRoundTripsThroughColumnRouteAndCache() {
        Map<String, Object> data = createAndTrack(TENANT_A, req(OK_URL, null, null, null, null, 5000));
        String code = (String) data.get("short_code");

        Integer column = jdbc.queryForObject(
                "SELECT rate_limit_per_minute FROM short_link WHERE tenant_id = ? AND short_code = ?",
                Integer.class, TENANT_A, code);
        assertEquals(5000, column);
        String routeJson = jdbc.queryForObject(
                "SELECT route_json FROM link_route WHERE short_code = ?", String.class, code);
        assertEquals(5000, readJson(routeJson).path("rate_limit_per_minute").asInt(), routeJson);
        assertEquals(5000, readJson(redis.opsForValue().get("sl:r:" + code))
                .path("rate_limit_per_minute").asInt(), "缓存快照必须带阈值，否则 jump 读不到");

        service.update(TENANT_A, code, new UpdateLinkRequest(null, null, null, 900));
        assertEquals(900, readJson(redis.opsForValue().get("sl:r:" + code))
                .path("rate_limit_per_minute").asInt());

        service.update(TENANT_A, code, new UpdateLinkRequest(null, null, 0, null));
        assertEquals(900, readJson(redis.opsForValue().get("sl:r:" + code))
                .path("rate_limit_per_minute").asInt(), "PATCH 未给出该字段时保持原值（与 access_limit 同语义）");
    }

    @Test
    void customCodeReservedTakenAndValidation() {
        var reserved = assertThrows(BizException.class,
                () -> service.create(TENANT_A, req(OK_URL, "api", null, null, null)));
        assertEquals(ErrorCode.VALIDATION_FAILED, reserved.getErrorCode());

        var badShape = assertThrows(BizException.class,
                () -> service.create(TENANT_A, req(OK_URL, "ab$", null, null, null)));
        assertEquals(ErrorCode.VALIDATION_FAILED, badShape.getErrorCode());

        Map<String, Object> first = createAndTrack(TENANT_A, req(OK_URL, "dupcode01", null, null, null));
        assertEquals("dupcode01", first.get("short_code"));
        var conflict = assertThrows(BizException.class,
                () -> service.create(TENANT_B, req(OK_URL, "dupcode01", null, null, null)));
        assertEquals(ErrorCode.SHORT_CODE_TAKEN, conflict.getErrorCode());
        assertEquals(HttpStatus.CONFLICT, conflict.getStatus());

        var wrongType = assertThrows(BizException.class,
                () -> service.create(TENANT_A, req(OK_URL, null, null, null, 2)));
        assertTrue(wrongType.getMessage().contains("redirect_type"));

        var expired = assertThrows(BizException.class,
                () -> service.create(TENANT_A, req(OK_URL, null, LocalDateTime.now().minusDays(1), null, null)));
        assertTrue(expired.getMessage().contains("expire_time"));
    }

    @Test
    void ownershipChecksOnGet() {
        Map<String, Object> data = createAndTrack(TENANT_A, req(OK_URL, "owncode1", null, null, null));
        String code = (String) data.get("short_code");
        Map<String, Object> view = service.get(TENANT_A, code);
        assertEquals(OK_URL, view.get("origin_url"));

        var forbidden = assertThrows(BizException.class, () -> service.get(TENANT_B, code));
        assertEquals(ErrorCode.LINK_FORBIDDEN, forbidden.getErrorCode());
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatus());

        var missing = assertThrows(BizException.class, () -> service.get(TENANT_A, "nosuchcode"));
        assertEquals(ErrorCode.LINK_NOT_FOUND, missing.getErrorCode());
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatus());
    }

    @Test
    void updateMergesFieldsAndBumpsRouteVersion() {
        Map<String, Object> data = createAndTrack(TENANT_A, req(OK_URL, null, null, null, null));
        String code = (String) data.get("short_code");
        LocalDateTime future = LocalDateTime.now().plusDays(3).withNano(0);

        service.update(TENANT_A, code, new UpdateLinkRequest(future, 5, null, null));
        Map<String, Object> link = jdbc.queryForMap(
                "SELECT * FROM short_link WHERE tenant_id = ? AND short_code = ?", TENANT_A, code);
        assertEquals(5, ((Number) link.get("access_limit")).intValue());
        assertEquals(future, (LocalDateTime) link.get("expire_time"));
        assertEquals(0, ((Number) link.get("status")).intValue(), "未给出字段保持原值");

        String routeJson = jdbc.queryForObject("SELECT route_json FROM link_route WHERE short_code = ?", String.class, code);
        assertEquals(2, readJson(routeJson).path("version").asInt(), routeJson);
        assertEquals(2L, jdbc.queryForObject("SELECT version FROM link_route WHERE short_code = ?", Long.class, code));
        assertEquals(5, readJson(redis.opsForValue().get("sl:r:" + code)).path("access_limit").asInt());

        service.update(TENANT_A, code, new UpdateLinkRequest(null, null, 1, null));
        Map<String, Object> after = jdbc.queryForMap(
                "SELECT * FROM short_link WHERE tenant_id = ? AND short_code = ?", TENANT_A, code);
        assertEquals(1, ((Number) after.get("status")).intValue());
        assertEquals(future, (LocalDateTime) after.get("expire_time"), "PATCH 合并语义：未给字段不被清空");
        assertEquals(5, ((Number) after.get("access_limit")).intValue());
        assertEquals(3L, jdbc.queryForObject("SELECT version FROM link_route WHERE short_code = ?", Long.class, code));

        List<String> events = jdbc.queryForList(
                "SELECT event_type FROM outbox WHERE aggregate_id = ? AND tenant_id = ? ORDER BY id", String.class, code, TENANT_A);
        assertEquals(List.of("LINK_CREATED", "LINK_UPDATED", "LINK_UPDATED"), events);
    }

    @Test
    void crossShardTenantBothWritable() {
        // 1001→ds(tenant%2)=xsl_01、1002→xsl_00：两租户各建一码，均可读回
        Map<String, Object> a = createAndTrack(TENANT_A, req(OK_URL, "multiten01", null, null, null));
        Map<String, Object> b = createAndTrack(TENANT_B, req(OK_URL, "multiten02", null, null, null));
        assertEquals("multiten01", service.get(TENANT_A, "multiten01").get("short_code"));
        assertEquals("multiten02", service.get(TENANT_B, "multiten02").get("short_code"));
        assertNotNull(a.get("id"));
        assertNotNull(b.get("id"));
        assertNotEqualsStr(a.get("id"), b.get("id"));
    }

    private static void assertNotEqualsStr(Object a, Object b) {
        assertTrue(!String.valueOf(a).equals(String.valueOf(b)));
    }

    @Test
    void routeJsonContainsExplicitNulls() {
        createAndTrack(TENANT_A, req(OK_URL, "jsonchk01", null, 100, null));
        var node = readJson(redis.opsForValue().get("sl:r:jsonchk01"));
        assertEquals(100, node.path("access_limit").asInt());
        assertEquals(OK_URL, node.path("origin_url").asText());
        assertTrue(node.get("expire_time").isNull(), "null 字段应显式序列化，跳转侧按 key 读取");
    }

    private static com.fasterxml.jackson.databind.JsonNode readJson(String json) {
        try {
            return JSON.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("invalid json: " + json, e);
        }
    }

    private static boolean infraUp() {
        return portOpen("127.0.0.1", 6380) && portOpen("127.0.0.1", 3307);
    }

    private static boolean portOpen(String host, int port) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new InetSocketAddress(host, port), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
