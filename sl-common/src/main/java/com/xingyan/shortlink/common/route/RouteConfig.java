package com.xingyan.shortlink.common.route;

import java.time.LocalDateTime;

/**
 * link_route.route_json 快照：管理面唯一写源（编译产物），数据面唯一读取源
 * （DESIGN 6.x 规则双轨）。status/expire/access 冗余进快照，跳转侧无需回查元数据表。
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
        String promoterId) {
}
