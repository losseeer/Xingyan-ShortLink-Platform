#!/usr/bin/env bash
# M1-11 事件链路两段测（DESIGN 8.1-A「先关 consumer 测堆积，再开 consumer 测追赶」）：
#   第一段：停 consumer → 持续打跳转 → 度量生产端能压进 Kafka 多少 events/s（broker 堆积）；
#   第二段：放开 consumer → 追平 lag → 度量消费+写库的追赶吞吐，并对账"生产数 = 落库数"。
# 对账口径：本轮所有热码共用一个 campaign_id（setup 打的标），CH 里按 campaign 数出来即可，
# 不受历史数据干扰；重复由 event_id + ReplacingMergeTree 幂等消化。
source "$(dirname "$0")/lib.sh"

[[ -f "$TMP_DIR/bench-meta.env" ]] || { echo "先跑 bench/setup-hot-links.sh"; exit 1; }
source "$TMP_DIR/bench-meta.env"
BURST_SECONDS=${BURST_SECONDS:-45}
THREADS=${THREADS:-8}
CONNS=${CONNS:-200}
export BENCH_CODES_FILE="${BENCH_CODES_FILE:-$TMP_DIR/hot-codes.txt}"

log_end_offset() {
  # 用 kafka-get-offsets 直接取 LOG-END-OFFSET：--describe --all-topics 的列位在未消费分区上是 "-"，
  # 早先按 $5 解析取到 0，把"生产数"算成了 0（本机实测踩到）
  $COMPOSE exec -T kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 \
    --topic shortlink-click --time -1 2>/dev/null | awk -F: '{s+=$3} END {print s+0}'
}
ch_events() { chq "SELECT count() FROM xsl.click_event WHERE campaign_id='$CAMPAIGN'"; }

REPORT=$(report_new "m1-11-kafka")
echo "报告：$REPORT"

step() { printf '\n== %s ==\n' "$1"; }

step "0. 基线"
$COMPOSE exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
  --describe --topic shortlink-click | sed -n '1p' >> "$REPORT"
{ echo "- topic：shortlink-click（单分区，KRaft 单节点）"; echo "- 起始 CH 事件数（campaign=$CAMPAIGN）：$(ch_events)"; echo "- 起始 LOG-END-OFFSET：$(log_end_offset)"; } >> "$REPORT"
BASE_CH=$(ch_events); BASE_OFF=$(log_end_offset)

step "1. 第一段：停 consumer，测生产端堆积吞吐"
$COMPOSE stop consumer >> "$REPORT" 2>&1
OFF_A=$(log_end_offset)
wrk -t"$THREADS" -c"$CONNS" -d"${BURST_SECONDS}s" -s "$BENCH_DIR/wrk/jump-path.lua" \
    http://127.0.0.1:80 2>&1 | tee "$TMP_DIR/kafka-burst.txt" | tail -6
OFF_B=$(log_end_offset)
PRODUCED=$((OFF_B - OFF_A))
RATE=$(python3 -c "print(f'{$PRODUCED/$BURST_SECONDS:.0f}')")
{
  echo
  echo '```'
  tail -6 "$TMP_DIR/kafka-burst.txt"
  echo '```'
  echo
  echo "- 生产事件数（LOG-END-OFFSET 增量）：**$PRODUCED**，历时 ${BURST_SECONDS}s → **$RATE events/s**（目标 ≥1 万）"
  echo "- 此时 consumer 已停止，积压 = lag $(consumer_lag)（跳转仍全部 302：事件通道不阻塞数据面）"
  echo "- jump WAL 积压字节：$(curl -s localhost:8020/actuator/prometheus | awk '/xsl_jump_wal_backlog_bytes/{s+=$2} END {print s+0}')（应为 0：Kafka 可达，未走兜底）"
} >> "$REPORT"
echo "   produced=$PRODUCED rate=$RATE/s lag=$(consumer_lag)"

step "2. 第二段：放开 consumer，测追赶清零"
$COMPOSE start consumer >> "$REPORT" 2>&1
T0=$(date +%s); LAG0=$(consumer_lag); SLEEPED=0
while :; do
  LAG=$(consumer_lag)
  [[ "$LAG" -le 0 ]] && break
  [[ $(( $(date +%s) - T0 )) -gt 600 ]] && { echo "追赶超时（600s）"; break; }
  sleep 5
done
T=$(( $(date +%s) - T0 ))
{
  echo
  echo "- 追平前 lag=$LAG0，追平用时 ${T}s → 追赶吞吐 **$(python3 -c "print(f'{$LAG0/max($T,1):.0f}')") events/s**"
  echo "- 追平后 lag=$(consumer_lag)"
} >> "$REPORT"
echo "   lag0=$LAG0 追平用时=${T}s"

step "3. 对账：生产数 vs 落库数"
sleep 10
END_CH=$(ch_events); END_OFF=$(log_end_offset)
DELTA_CH=$((END_CH - BASE_CH))
{
  echo
  echo "| 口径 | 数值 |"
  echo "|---|---|"
  echo "| topic 累计写入（含本轮前） | $END_OFF |"
  echo "| 本轮生产（第一段增量） | $PRODUCED |"
  echo "| CH 本轮新增（campaign=$CAMPAIGN，非 FINAL，含未合并重复） | $DELTA_CH |"
  echo "| 差值 | $((PRODUCED - DELTA_CH)) |"
  echo
  python3 - "$PRODUCED" "$DELTA_CH" <<'PY'
import sys
p, c = int(sys.argv[1]), int(sys.argv[2])
r = 0.0 if p == 0 else abs(p - c) / p * 100
print(f"- **丢失率口径：{r:.4f}%**（目标 <0.01%）。差值为 0 说明堆积→追赶全程未丢事件；"
      f"若为正数则是尚未合并的 ReplacingMergeTree 重复或延迟，需用 FINAL 复核")
PY
} >> "$REPORT"
echo "   produced=$PRODUCED ch_delta=$DELTA_CH diff=$((PRODUCED - DELTA_CH))"
FINAL=$(chq "SELECT count() FROM xsl.click_event FINAL WHERE campaign_id='$CAMPAIGN'")
{ echo "- CH count() FINAL（ReplacingMergeTree 去重后）= $FINAL"; } >> "$REPORT"
echo "   FINAL=$FINAL"
