package com.xingyan.shortlink.jump;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * access_limit 剩余次数原子扣减（DESIGN 4.3 sl:cnt:{code}，Lua 原子、常驻键与 DB 定期对账留 M2）。
 * 首次见键时以 access_limit 初始化；返回扣减后余数，-1 表示已超限。
 */
@Component
public class AccessCounter {

    private static final RedisScript<Long> CONSUME = new DefaultRedisScript<>("""
            local v = redis.call('GET', KEYS[1])
            if not v then
              redis.call('SET', KEYS[1], ARGV[1])
              v = ARGV[1]
            end
            local n = tonumber(v)
            if n <= 0 then
              return -1
            end
            return redis.call('DECR', KEYS[1])
            """, Long.class);

    private final StringRedisTemplate redis;

    public AccessCounter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean tryConsume(String code, int accessLimit) {
        Long remain = redis.execute(CONSUME, List.of("sl:cnt:" + code), String.valueOf(accessLimit));
        return remain == null || remain >= 0;
    }
}
