package com.xingyan.shortlink.common.route;

import java.time.LocalDateTime;
import java.util.List;

/**
 * link_route.route_json 快照：管理面唯一写源（编译产物），数据面唯一读取源
 * （DESIGN 6.x 规则双轨）。status/expire/access 冗余进快照，跳转侧无需回查元数据表。
 *
 * @param accessLimit         总可访问次数上限，null = 不限（DESIGN 5.2 步骤 5 的配额扣减）
 * @param rateLimitPerMinute  该链接「同一客户端 IP 每分钟可点」的阈值，null/非正 = 用 jump 的全局默认
 *                            （DESIGN 5.3 第 2 层；票务场景里一条推给十万人的链接和一条私发链接
 *                            的正常并发差几个量级，只有全局阈值会把正常流量判成刷量）
 * @param rules               一级路由决策的规则表，**已按 priority 升序编译好**（DESIGN 6.2 双轨的编译产物）；
 *                            null/空 = 该链接没有规则，数据面按 origin_url 直接 302（与 M1 行为逐字节一致）
 */
public record RouteConfig(
        String originUrl,
        long tenantId,
        int redirectType,
        LocalDateTime expireTime,
        Integer accessLimit,
        int status,
        long version,
        String channelId,
        String campaignId,
        String promoterId,
        Integer rateLimitPerMinute,
        List<RouteRule> rules) {

    /** 老快照（M1 写的 route_json 没有 rules 字段）反序列化后 rules=null，这里统一成"无规则"。 */
    public List<RouteRule> rulesOrEmpty() {
        return rules == null ? List.of() : rules;
    }
}
