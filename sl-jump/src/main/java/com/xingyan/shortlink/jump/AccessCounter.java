package com.xingyan.shortlink.jump;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * access_limit 剩余次数原子扣减（DESIGN 4.3 {@code sl:cnt:{code}}、8.4 一致性口径）。
 *
 * <p>M2-12 补的是"键没了怎么办"。M1 版在键缺失时直接按配置的上限重新播种，等于
 * Redis 一旦丢数据（ flush / 主从切换 / 换实例），配额就被清零重来 —— 超放。现在：
 * <ol>
 *   <li>热路径不变：一次 EVAL，键在就 {@code DECR}；</li>
 *   <li>键不在且调用方没给种子 → 脚本返回 {@code -2}，调用方去读 {@code link_route.access_used}
 *       快照（管理面每分钟从 Redis 权威值回写），按 {@code 上限 − 已用 − 保守缓冲} 重新播种；</li>
 *   <li>缓冲要覆盖一个对账周期内已发生但未进快照的扣减量，方向是"宁可少放不可超放"。</li>
 * </ol>
 *
 * <p>Redis 完全不可用时仍然放行（{@code degraded_allow} 指标可查）：DESIGN 8.3 明确
 * "Redis 挂 → 跳转成功率不跌零"，配额保证让位于可用性，这条取舍写在注释里而不是藏在代码里。
 */
@Component
public class AccessCounter {

    private static final Logger log = LoggerFactory.getLogger(AccessCounter.class);
    private static final long SEEDED_BY_CALLER_MISSING = -2L;

    private static final RedisScript<Long> CONSUME = new DefaultRedisScript<>("""
            local v = redis.call('GET', KEYS[1])
            if not v then
              local seed = tonumber(ARGV[1])
              if seed < 0 then
                return -2
              end
              redis.call('SET', KEYS[1], seed)
              v = seed
            end
            local n = tonumber(v)
            if n <= 0 then
              return -1
            end
            return redis.call('DECR', KEYS[1])
            """, Long.class);

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final MeterRegistry registry;
    private final int rebuildBuffer;

    public AccessCounter(StringRedisTemplate redis,
                         JdbcTemplate jdbc,
                         MeterRegistry registry,
                         @Value("${xsl.jump.access-rebuild-buffer:100}") int rebuildBuffer) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.registry = registry;
        this.rebuildBuffer = Math.max(rebuildBuffer, 0);
    }

    /** @return true 表示本次点击获准消耗一格配额 */
    public boolean tryConsume(String code, int accessLimit) {
        Long remain = execute(code, -1);
        if (remain == null) {
            return true;                       // Redis 不可用 → 放行（8.3），不让配额组件把跳转打死
        }
        if (remain == SEEDED_BY_CALLER_MISSING) {
            remain = execute(code, seedFromSnapshot(code, accessLimit));
            registry.counter("xsl_jump_access_counter_total", "result", "rebuild").increment();
        }
        return remain == null || remain >= 0;
    }

    private Long execute(String code, long seed) {
        try {
            return redis.execute(CONSUME, List.of("sl:cnt:" + code), String.valueOf(seed));
        } catch (Exception e) {
            registry.counter("xsl_jump_access_counter_total", "result", "degraded_allow").increment();
            log.warn("[jump] 配额扣减不可用，本次放行 code={}: {}", code, e.toString());
            return null;
        }
    }

    /**
     * 冷重建的初值：{@code 上限 − 快照已用 − 缓冲}，下限 0。
     *
     * <p><b>"键不存在"有两种</b>：从没被点过的新链接（没有需要保护的历史，该给满额），
     * 与计数器丢了（必须按快照收口）。区分信号是 {@code access_used_at}：对账只在 Redis 键
     * 存在时回写，所以它是 NULL 就意味着这条链接从没消耗过配额。<br>
     * 这个区分是 accept-m2-13 的 D1 抓出来的——之前把两者混为一谈，结果<b>每条新链接的首击
     * 都要被扣掉一个缓冲</b>（配额 50 实测只剩 35 可用）。
     *
     * <p>缓冲本身是绝对次数口径（默认 100，按链接峰值调：它要盖住"已扣但还没进快照"的量，
     * 最长一个对账周期），但再被<b>剩余额度的 1/4</b> 封顶——否则 {@code access_limit=5} 这类
     * 小配额链接一旦在尾段重建就会被直接打死（把"少放"做成了"拒服"）。
     */
    private int seedFromSnapshot(String code, int accessLimit) {
        Map<String, Object> row;
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT access_used, access_used_at FROM link_route WHERE short_code = ?", code);
            row = rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            log.warn("[jump] 读配额快照失败，按满额重建 code={}: {}", code, e.toString());
            row = null;
        }
        if (row == null || row.get("access_used_at") == null) {
            return accessLimit;          // 没有历史可保护：新链接（或快照读不到）按满额
        }
        int used = ((Number) row.get("access_used")).intValue();
        int remaining = Math.max(accessLimit - used, 0);
        int effectiveBuffer = Math.min(rebuildBuffer, remaining / 4);
        return Math.max(remaining - effectiveBuffer, 0);
    }
}
