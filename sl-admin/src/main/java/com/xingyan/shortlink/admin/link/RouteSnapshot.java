package com.xingyan.shortlink.admin.link;

import java.time.LocalDateTime;

/**
 * link_route.route_json 快照（DESIGN 6.x 规则双轨：数据面唯一读取源）。
 * status/expire/access 冗余进快照，跳转侧无需回查元数据表。
 */
public record RouteSnapshot(
        String originUrl,
        long tenantId,
        int redirectType,
        LocalDateTime expireTime,
        Integer accessLimit,
        int status,
        long version) {
}
