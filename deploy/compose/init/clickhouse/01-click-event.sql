-- DESIGN 4.2：ClickHouse 事件明细 + 分钟聚合
-- 注意：docker-entrypoint-initdb.d 仅在数据卷首次创建时执行；卷已存在时用
--   clickhouse-client / HTTP 接口重放本脚本（`make ch-schema`，全幂等）。
--
-- 顺序有讲究（M3-01）：**先加列，再换 consumer，最后才换 jump**。
-- 反过来会出现"新 jump 已经往消息里塞 click_id、而列还不存在"的窗口——ClickHouse 的 JSONEachRow
-- 对未知字段默认直接报错，那段时间的事件会写不进去（consumer 重试三次就进死信日志）。
-- 本机是同一次 make up 一起换的，但这条顺序值得写下来：它是"契约变更"的通用排期。

CREATE DATABASE IF NOT EXISTS xsl;

CREATE TABLE IF NOT EXISTS xsl.click_event
(
    event_id     String,
    short_code   LowCardinality(String),
    click_id     String,
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

-- 老数据卷：ADD COLUMN 只改元数据，不重写已有 parts；读旧 part 时该列返回默认值空串
-- （空串的两种含义见 DESIGN 4.2：M3-01 之前的历史行、以及被频控拦下的点击）。
ALTER TABLE xsl.click_event ADD COLUMN IF NOT EXISTS click_id String AFTER short_code;

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
