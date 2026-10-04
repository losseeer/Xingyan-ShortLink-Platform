package com.xingyan.shortlink.common.route;

/**
 * 一条路由规则（DESIGN 6.2 两级路由模型的服务端一级决策单位）。
 *
 * <p>存储双轨：行式真源是 MySQL {@code route_rule}（管理面逐条校验/冲突检查），
 * 编译产物进 {@code link_route.route_json.rules}（数据面唯一读取源，已按 priority 升序）。
 * 数据面不回查 route_rule —— jump 的 ShardingSphere 配置里也没有它（气分离）。
 *
 * <p>{@code conditionValue} 对 {@code time_window} 存 JSON 数组串（如 {@code ["2026-09-20","2026-09-30"]}），
 * 配合 {@code condition_op=between}；这样做是因为 route_rule 的条件列是通用的 (type, op, value) 三元组，
 * 不为某个维度单独加列。
 *
 * @param conditionType ua_container / device / geo_province / time_window / default
 * @param conditionOp   eq / in / between / any（实现收窄掉了 gt：这几类条件没有可比大小语义）
 * @param targetType    h5 / app_scheme / wx_urllink / intermediate
 */
public record RouteRule(
        int priority,
        String conditionType,
        String conditionOp,
        String conditionValue,
        String targetType,
        String targetUrl,
        String schemeUrl,
        String fallbackUrl) {

    public static final String UA_CONTAINER = "ua_container";
    public static final String DEVICE = "device";
    public static final String GEO_PROVINCE = "geo_province";
    public static final String TIME_WINDOW = "time_window";
    public static final String DEFAULT = "default";

    public static final String T_H5 = "h5";
    public static final String T_APP_SCHEME = "app_scheme";
    public static final String T_WX_URLLINK = "wx_urllink";
    public static final String T_INTERMEDIATE = "intermediate";

    /**
     * 是否无条件规则（default 那一类）。刻意不叫 {@code isDefault()}：sl-common 不依赖 Jackson，
     * 而 {@code isXxx()} 会被 Jackson 当成派生属性写进 route_json（快照里凭空多一个 "default" 键）。
     */
    public boolean unconditional() {
        return DEFAULT.equals(conditionType);
    }
}
