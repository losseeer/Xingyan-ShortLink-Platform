package com.xingyan.shortlink.admin.recon;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * access_limit 对账（DESIGN 8.4）：Redis Lua 扣减是权威值，本任务把权威值镜像进
 * {@code link_route.access_used}，让 Redis 丢数据时 jump 能按"上限 − 已用 − 缓冲"重建，
 * 而不是重新播种成满额（那等于把超放写进设计里）。
 *
 * <p>刻意放在管理面：数据面继续保持"只写 Redis、不写业务表"（DESIGN 3.2/5.2 的面分离，
 * 也是 accept-m1-10 的 D 段"管理面宕机不影响跳转"能成立的前提）。快照是<b>独立列</b>而不是
 * {@code route_json} 的字段——route_json 带 version、是缓存契约，每分钟一次的写会把 L1/L2 全部打穿。
 */
@Component
@EnableScheduling
public class AccessQuotaReconciler {

    private static final Logger log = LoggerFactory.getLogger(AccessQuotaReconciler.class);

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final MeterRegistry registry;
    private final boolean enabled;
    private final int chunk;

    public AccessQuotaReconciler(JdbcTemplate jdbc,
                                 StringRedisTemplate redis,
                                 MeterRegistry registry,
                                 @Value("${xsl.quota-reconcile.enabled:true}") boolean enabled,
                                 @Value("${xsl.quota-reconcile.chunk:500}") int chunk) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.registry = registry;
        this.enabled = enabled;
        this.chunk = Math.max(chunk, 1);
    }

    /** 一轮对账的落库结果，便于验收脚本直接断言。 */
    public record Summary(int tracked, int updated, int unchanged, int noKey) {
    }

    @Scheduled(fixedDelayString = "${xsl.quota-reconcile.interval-ms:60000}",
               initialDelayString = "${xsl.quota-reconcile.initial-delay-ms:20000}")
    public void scheduled() {
        if (!enabled) {
            return;
        }
        Summary s = reconcileOnce();
        if (s.updated() > 0) {
            log.info("[quota] 对账完成 tracked={} 回写={} 未变={} 无键={}", s.tracked(), s.updated(), s.unchanged(), s.noKey());
        }
    }

    public Summary reconcileOnce() {
        List<Object[]> targets = jdbc.query(
                "SELECT short_code, access_limit FROM short_link WHERE access_limit IS NOT NULL",
                (rs, i) -> new Object[]{rs.getString(1), rs.getInt(2)});
        int updated = 0;
        int unchanged = 0;
        int noKey = 0;
        for (int from = 0; from < targets.size(); from += chunk) {
            List<Object[]> part = targets.subList(from, Math.min(from + chunk, targets.size()));
            List<String> keys = new ArrayList<>(part.size());
            for (Object[] row : part) {
                keys.add("sl:cnt:" + row[0]);
            }
            List<String> remains = redis.opsForValue().multiGet(keys);
            if (remains == null) {
                remains = List.of();
            }
            for (int i = 0; i < part.size(); i++) {
                String code = (String) part.get(i)[0];
                int limit = (Integer) part.get(i)[1];
                String raw = i < remains.size() ? remains.get(i) : null;
                if (raw == null) {
                    noKey++;          // 键还没被创建（链接没被点过）或已被清走：保留上一次快照
                    continue;
                }
                int used = Math.max(limit - parseInt(raw, limit), 0);
                int n = jdbc.update("UPDATE link_route SET access_used = ?, access_used_at = NOW() "
                        + "WHERE short_code = ? AND access_used <> ?", used, code, used);
                if (n > 0) {
                    updated++;
                } else {
                    unchanged++;
                }
            }
        }
        registry.counter("xsl_quota_reconcile_total", "result", "updated").increment(updated);
        registry.counter("xsl_quota_reconcile_total", "result", "unchanged").increment(unchanged);
        registry.counter("xsl_quota_reconcile_total", "result", "no_key").increment(noKey);
        registry.gauge("xsl_quota_reconcile_tracked", targets.size());
        return new Summary(targets.size(), updated, unchanged, noKey);
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
