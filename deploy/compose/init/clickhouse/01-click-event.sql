-- DESIGN 4.2：ClickHouse 事件明细 + 分钟聚合
-- 注意：docker-entrypoint-initdb.d 仅在数据卷首次创建时执行；卷已存在时用
--   clickhouse-client 手动执行本脚本（见 DEVELOPMENT_PLAN M1-03 验收动作）。

CREATE DATABASE IF NOT EXISTS xsl;

CREATE TABLE IF NOT EXISTS xsl.click_event
(
    event_id     String,
    short_code   LowCardinality(String),
    click_time   DateTime,
    ip_hash      String,
    user_agent   String,
    referer      String,
    device_type  LowCardinality(String),
    os           LowCardinality(String),
    province     LowCardinality(String),
    city         LowCardinality(String),
    channel_id   LowCardinality(String),
    campaign_id  LowCardinality(String),
    promoter_id  LowCardinality(String),
    utm_params   String,
    risk_score   UInt8,
    is_bot       UInt8,
    tenant_id    UInt64
)
ENGINE = ReplacingMergeTree
PARTITION BY toYYYYMM(click_time)
ORDER BY (short_code, click_time, event_id);

CREATE TABLE IF NOT EXISTS xsl.click_stat_minute
(
    minute       DateTime,
    tenant_id    UInt64,
    campaign_id  LowCardinality(String),
    channel_id   LowCardinality(String),
    pv           SimpleAggregateFunction(sum, UInt64),
    uv_state     AggregateFunction(uniq, String),
    bot_pv       SimpleAggregateFunction(sum, UInt64),
    wx_pv        SimpleAggregateFunction(sum, UInt64),
    douyin_pv    SimpleAggregateFunction(sum, UInt64),
    browser_pv   SimpleAggregateFunction(sum, UInt64)
)
ENGINE = SummingMergeTree
ORDER BY (tenant_id, campaign_id, channel_id, minute);

CREATE MATERIALIZED VIEW IF NOT EXISTS xsl.click_stat_minute_mv
TO xsl.click_stat_minute
AS SELECT
    toStartOfMinute(click_time)               AS minute,
    tenant_id,
    campaign_id,
    channel_id,
    count()                                   AS pv,
    uniqState(ip_hash)                        AS uv_state,
    countIf(is_bot = 1)                       AS bot_pv,
    countIf(device_type = 'wechat')           AS wx_pv,
    countIf(device_type = 'douyin')           AS douyin_pv,
    countIf(device_type NOT IN ('wechat', 'douyin')) AS browser_pv
FROM xsl.click_event
GROUP BY minute, tenant_id, campaign_id, channel_id;
