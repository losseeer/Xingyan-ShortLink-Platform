package com.xingyan.shortlink.admin.link;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xingyan.shortlink.admin.pool.ShortCodePoolService;
import com.xingyan.shortlink.admin.route.RouteCachePublisher;
import com.xingyan.shortlink.common.admission.UrlAdmissionChecker;
import com.xingyan.shortlink.common.error.ErrorCode;
import com.xingyan.shortlink.common.id.SnowflakeIdGenerator;
import com.xingyan.shortlink.common.route.RouteRule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /**
     * 本类写死复用的自定义短码。{@code @AfterEach} 只清"本轮真正建出来的那些"，所以上一轮被打断
     * （Ctrl-C、容器重启、或 infraUp() 在那一刻返回 false 让收尾的 assumeTrue 直接跳过）就会把它们留在库里，
     * 下一轮 create() 于是 409 "custom short code already used"——红的是开局而不是被测行为（M3-04 回归就撞上了）。
     * 开局按名字扫一遍，把"能不能重跑"从"上一轮是否跑完"手里拿回来：这些名字归本类专用，扫掉的只可能是自己的 fixture。
     */
    private static final List<String> FIXED_CODES = List.of("dupcode01", "owncode1", "multiten01",
            "multiten02", "jsonchk01", "ruleord01", "ruletype1");

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
        sweepFixedCodes();
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        ShortCodePoolService pool = new ShortCodePoolService(redis, jdbc, NS, 200, 0.2);
        UrlAdmissionChecker checker = new UrlAdmissionChecker(Set.of("mock.ticketsales.test", ".xystage.test"));
        RouteRuleService ruleService = new RouteRuleService(jdbc, checker);
        // 直写路径现在会把新值按 version 守卫写进 L2 并发一次失效广播（DESIGN 8.4，M3-02）；
        // 测试里没有订阅方，广播就是一条没人收的发布，只多一个计数器。
        RouteCachePublisher routeCache = new RouteCachePublisher(redis, new SimpleMeterRegistry());
        // 生效域走"关掉选域池"的那条路：本类的断言是 short_url == https://xy1.test/{code}，
        // 那它必须钉住一个确定的域。domain_pool 的真实选域由 DomainPolicyLiveTest 单独测（DESIGN 9.5，M3-10）
        DomainPolicy domains = new DomainPolicy(false, "127.0.0.1", 3307, "root", "", "xy1.test", 30,
                new SimpleMeterRegistry());
        service = new LinkService(redis, jdbc, pool, checker,
                new SnowflakeIdGenerator(3), new LinkTxWriter(jdbc, mapper, ruleService), mapper,
                ruleService, routeCache, domains);
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
        jdbc.update("DELETE FROM route_rule WHERE short_code IN (" + ph + ")", args);
        jdbc.update("DELETE FROM short_code_pool WHERE short_code IN (" + ph + ")", args);
        for (String code : createdCodes) {
            redis.delete(List.of("sl:r:" + code, "sl:code:" + code));
        }
        createdCodes.clear();
    }

    private void sweepFixedCodes() {
        String ph = FIXED_CODES.stream().map(c -> "?").collect(Collectors.joining(","));
        Object[] args = FIXED_CODES.toArray();
        for (String table : List.of("short_link", "link_route", "code_tenant_index", "route_rule", "short_code_pool")) {
            jdbc.update("DELETE FROM " + table + " WHERE short_code IN (" + ph + ")", args);
        }
        jdbc.update("DELETE FROM outbox WHERE aggregate_id IN (" + ph + ")", args);
        for (String code : FIXED_CODES) {
            redis.delete(List.of("sl:r:" + code, "sl:code:" + code));
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private CreateLinkRequest req(String originUrl, String shortCode, LocalDateTime expire,
                                  Integer accessLimit, Integer redirectType) {
        return req(originUrl, shortCode, expire, accessLimit, redirectType, null);
    }

    private CreateLinkRequest req(String originUrl, String shortCode, LocalDateTime expire,
                                  Integer accessLimit, Integer redirectType, Integer rateLimitPerMinute) {
        return new CreateLinkRequest(originUrl, "ch-wechat", "cp-autumn", "p-9", shortCode, expire,
                accessLimit, redirectType, rateLimitPerMinute, null);
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
                new CreateLinkRequest(OK_URL, null, "cp1", null, null, null, null, null, null, null)));
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

    // ---------------- M2-31 route_rule 双轨：编译产物（route_json.rules）+ 行式真源（route_rule） ----------------

    @Test
    void rulesCompileIntoSortedRouteJsonAndRows() {
        // 故意乱序提交：编译产物必须按 priority 升序，数据面靠"第一个匹配"决策，顺序就是语义
        List<RouteRule> incoming = List.of(
                h5(20, RouteRule.DEVICE, "in", "ios,android", "https://mock.ticketsales.test/app-h5"),
                h5(5, RouteRule.UA_CONTAINER, "eq", "wechat", "https://mock.ticketsales.test/wx"),
                h5(10, RouteRule.GEO_PROVINCE, "eq", "上海", "https://mock.ticketsales.test/sh"));
        Map<String, Object> data = createAndTrack(TENANT_A,
                new CreateLinkRequest(OK_URL, "ch-wechat", "cp-autumn", "p-9", "ruleord01", null, null, null, null, incoming));

        assertEquals(List.of(5, 10, 20), priorities(data.get("rules")), "响应里的规则要有序");

        var dbRules = readJson(jdbc.queryForObject(
                "SELECT route_json FROM link_route WHERE short_code = ?", String.class, "ruleord01")).path("rules");
        assertEquals(List.of(5, 10, 20), jsonPriorities(dbRules), "route_json.rules 是编译产物，必须已排序");
        assertEquals("wechat", dbRules.get(0).path("condition_value").asText());
        assertTrue(dbRules.get(0).has("condition_type"), "快照里的键是 snake_case（jump 用同一份命名策略反序列化）");

        var cachedRules = readJson(redis.opsForValue().get("sl:r:ruleord01")).path("rules");
        assertEquals(jsonPriorities(dbRules), jsonPriorities(cachedRules), "缓存与 link_route 必须是同一份编译产物");

        List<Integer> rowPriorities = jdbc.queryForList(
                "SELECT priority FROM route_rule WHERE tenant_id = ? AND short_code = ? ORDER BY priority",
                Integer.class, TENANT_A, "ruleord01");
        assertEquals(List.of(5, 10, 20), rowPriorities, "行式真源与编译产物同源同序");
    }

    @Test
    void nonH5TargetsRoundTripThroughColumns() {
        // app_scheme 用 scheme_url+fallback_url、wx_urllink 用 scheme_url 位放小程序 path：
        // 这两类走的是 DDL 新加的列，不落行就等于"M2-33 的中间页拿不到降级目标"
        createAndTrack(TENANT_A, new CreateLinkRequest(OK_URL, "ch-wechat", "cp-autumn", "p-9", "ruletype1",
                null, null, null, null, List.of(
                new RouteRule(1, RouteRule.UA_CONTAINER, "eq", "wechat", RouteRule.T_WX_URLLINK,
                        "https://mock.ticketsales.test/mp-fallback", "/pages/order/detail?id=9", null),
                new RouteRule(2, RouteRule.DEVICE, "eq", "ios", RouteRule.T_APP_SCHEME,
                        null, "xyapp://open/order?id=9", "https://mock.ticketsales.test/dl-guide"))));

        var rules = readJson(jdbc.queryForObject(
                "SELECT route_json FROM link_route WHERE short_code = ?", String.class, "ruletype1")).path("rules");
        assertEquals("wx_urllink", rules.get(0).path("target_type").asText());
        assertEquals("/pages/order/detail?id=9", rules.get(0).path("scheme_url").asText());
        assertEquals("xyapp://open/order?id=9", rules.get(1).path("scheme_url").asText());
        assertEquals("https://mock.ticketsales.test/dl-guide", rules.get(1).path("fallback_url").asText());
        assertFalse(rules.get(0).has("default"), "派生方法不该被序列化进快照");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT target_type, target_url, scheme_url, fallback_url FROM route_rule WHERE short_code = ? AND priority = 2",
                "ruletype1");
        assertEquals("app_scheme", row.get("target_type"));
        assertEquals("xyapp://open/order?id=9", row.get("scheme_url"));
        assertEquals("https://mock.ticketsales.test/dl-guide", row.get("fallback_url"));
        assertNull(row.get("target_url"), "app_scheme 不填 target_url");
    }

    @Test
    void replaceRulesBumpsVersionAndKeepsMetadata() {
        createAndTrack(TENANT_A, new CreateLinkRequest(OK_URL, "ch-wechat", "cp-autumn", "p-9", "ruleput01",
                null, 700, null, 3000, List.of(h5(1, RouteRule.UA_CONTAINER, "eq", "douyin", OK_URL))));

        Map<String, Object> result = service.updateRules(TENANT_A, "ruleput01", List.of(
                h5(2, RouteRule.DEVICE, "eq", "desktop", "https://mock.ticketsales.test/pc"),
                h5(1, RouteRule.UA_CONTAINER, "eq", "alipay", "https://mock.ticketsales.test/alipay")));

        assertEquals(List.of(1, 2), priorities(result.get("rules")));
        assertEquals(2L, result.get("route_version"), "PUT 规则是一次发布：version 必须递增");
        assertEquals(2L, jdbc.queryForObject("SELECT version FROM link_route WHERE short_code = ?", Long.class, "ruleput01"));

        var snapshot = readJson(redis.opsForValue().get("sl:r:ruleput01"));
        assertEquals(List.of(1, 2), jsonPriorities(snapshot.path("rules")));
        assertEquals(700, snapshot.path("access_limit").asInt(), "换规则不得动元数据");
        assertEquals(3000, snapshot.path("rate_limit_per_minute").asInt());
        assertEquals(OK_URL, snapshot.path("origin_url").asText());

        List<String> conditions = jdbc.queryForList(
                "SELECT CONCAT(condition_type,':',condition_value) FROM route_rule WHERE short_code = ? ORDER BY priority",
                String.class, "ruleput01");
        assertEquals(List.of("ua_container:alipay", "device:desktop"), conditions, "整体替换：旧规则不留行");

        List<String> events = jdbc.queryForList(
                "SELECT event_type FROM outbox WHERE aggregate_id = ? AND tenant_id = ? ORDER BY id",
                String.class, "ruleput01", TENANT_A);
        assertEquals(List.of("LINK_CREATED", "LINK_UPDATED"), events);
    }

    @Test
    void patchMetadataDoesNotClearRules() {
        createAndTrack(TENANT_A, new CreateLinkRequest(OK_URL, "ch-wechat", "cp-autumn", "p-9", "rulekeep1",
                null, null, null, null, List.of(h5(1, RouteRule.UA_CONTAINER, "eq", "weibo", OK_URL),
                h5(2, RouteRule.DEFAULT, "any", null, "https://mock.ticketsales.test/fallback"))));

        service.update(TENANT_A, "rulekeep1", new UpdateLinkRequest(LocalDateTime.now().plusDays(2), 9, null, null));

        var snapshot = readJson(jdbc.queryForObject(
                "SELECT route_json FROM link_route WHERE short_code = ?", String.class, "rulekeep1"));
        assertEquals(2, snapshot.path("rules").size(), "PATCH 只改元数据，编译产物里的 rules 不能掉");
        assertEquals(9, snapshot.path("access_limit").asInt());
        assertEquals(snapshot.path("rules"), readJson(redis.opsForValue().get("sl:r:rulekeep1")).path("rules"),
                "缓存与 link_route 是同一份产物（DB JSON 列会重排键序，按树比较）");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM route_rule WHERE short_code = ?", Integer.class, "rulekeep1"));
    }

    @Test
    void rejectsIllegalRuleSetsWith400() {
        // 每类都要"改一处即可复现"的粒度：错误信息带上可选值，规则是给人配的
        assertRuleRejected(List.of(h5(1, "ua_country", "eq", "cn", OK_URL)), "condition_type");
        assertRuleRejected(List.of(h5(1, RouteRule.UA_CONTAINER, "gt", "qq", OK_URL)), "not allowed");
        assertRuleRejected(List.of(h5(1, RouteRule.UA_CONTAINER, "eq", "instagram", OK_URL)), "unknown ua_container");
        assertRuleRejected(List.of(h5(1, RouteRule.DEVICE, "eq", "symbian", OK_URL)), "unknown device");
        assertRuleRejected(List.of(
                h5(1, RouteRule.UA_CONTAINER, "eq", "qq", OK_URL),
                h5(1, RouteRule.DEVICE, "eq", "ios", OK_URL)), "duplicated priority");
        assertRuleRejected(List.of(
                h5(1, RouteRule.DEFAULT, "any", null, OK_URL),
                h5(2, RouteRule.DEFAULT, "any", null, OK_URL)), "default");
        assertRuleRejected(List.of(
                h5(1, RouteRule.UA_CONTAINER, "eq", "qq", OK_URL),
                h5(2, RouteRule.UA_CONTAINER, "eq", "qq", "https://mock.ticketsales.test/other")), "duplicated condition");
        assertRuleRejected(List.of(h5(1, RouteRule.TIME_WINDOW, "between", "[\"2026-09-30\",\"2026-09-20\"]", OK_URL)),
                "must not be after");
        assertRuleRejected(List.of(h5(1, RouteRule.TIME_WINDOW, "between", "2026-09-20", OK_URL)), "JSON array");
        assertRuleRejected(List.of(h5(1, RouteRule.UA_CONTAINER, "eq", "qq", "https://evil.test/p")), "target_url rejected");
        assertRuleRejected(List.of(new RouteRule(1, RouteRule.UA_CONTAINER, "eq", "qq",
                RouteRule.T_APP_SCHEME, null, null, OK_URL)), "needs a valid scheme_url");
        assertRuleRejected(List.of(new RouteRule(1, RouteRule.UA_CONTAINER, "eq", "qq",
                RouteRule.T_APP_SCHEME, null, "https://mock.ticketsales.test/x", null)), "scheme_url must not be http(s)");
        assertRuleRejected(List.of(new RouteRule(1, RouteRule.UA_CONTAINER, "eq", "qq",
                RouteRule.T_APP_SCHEME, null, "xyapp://open/order", "http://mock.ticketsales.test/fb")), "fallback_url rejected");

        assertTrue(jdbc.queryForList("SELECT 1 FROM code_tenant_index WHERE short_code = ?", Integer.class, "badrule01").isEmpty(),
                "规则非法时不应占用短码（校验在 allocateCode 之前）");
    }

    @Test
    void rulesAreTenantScoped() {
        createAndTrack(TENANT_A, new CreateLinkRequest(OK_URL, "ch-wechat", "cp-autumn", "p-9", "rulescope",
                null, null, null, null, List.of(h5(1, RouteRule.UA_CONTAINER, "eq", "qq", OK_URL))));

        var forbidden = assertThrows(BizException.class, () -> service.updateRules(TENANT_B, "rulescope",
                List.of(h5(1, RouteRule.UA_CONTAINER, "eq", "douyin", OK_URL))));
        assertEquals(ErrorCode.LINK_FORBIDDEN, forbidden.getErrorCode());
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatus());

        assertEquals(List.of("ua_container:qq"), jdbc.queryForList(
                "SELECT CONCAT(condition_type,':',condition_value) FROM route_rule WHERE short_code = ?", String.class, "rulescope"));
        assertEquals(1L, jdbc.queryForObject("SELECT version FROM link_route WHERE short_code = ?", Long.class, "rulescope"),
                "越权请求不得留下任何写痕");

        // 同名规则在另一租户下是独立的一行：分片轴 tenant_id 决定它落在哪个库
        createAndTrack(TENANT_B, new CreateLinkRequest(OK_URL, "ch-wechat", "cp-autumn", "p-9", "rulescopb",
                null, null, null, null, List.of(h5(1, RouteRule.DEVICE, "eq", "ios", OK_URL))));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM route_rule WHERE short_code = ?", Integer.class, "rulescopb"));
    }

    private void assertRuleRejected(List<RouteRule> rules, String expectedMessagePart) {
        var e = assertThrows(BizException.class, () -> createAndTrack(TENANT_A,
                new CreateLinkRequest(OK_URL, "ch-wechat", "cp-autumn", "p-9", "badrule01",
                        null, null, null, null, rules)), expectedMessagePart);
        assertEquals(ErrorCode.VALIDATION_FAILED, e.getErrorCode());
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertTrue(e.getMessage().contains(expectedMessagePart), e.getMessage());
    }

    private static RouteRule h5(int priority, String conditionType, String op, String value, String targetUrl) {
        return new RouteRule(priority, conditionType, op, value, RouteRule.T_H5, targetUrl, null, null);
    }

    private static List<Integer> priorities(Object rules) {
        return ((List<?>) rules).stream().map(r -> ((RouteRule) r).priority()).toList();
    }

    private static List<Integer> jsonPriorities(com.fasterxml.jackson.databind.JsonNode rules) {
        List<Integer> out = new ArrayList<>();
        rules.forEach(r -> out.add(r.path("priority").asInt()));
        return out;
    }

    private static com.fasterxml.jackson.databind.JsonNode readJson(String json) {        try {
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
