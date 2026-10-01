#!/usr/bin/env bash
# M1-11 ClickHouse 基准（DESIGN 8.1-A「千万级明细秒级聚合」）：
#   1) 聚合查询跑在**真实积累**的明细上——M1-11 跳转压测本身就产出了千万级事件，
#      比造数凑量级更有说服力，也不用把容器内存顶穿；
#   2) 写入吞吐单独测一小批合成事件（独立 campaign 标记，测完删除，不污染对账）；
#   3) 上限如实记录：mem_limit=2g 下，在数千万行存量上继续大批量插入会撞 Code 241（本机实测）。
# 不用影子库：被测对象就是生产那套 DDL/分区键/物化视图，换库等于换了被测系统。
source "$(dirname "$0")/lib.sh"

INSERT_ROWS=${CH_INSERT_ROWS:-1000000}
CHUNK=${CH_CHUNK:-100000}
BENCH_CAMPAIGN="bench-agg-$(date +%s)"
REPORT=$(report_new "m1-11-clickhouse")
echo "报告：$REPORT"

step() { printf '\n== %s ==\n' "$1"; }

run_timed() { # run_timed <标题> <SQL>
  local label="$1" sql="$2" t0 t1 out
  CH_TIMEOUT=600
  t0=$(now_ms); out=$(chq "$sql"); t1=$(now_ms)
  if [[ "$out" == *"Exception"* ]]; then
    echo "!! $label：$(echo "$out" | head -1)"
    { echo; echo "**$label**：失败 —— $(echo "$out" | head -1)"; } >> "$REPORT"
    return 0
  fi
  printf '%-42s %8s ms  %s\n' "$label" "$((t1 - t0))" "$(echo "$out" | tr '\n' ' ' | head -c 50)"
  {
    echo
    echo "**$label** — $((t1 - t0)) ms"
    echo
    echo '```'
    echo "$out" | head -10
    echo '```'
  } >> "$REPORT"
}

step "0. 数据现状（真实积累）"
TOTAL=$(chq "SELECT count() FROM xsl.click_event")
{
  echo "- click_event 存量（未去重）：$TOTAL 行；其中跳转压测真实产出 $(chq "SELECT count() FROM xsl.click_event WHERE campaign_id LIKE 'cp-%'") 行"
  echo "- 明细磁盘占用：$(chq "SELECT formatReadableSize(sum(bytes_on_disk)) FROM system.parts WHERE database='xsl' AND active")"
  echo "- ClickHouse $(chq "SELECT version()")，容器 mem_limit 2g（compose 声明值）"
} >> "$REPORT"
echo "   存量 $TOTAL 行"

step "1. 聚合查询基准（全表存量）"
{ echo; echo "## 聚合查询（$TOTAL 行真实明细）"; } >> "$REPORT"
run_timed "按渠道聚合 PV" \
  "SELECT channel_id, count() AS pv FROM xsl.click_event GROUP BY channel_id ORDER BY pv DESC FORMAT TSV"
run_timed "按小时×设备分桶" \
  "SELECT toStartOfHour(click_time) AS h, device_type, count() AS c FROM xsl.click_event GROUP BY h, device_type ORDER BY h DESC LIMIT 5 FORMAT TSV"
run_timed "按推广人 TopN + uniqExact(ip_hash)" \
  "SELECT promoter_id, count() AS pv, uniqExact(ip_hash) AS uv FROM xsl.click_event GROUP BY promoter_id ORDER BY pv DESC LIMIT 5 FORMAT TSV"
run_timed "ReplacingMergeTree 去重读取 count() FINAL" \
  "SELECT count() FROM xsl.click_event FINAL FORMAT TSV"
run_timed "MV 预聚合读分钟表（sum + uniqMerge）" \
  "SELECT sum(pv) AS pv, uniqMerge(uv_state) AS uv FROM xsl.click_stat_minute GROUP BY () FORMAT TSV"
run_timed "看板单码口径（FINAL + short_code 过滤）" \
  "SELECT count() FROM xsl.click_event FINAL WHERE short_code='$(head -1 "$TMP_DIR/hot-codes.txt")' FORMAT TSV"

step "2. 写入吞吐（$INSERT_ROWS 行合成，$CHUNK 行一批）"
insert_chunk() { # insert_chunk <块序号> —— number 加块基址保证 event_id 唯一
  local base=$(($1 * CHUNK))
  chq "INSERT INTO xsl.click_event
       SELECT concat('bench-', toString(number))             AS event_id,
              concat('b', toString(number % 100000))         AS short_code,
              now() - toUInt32(number % 3600)                AS click_time,
              concat('ip', toString(cityHash64(number)))     AS ip_hash,
              'Mozilla/5.0 (Macintosh; Intel bench)'         AS user_agent,
              ''                                             AS referer,
              ['wechat','douyin','browser'][1 + number % 3]  AS device_type,
              'linux' AS os, '浙江' AS province, '杭州' AS city,
              'ch-bench' AS channel_id,
              '$BENCH_CAMPAIGN'                               AS campaign_id,
              concat('pr', toString(number % 500))           AS promoter_id,
              'utm_source=bench'                             AS utm_params,
              toUInt8(number % 100)                          AS risk_score,
              toUInt8(number % 97 = 0)                       AS is_bot,
              toUInt64(1001 + number % 2)                    AS tenant_id
       FROM (SELECT number + $base AS number FROM numbers(toUInt64($CHUNK)))"
}
T0=$(now_ms); ERR=""; DONE_CHUNKS=0
for i in $(seq 0 $((INSERT_ROWS / CHUNK - 1))); do
  e=$(CH_TIMEOUT=300 insert_chunk "$i")
  if [[ -n "$e" ]]; then ERR="$e"; break; fi
  DONE_CHUNKS=$((DONE_CHUNKS + 1))
done
MILLI=$(( $(now_ms) - T0 ))
{
  echo
  echo "- 完成 $DONE_CHUNKS 批 × $CHUNK 行 = $((DONE_CHUNKS * CHUNK)) 行，用时 ${MILLI} ms（含物化视图同步聚合）"
  [[ $DONE_CHUNKS -gt 0 ]] && echo "- 写入吞吐 ≈ $(python3 -c "print(f'{$((DONE_CHUNKS * CHUNK)) * 1000 / max($MILLI, 1):,.0f} rows/s')")"
  [[ -n "$ERR" ]] && echo "- **中断点**：$(echo "$ERR" | head -1)（原样存档，不为过指标而绕过容器上限）"
  echo "- 落库确认：$(chq "SELECT count() FROM xsl.click_event WHERE campaign_id='$BENCH_CAMPAIGN'") 行"
} >> "$REPORT"
echo "   $DONE_CHUNKS 批 / ${MILLI} ms"

step "3. 收尾：只删本轮合成数据"
if [[ $DONE_CHUNKS -gt 0 ]]; then
  chq "ALTER TABLE xsl.click_event DELETE WHERE campaign_id='$BENCH_CAMPAIGN'" >/dev/null
  chq "ALTER TABLE xsl.click_stat_minute DELETE WHERE campaign_id='$BENCH_CAMPAIGN'" >/dev/null
  LEFT=-1
  for _ in $(seq 40); do
    LEFT=$(chq "SELECT count() FROM xsl.click_event WHERE campaign_id='$BENCH_CAMPAIGN'")
    [[ "$LEFT" == "0" ]] && break
    sleep 3
  done
else
  LEFT=0
fi
{
  echo
  echo "- 合成批残留 $LEFT 行（mutation 异步，轮询上限 120s）"
  echo "- 真实明细未做任何删除：当前 $(chq "SELECT count() FROM xsl.click_event") 行、$(chq "SELECT formatReadableSize(sum(bytes_on_disk)) FROM system.parts WHERE database='xsl' AND active")"
} >> "$REPORT"
echo "   残留 $LEFT 行"
