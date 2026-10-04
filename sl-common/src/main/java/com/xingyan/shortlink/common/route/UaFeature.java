package com.xingyan.shortlink.common.route;

import java.util.List;

/**
 * UA 特征表的一条（DESIGN 6.2「特征表自维护 + 配置加载」）。
 *
 * @param key    词汇表值：同时是 {@code route_rule} 的 {@code condition_value} 与 {@code click_event.device_type/os}
 *               的取值，必须落在 {@link UaContainer}/{@link UaDevice} 的 key 里
 * @param tokens UA（已转小写）里搜的子串，<b>任一个命中即算这条特征</b>；顺序无关，条目之间才讲顺序
 */
public record UaFeature(String key, List<String> tokens) {
}
