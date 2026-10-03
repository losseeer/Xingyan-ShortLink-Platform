#!/usr/bin/env bash
# M2-00 负载曲线：逐个并发点各跑一段，同时采 jump 自测耗时、线程状态、容器 CPU，
# 用来回答两个问题：① SLO 的 P99<50ms 在**目标负载**（≥2000 QPS）下究竟达不达标；
# ② 饱和区的尾巴是 CPU 调度排队、还是被下游（Kafka/Redis）阻塞。
# 口径提醒：M1-11 的"未达标"是在 200 连接（≈10× 目标负载）下测的；判定 SLO 必须说清在多大负载上判。
source "$(dirname "$0")/lib-bench.sh"

POINTS=${CURVE_POINTS:-"4 8 16 32 64 128 200"}
DURATION=${CURVE_DURATION:-15s}
THREADS_BASE=${CURVE_THREADS:-}   # 留空则按并发自动缩放线程（wrk 自身不能成为瓶颈）
SECS=${DURATION%s}
export BENCH_CODES_FILE="${BENCH_CODES_FILE:-$TMP_DIR/hot-codes.txt}"
[[ -s "$BENCH_CODES_FILE" ]] || { echo "先跑 bench/setup-hot-links.sh"; exit 1; }

REPORT=$(report_new "m2-00-jump-loadcurve")
echo "报告：$REPORT"

jump_mean() { # 本轮均值用 sum/count 增量算，绕开 Micrometer 分位数的 10 分钟衰减窗口
  curl -s -m 5 "http://127.0.0.1:8020/actuator/prometheus" "http://127.0.0.1:8022/actuator/prometheus" \
    | awk 'index($1,"http_server_requests_seconds_count{")==1 && $0 ~ /uri="\/s\/\{code\}"/ && $0 !~ /quantile/ {c+=$2}
           index($1,"http_server_requests_seconds_sum{")==1   && $0 ~ /uri="\/s\/\{code\}"/ {s+=$2}
           END {printf "%.1f %.6f", c, s}'
}
threads_now() { # <state> 两实例合并
  curl -s -m 5 "http://127.0.0.1:8020/actuator/prometheus" "http://127.0.0.1:8022/actuator/prometheus" \
    | awk -v s="$1" 'index($1,"jvm_threads_states_threads{")==1 && $0 ~ "state=\"" s "\"" {c+=$2} END {printf "%.0f", c}'
}
kafka_wait_ns() {
  curl -s -m 5 "http://127.0.0.1:8020/actuator/prometheus" "http://127.0.0.1:8022/actuator/prometheus" \
    | awk 'index($1,"kafka_producer_bufferpool_wait_time_ns_total{")==1 {c+=$2} END {printf "%.0f", c}'
}

{
  echo "- 每点：\`wrk -t<自动 4/6/8> -c<并发> -d$DURATION\`，入口 nginx :80 → jump×2，热码 $(wc -l < "$BENCH_CODES_FILE" | tr -d ' ') 个"
  echo "- 线程数随并发缩放：固定小线程数在大并发下会先压垮 wrk 自身（本机实测 c200/-t2 得 p99 186ms，而 c200/-t8 是 54ms——差的 130ms 是客户端的，不是服务端的）"
  echo "- 并发点之间停 4s 排空队列；负载中采样线程状态与 Kafka 生产者缓冲区等待"
  echo
  echo "| 并发 | 线程 | QPS | p50 | p99 | max | jump 自测均值 | 非302 | runnable/blocked/waiting | Kafka buffer 等待增量 |"
  echo "|---|---|---|---|---|---|---|---|---|---|"
} >> "$REPORT"

# 先跑一段丢弃：容器重建后的首个数据点会被 JIT 预热、Caffeine 冷启与上一轮写入引发的
# ClickHouse 合并污染（本机实测首点 c=4 只有 1,016 QPS / p99 379ms，比 c=200 还差 4 倍，
# 这种点写进报告只会误导）。等合并排空再正式采样。
step() { printf '%s\n' "$1" >&2; }
step "预热 12s（结果丢弃）…"
BENCH_CODES_FILE="$BENCH_CODES_FILE" wrk -t4 -c32 -d12s -s "$BENCH_DIR/wrk/jump-path.lua" http://127.0.0.1:80 >/dev/null 2>&1
step "等 ClickHouse 后台合并排空（最多 90s）…"
for _ in $(seq 30); do
  M=$(docker exec xsl-clickhouse-1 clickhouse-client --query 'SELECT count() FROM system.merges' 2>/dev/null || echo 0)
  [[ "${M:-0}" == "0" ]] && break
  sleep 3
done
step "起点环境：$(docker exec xsl-clickhouse-1 clickhouse-client --query 'SELECT count() FROM system.merges' 2>/dev/null) 个合并中，lag=$(consumer_lag)"

for C in $POINTS; do
  read -r C0 S0 <<< "$(jump_mean)"
  KW0=$(kafka_wait_ns)
  ( sleep $((SECS - 4))
    printf '%s %s %s' "$(threads_now runnable)" "$(threads_now blocked)" "$(threads_now waiting)" > "$TMP_DIR/curve-threads.txt"
  ) & SP=$!
  T=${THREADS_BASE:-$(( C < 64 ? 4 : ( C < 128 ? 6 : 8 ) ))}
  wrk -t"$T" -c"$C" -d"$DURATION" --latency -s "$BENCH_DIR/wrk/jump-path.lua" http://127.0.0.1:80 \
    > "$TMP_DIR/curve-wrk.txt" 2>&1
  wait "$SP" 2>/dev/null
  read -r C1 S1 <<< "$(jump_mean)"
  KW1=$(kafka_wait_ns)
  PARSED=$(python3 "$BENCH_DIR/parse-wrk.py" "$TMP_DIR/curve-wrk.txt")
  MEAN=$(python3 - "$C0" "$S0" "$C1" "$S1" <<'PY'
import sys
c0, s0, c1, s1 = (float(sys.argv[i]) for i in range(1, 5))
n = max(c1 - c0, 1.0)
print(f"{(s1 - s0) / n * 1000:.3f}ms/{int(c1 - c0)}次")
PY
)
  NON302=$(grep -oE "[0-9]+ non-2xx or 3xx" "$TMP_DIR/curve-wrk.txt" | awk '{print $1}' | head -1); NON302=${NON302:-0}
  # 取值靠字段名而不是"两个锚点之间的贪婪 .*"：上一版用 sed 抠 p50，遇到 us 单位就整体失配，
  # 把整行摘要写进了报告表格里（2026-10-03-1414 存档首点即如此，值本身仍可读）。
  field() { awk -v k="$1" '{for(i=1;i<=NF;i++){if(split($i,a,"=")==2 && a[1]==k){print a[2]; exit}}}'; }
  QPS=$(field QPS <<< "$PARSED"); P50=$(field p50 <<< "$PARSED")
  P99=$(field p99 <<< "$PARSED"); PMAX=$(field max <<< "$PARSED")
  THR=$(cat "$TMP_DIR/curve-threads.txt" 2>/dev/null || echo "- - -")
  KWD=$((KW1 - KW0))
  echo "  c=$C  QPS=$QPS  p99=$P99  jump均值=$MEAN"
  { echo "| $C | $T | $QPS | $P50 | $P99 | $PMAX | $MEAN | $NON302 | ${THR// //} | $((KWD / 1000000))ms |"; } >> "$REPORT"
  sleep 4
done

{
  echo
  echo "## 结论要点（读表）"
  echo
  echo "- **SLO 判定看目标负载行**：DESIGN 1.3 的 \`P50<10ms、P99<50ms\` 与 \`≥2000 QPS\` 是同一条 SLO，"
  echo "  所以达标与否要在**能产出 ≥2000 QPS 的最小并发点**上判，而不是在把系统压饱和的并发点上判。"
  echo "- 饱和区的 P99 属于容量上限描述（不是 SLO 违例）；两件事在文档里分开写。"
  echo "- \`jump 自测均值\` 远小于 wrk 的 p50 ⇒ 时间不在业务代码里；"
  echo "  再看 runnable/blocked：runnable 远大于核数 ⇒ CPU 调度排队；blocked 上升且 Kafka 等待增量>0 ⇒ 被下游卡住。"
} >> "$REPORT"
echo "已写入 $REPORT"
