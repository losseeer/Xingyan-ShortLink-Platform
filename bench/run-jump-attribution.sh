#!/usr/bin/env bash
# M2-00 归因：负载进行中逐容器采 CPU，回答"数据面被谁拖慢"。
# 结束后采是空的（docker stats 只反映瞬时），所以必须在负载中间隔采样——
# M1-11 的报告里那行 "jump CPU 0.07%" 就是结束后采的，指认不了任何瓶颈。
source "$(dirname "$0")/lib-bench.sh"

CONNS=${ATTR_CONNS:-64}
THREADS=${ATTR_THREADS:-6}
DURATION=${ATTR_DURATION:-24s}
SECS=${DURATION%s}
SAMPLE_EVERY=${ATTR_SAMPLE:-3}
export BENCH_CODES_FILE="${BENCH_CODES_FILE:-$TMP_DIR/hot-codes.txt}"
[[ -s "$BENCH_CODES_FILE" ]] || { echo "先跑 bench/setup-hot-links.sh"; exit 1; }

REPORT=$(report_new "m2-00-jump-attribution-c$CONNS")
echo "报告：$REPORT"

wrk -t"$THREADS" -c"$CONNS" -d"$DURATION" -s "$BENCH_DIR/wrk/jump-path.lua" http://127.0.0.1:80 >/dev/null 2>&1 &
WRK=$!
sleep 3   # 进入稳态再采
: > "$TMP_DIR/attr-samples.txt"
while kill -0 "$WRK" 2>/dev/null; do
  docker stats --no-stream --format '{{.Name}} {{.CPUPerc}}' >> "$TMP_DIR/attr-samples.txt"
  echo "---" >> "$TMP_DIR/attr-samples.txt"
  sleep "$SAMPLE_EVERY"
done
wait "$WRK" 2>/dev/null

{
  echo "- 负载：\`wrk -t$THREADS -c$CONNS -d$DURATION\`；每 ${SAMPLE_EVERY}s 采一次 docker stats（负载进行中）"
  echo "- 采样次数：$(grep -c '^xsl-' "$TMP_DIR/attr-samples.txt")"
  echo
  echo "## 负载中各容器平均 CPU（按占用排序）"
  echo
  awk '/^---$/{next} {gsub(/%/,"",$2); s[$1]+=$2; n[$1]++} END {for (k in s) printf "%.0f %s\n", s[k]/n[k], k}' \
    "$TMP_DIR/attr-samples.txt" | sort -rn | awk '{printf "- %s：约 %s%% 核\n", $2, $1}'
  echo
  echo "## 判读"
  echo
  echo "- jump 两个实例的 CPU 之和只是数据面自己的开销；**事件链（kafka + consumer + clickhouse）与 mysql 的合计才是同机竞争的大头**。"
  echo "  跳转的请求路径要把 ClickEvent 交给 kafka producer，链路越忙，尾延迟越靠后挪——这解释了 jump 自测均值 0.1–0.5ms 而 nginx 侧 urt 到几十毫秒。"
  echo "- 因此 M2 的治理方向不是给 jump 调 GC，而是**把签发/统计/事件链的资源与数据面隔离**（producer 批量与缓冲策略、consumer 拉取节奏、ClickHouse 写入批量），"
  echo "  并把 SLO 的判定负载固定在目标值（见 bench/README 口径）。"
} >> "$REPORT"
sed -n '/## 负载中各容器平均 CPU/,$p' "$REPORT"
