package com.xingyan.shortlink.admin.pool;

import com.xingyan.shortlink.common.codec.ShortCodeGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 预生成短码池（DESIGN 5.1/4.3）：Redis List 供给、SADD 唯一集防碰撞、
 * SETNX 锁防多实例重复补充、DB 登记租约。LPOP count 原子弹出保证并发租用不重。
 * M1 仅管理面（单实例）使用；补池同步执行，异步化留 M2。
 */
@Component
public class ShortCodePoolService {

    private static final Logger log = LoggerFactory.getLogger(ShortCodePoolService.class);
    private static final int MAX_COLLISION_ATTEMPTS_FACTOR = 3;

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final int namespace;
    private final long capacity;
    private final double refillRatio;

    public ShortCodePoolService(StringRedisTemplate redis,
                                JdbcTemplate jdbc,
                                @Value("${xsl.pool.namespace:0}") int namespace,
                                @Value("${xsl.pool.capacity:5000}") long capacity,
                                @Value("${xsl.pool.refill-ratio:0.2}") double refillRatio) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.namespace = namespace;
        this.capacity = capacity;
        this.refillRatio = refillRatio;
    }

    String poolKey(int ns) {
        return "sl:pool:" + ns;
    }

    private String seenKey(int ns) {
        return "sl:pool:" + ns + ":seen";
    }

    private String refillLockKey(int ns) {
        return "sl:pool:refill:lock:" + ns;
    }

    public List<String> lease(int count) {
        Long size = redis.opsForList().size(poolKey(namespace));
        if (size == null || size < capacity * refillRatio) {
            refill();
        }
        List<String> codes = drain(count);
        if (codes.isEmpty()) {
            throw new IllegalStateException("short code pool exhausted even after refill (ns=" + namespace + ")");
        }
        markLeased(codes);
        return codes;
    }

    /** LPOP count 原子弹出，并发调用各自拿到不相交批次 */
    private List<String> drain(int count) {
        List<String> popped = redis.opsForList().leftPop(poolKey(namespace), count);
        return popped == null ? List.of() : new ArrayList<>(popped);
    }

    public int refill() {
        Boolean locked = redis.opsForValue()
                .setIfAbsent(refillLockKey(namespace), "instance", Duration.ofSeconds(60));
        if (!Boolean.TRUE.equals(locked)) {
            log.info("[pool] ns={} 他实例补池中，本次跳过", namespace);
            return 0;
        }
        try {
            long size = size();
            int need = (int) Math.max(0, capacity - size);
            if (need == 0) {
                return 0;
            }
            List<String> fresh = generateUnique(need);
            if (!fresh.isEmpty()) {
                redis.opsForList().rightPushAll(poolKey(namespace), fresh);
                registerInDb(fresh);
            }
            log.info("[pool] 补水位 ns={} 目标={} 补充={} 现水位={}", namespace, capacity, fresh.size(), size());
            return fresh.size();
        } finally {
            redis.delete(refillLockKey(namespace));
        }
    }

    private List<String> generateUnique(int need) {
        List<String> fresh = new ArrayList<>(need);
        int attempts = need * MAX_COLLISION_ATTEMPTS_FACTOR;
        while (fresh.size() < need && attempts-- > 0) {
            String code = ShortCodeGenerator.next();
            Long added = redis.opsForSet().add(seenKey(namespace), code);
            if (added != null && added == 1) {
                fresh.add(code);
            }
        }
        return fresh;
    }

    private void registerInDb(List<String> codes) {
        List<Object[]> batch = new ArrayList<>(codes.size());
        for (String code : codes) {
            batch.add(new Object[]{code, namespace, 0});
        }
        jdbc.batchUpdate("INSERT IGNORE INTO short_code_pool (short_code, namespace, status) VALUES (?, ?, ?)", batch);
    }

    private void markLeased(List<String> codes) {
        for (int i = 0; i < codes.size(); i += 500) {
            List<String> chunk = codes.subList(i, Math.min(codes.size(), i + 500));
            String placeholders = String.join(",", chunk.stream().map(c -> "?").toList());
            Object[] args = new Object[chunk.size() + 1];
            args[0] = "admin-ns" + namespace;
            for (int j = 0; j < chunk.size(); j++) {
                args[j + 1] = chunk.get(j);
            }
            jdbc.update("UPDATE short_code_pool SET status = 1, lease_owner = ?, lease_time = NOW() "
                    + "WHERE short_code IN (" + placeholders + ")", args);
        }
    }

    public long size() {
        Long size = redis.opsForList().size(poolKey(namespace));
        return size == null ? 0 : size;
    }
}
