package com.xingyan.shortlink.admin.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingyan.shortlink.admin.route.RouteCachePublisher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * outbox 同步器（DESIGN 8.4，M3-02）：把"管理面直写 `link_route` + 缓存"这条路的失败补回来。
 *
 * <p>此前 {@code outbox} 只有写方（三处 INSERT，{@code status} 恒 0）。直写成功时看不出问题，
 * 失败时最坏的一种是创建时路由表缺行——{@code short_link} 里链接好好的，跳转却永久 404。
 *
 * <p><b>重放是安全的</b>，这一点是整套补偿的地基，也是 {@code accept-m3-02} 要拿去测的性质：
 * 每次应用都是 {@code UPDATE ... WHERE short_code=? AND version<?} 的条件写，缓存侧同一 version 守卫，
 * 所以一张写满历史事件的 outbox 只会把 {@code link_route} 补齐，不可能把它写回旧值。
 *
 * <p>三条支路各自的含义：{@code applied}=路由表被这条事件推进了；{@code inserted}=路由表缺这一行，补上
 * （连同缺的归属索引）；{@code superseded}=表里已有不低于它的 version，这条事件过期了，直接算投递完成
 * （重试没有意义）。<b>缓存一律写"当时最权威的那一份"</b>：前两条用事件里的快照，第三条用表里那份更高的
 * 去修缓存。反过来说就是任何情况下都不会拿旧事件覆盖缓存——否则会得到"路由表 v5、缓存 v4"，
 * 跳转要等缓存 24h 过期才回到 v5（这一条是被活体测试逼出来的，见 ITER-M3 §4）。
 */
@Component
public class OutboxSyncService {

    private static final Logger log = LoggerFactory.getLogger(OutboxSyncService.class);

    /** 一轮的落库结果；字段名与 {@code POST /internal/outbox/replay} 的响应一致，便于验收脚本直接断言。 */
    public record Summary(int scanned, int applied, int inserted, int superseded, int failed,
                          int backlog, long oldestAgeSeconds) {
    }

    private record Event(long id, long tenantId, String code, String payloadJson, int retryCount) {
    }

    /** 一份可写进缓存的路由快照：可能是这条事件带的，也可能是路由表里那份更高的。 */
    private record Snapshot(String json, long version) {
    }

    private enum Outcome {APPLIED, INSERTED, SUPERSEDED}

    private static final RowMapper<Event> EVENT_ROW =
            (rs, i) -> new Event(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getInt(5));

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final RouteCachePublisher routeCache;
    private final MeterRegistry registry;
    private final boolean enabled;
    private final int batch;
    private final int maxRetry;

    /** 积压指标用常驻的 AtomicLong 承载：Micrometer 对注册进去的对象只持弱引用，每轮 new 一个数就等于没有指标。 */
    private final AtomicLong backlogPending = new AtomicLong();
    private final AtomicLong backlogFailed = new AtomicLong();
    private final AtomicLong oldestAgeSeconds = new AtomicLong();

    public OutboxSyncService(JdbcTemplate jdbc,
                             ObjectMapper mapper,
                             RouteCachePublisher routeCache,
                             MeterRegistry registry,
                             @Value("${xsl.outbox-sync.enabled:true}") boolean enabled,
                             @Value("${xsl.outbox-sync.batch:500}") int batch,
                             @Value("${xsl.outbox-sync.max-retry:10}") int maxRetry) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.routeCache = routeCache;
        this.registry = registry;
        this.enabled = enabled;
        // 上限放宽到 5000 是为了"首次上线"那一趟：同步器出现之前写下的事件全是 status=0，
        // 一轮 200 行会把新事件压在队尾（本机实测积压 7,955 行，见 ITER-M3 §3）。
        this.batch = Math.max(1, Math.min(batch, 5000));
        this.maxRetry = Math.max(1, maxRetry);
        registry.gauge("xsl_outbox_backlog", Tags.of("status", "0"), backlogPending, AtomicLong::doubleValue);
        registry.gauge("xsl_outbox_backlog", Tags.of("status", "2"), backlogFailed, AtomicLong::doubleValue);
        registry.gauge("xsl_outbox_backlog_age_seconds", oldestAgeSeconds, AtomicLong::doubleValue);
    }

    @Scheduled(fixedDelayString = "${xsl.outbox-sync.interval-ms:5000}",
               initialDelayString = "${xsl.outbox-sync.initial-delay-ms:10000}")
    public void scheduled() {
        if (!enabled) {
            return;
        }
        Summary s = syncOnce(null);
        if (s.scanned() > 0) {
            log.info("[outbox] 一轮补偿 scanned={} 推进={} 补行={} 取代={} 失败={} 积压={} 最老={}s",
                    s.scanned(), s.applied(), s.inserted(), s.superseded(), s.failed(),
                    s.backlog(), s.oldestAgeSeconds());
        }
    }

    /**
     * 定时任务与手动触发（{@code POST /internal/outbox/replay}）共用这一份逻辑。
     * {@code onlyCode} 非空时只补偿这一条链接——运维定点修是它的主要用途，顺带解决"大批积压时新事件
     * 排在队尾要等好几轮"（扫描按 id 升序，FIFO 是对的，但等不起的场景该给出口）。
     */
    public Summary syncOnce(String onlyCode) {
        String target = onlyCode == null ? null : onlyCode.trim();
        List<Event> events = target == null || target.isEmpty()
                ? jdbc.query("SELECT id, tenant_id, aggregate_id, payload_json, retry_count FROM outbox "
                        + "WHERE status = 0 ORDER BY id LIMIT " + batch, EVENT_ROW)
                : jdbc.query("SELECT id, tenant_id, aggregate_id, payload_json, retry_count FROM outbox "
                        + "WHERE status = 0 AND aggregate_id = ? ORDER BY id LIMIT " + batch, EVENT_ROW, target);
        int applied = 0;
        int inserted = 0;
        int superseded = 0;
        int failed = 0;
        for (Event e : events) {
            try {
                Outcome outcome = deliver(e);
                markDelivered(e);
                switch (outcome) {
                    case APPLIED -> applied++;
                    case INSERTED -> inserted++;
                    case SUPERSEDED -> superseded++;
                }
            } catch (Exception ex) {
                failed++;
                markFailure(e, ex);
            }
        }
        long pending = readBacklog();
        long age = readOldestAgeSeconds(pending);
        registry.counter("xsl_outbox_sync_total", "result", "applied").increment(applied);
        registry.counter("xsl_outbox_sync_total", "result", "inserted").increment(inserted);
        registry.counter("xsl_outbox_sync_total", "result", "superseded").increment(superseded);
        registry.counter("xsl_outbox_sync_total", "result", "failed").increment(failed);
        return new Summary(events.size(), applied, inserted, superseded, failed, (int) pending, age);
    }

    /** 解析事件 → 条件写路由表 → 按权威值回写缓存并广播。抛出去的就是"这条还没补偿成功"，由调用方计入重试。 */
    private Outcome deliver(Event e) throws Exception {
        JsonNode route = mapper.readTree(e.payloadJson()).path("route_json");
        long version = route.path("version").asLong(0);
        if (version <= 0) {
            throw new IllegalStateException("outbox payload 里没有可用的 route_json.version: id=" + e.id());
        }
        // payload 里的 route_json 是从 MySQL JSON 列读回来的文本，重新序列化一遍拿到与写缓存一致的那一份
        String routeJson = mapper.writeValueAsString(route);
        Outcome outcome;
        Snapshot authoritative;
        if (jdbc.update("UPDATE link_route SET route_json = CAST(? AS JSON), version = ? "
                + "WHERE short_code = ? AND version < ?", routeJson, version, e.code(), version) > 0) {
            outcome = Outcome.APPLIED;
            authoritative = new Snapshot(routeJson, version);
        } else {
            Snapshot stored = readRoute(e.code());
            if (stored == null) {
                jdbc.update("INSERT INTO link_route (short_code, route_json, version) VALUES (?, CAST(? AS JSON), ?)",
                        e.code(), routeJson, version);
                ensureTenantIndex(e.code(), e.tenantId());
                outcome = Outcome.INSERTED;
                authoritative = new Snapshot(routeJson, version);
            } else {
                outcome = Outcome.SUPERSEDED;
                authoritative = stored;
            }
        }
        routeCache.publish(e.code(), authoritative.json(), authoritative.version());
        return outcome;
    }

    /** 表里那一行（可能比这条事件更新）；缺行返回 null。 */
    private Snapshot readRoute(String code) {
        List<Snapshot> rows = jdbc.query("SELECT route_json, version FROM link_route WHERE short_code = ?",
                (rs, i) -> new Snapshot(rs.getString(1), rs.getLong(2)), code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 补行要补<b>一对</b>：创建时那次失败是 {@code insertRouteAndIndex} 整段没跑完，归属索引行同样缺。
     * 只补 {@code link_route} 会得到一个"跳转能 302、管理面按码查归属却 404"的链接——
     * DESIGN 6.4 的越权反查正是走 {@code code_tenant_index}，那种半状态比缺行更难解释。
     */
    private void ensureTenantIndex(String code, long tenantId) {
        List<Long> owners = jdbc.queryForList("SELECT tenant_id FROM code_tenant_index WHERE short_code = ?",
                Long.class, code);
        if (owners.isEmpty()) {
            jdbc.update("INSERT INTO code_tenant_index (short_code, tenant_id) VALUES (?, ?)", code, tenantId);
        }
    }

    private void markDelivered(Event e) {
        jdbc.update("UPDATE outbox SET status = 1 WHERE id = ? AND tenant_id = ? AND status = 0",
                e.id(), e.tenantId());
    }

    /** 失败留在 0 等下一轮；到上限就置 2 停掉自动重试——一条坏 payload 不该每五秒把整批的额度吃一遍。 */
    private void markFailure(Event e, Exception ex) {
        int retried = e.retryCount() + 1;
        int status = retried >= maxRetry ? 2 : 0;
        jdbc.update("UPDATE outbox SET status = ?, retry_count = ? WHERE id = ? AND tenant_id = ?",
                status, retried, e.id(), e.tenantId());
        if (status == 2) {
            log.error("[outbox] 补偿连续失败达上限，置为失败等人工处理 id={} code={} retry={}: {}",
                    e.id(), e.code(), retried, ex.toString());
        } else {
            log.warn("[outbox] 补偿失败，下一轮重试 id={} code={} retry={}: {}",
                    e.id(), e.code(), retried, ex.toString());
        }
    }

    /**
     * 积压与年龄。两个 count 都走 {@code idx_status_create}，不带分片键会广播到 xsl_00/xsl_01 再归并——
     * outbox 是"每改一次配置多一行"的量级，5s 一次的四条查询实测在 accept-m3-02 的耗时项里给数字。
     */
    private long readBacklog() {
        Long pending = jdbc.queryForObject("SELECT count(*) FROM outbox WHERE status = 0", Long.class);
        Long dead = jdbc.queryForObject("SELECT count(*) FROM outbox WHERE status = 2", Long.class);
        backlogPending.set(pending == null ? 0 : pending);
        backlogFailed.set(dead == null ? 0 : dead);
        return backlogPending.get();
    }

    private long readOldestAgeSeconds(long pending) {
        long age = 0;
        if (pending > 0) {
            Timestamp oldest = jdbc.queryForObject("SELECT MIN(create_time) FROM outbox WHERE status = 0",
                    Timestamp.class);
            if (oldest != null) {
                age = Math.max(0, Instant.now().getEpochSecond() - oldest.toInstant().getEpochSecond());
            }
        }
        oldestAgeSeconds.set(age);
        return age;
    }
}
