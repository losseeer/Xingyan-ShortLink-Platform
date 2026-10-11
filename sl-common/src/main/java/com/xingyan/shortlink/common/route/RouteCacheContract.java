package com.xingyan.shortlink.common.route;

/**
 * 路由缓存的两端契约（DESIGN 8.4，M3-02）：写方 sl-admin、读方 sl-jump×2。
 *
 * <p>这两样东西必须<b>逐字符相同</b>，所以放在零依赖的 sl-common 而不是各写一份。它们出错时的
 * 失败形态都不是异常：通道串写错 → 广播没人收，缓存仍旧等 30s TTL 才收敛；两份 version 守卫 Lua
 * 语义漂移 → 旧值能盖新值，跳转偶尔回到过期配置。这类"看起来在跑、其实没生效"的坑本项目已吃过几次
 * （ITER-M2 的 UA 词汇表、ITER-M3 的假 reload 核对），所以宁可在这里多一个类。
 */
public final class RouteCacheContract {

    /**
     * 缓存失效广播通道。载荷就是短码字符串——订阅方只做一件事（丢掉本地那一份），不必传 JSON。
     * 尽力而为、不持久化：丢消息的代价是退化成 L1 的 30s TTL，正确性由 {@link #SET_IF_NEWER_LUA} 那两条条件写保住。
     */
    public static final String EVICT_CHANNEL = "xsl:route:evict";

    /**
     * 「只允许把缓存变得更新」的 Lua：当前值的 version 不低于要写的 version 就原样返回 0、不动缓存。
     * KEYS[1]=sl:r:{code}，ARGV[1]=route_json，ARGV[2]=version，ARGV[3]=TTL 秒。
     */
    public static final String SET_IF_NEWER_LUA = """
            local cur = redis.call('GET', KEYS[1])
            if cur then
              local ok, obj = pcall(cjson.decode, cur)
              if ok and obj and tonumber(obj.version) and tonumber(obj.version) >= tonumber(ARGV[2]) then
                return 0
              end
            end
            redis.call('SET', KEYS[1], ARGV[1], 'EX', tonumber(ARGV[3]))
            return 1
            """;

    private RouteCacheContract() {
    }
}
