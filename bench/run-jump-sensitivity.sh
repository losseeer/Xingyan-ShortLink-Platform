#!/usr/bin/env bash
# 跳转并发敏感度曲线：同一份热码，按 (线程, 连接) 逐点压 20s，
# 每点同时抓「负载进行中的 jump CPU」与「jvm_gc_pause 增量」——
# 用来回答"SLO 没达标时瓶颈在服務端还是排队"这个追问。
source "$(dirname "$0")/lib-bench.sh"

DURATION=${SENS_DURATION:-20s}
POINTS=${SENS_POINTS:-"2:16 4:50 4:100 8:200 8:400"}
export BENCH_CODES_FILE="${BENCH_CODES_FILE:-$TMP_DIR/hot-codes.txt}"
[[ -s "$BENCH_CODES_FILE" ]] || { echo "先跑 bench/setup-hot-links.sh"; exit 1; }

REPORT=$(report_new "m1-11-jump-sensitivity")
echo "报告：$REPORT"
{
  echo "- 每点 $DURATION；路径 \`/s/{code}?utm_source=wrk\`，Host=xy1.test；热码 $(wc -l < "$BENCH_CODES_FILE" | tr -d ' ') 个"
  echo "- CPU 在负载进行中每 4s 采样取均值（结束后采到的是空载，指认不了瓶颈）"
  echo "- GC 列是该点的**增量**（进程累计值做差），单位：次数/停顿秒数"
  echo
  echo "| 线程 | 连接 | QPS | P50 | P99 | 最大 | GC 增量(minor/major) | jump CPU 均值 |"
  echo "|---|---|---|---|---|---|---|---|"
} >> "$REPORT"

for pt in $POINTS; do
  T=${pt%%:*}; C=${pt##*:}
  GC0=$(snap_gc)
  SAMPLER="$TMP_DIR/sens-stats.txt"; : > "$SAMPLER"
  ( while sleep 4; do
      docker stats --no-stream --format '{{.Name}} {{.CPUPerc}}' | grep jump \
        | awk '{gsub("%","",$2); s+=$2; n++} END {printf "%.0f%%\n", (n? s/n : 0)}' >> "$SAMPLER"
    done ) & SP=$!
  OUT=$(wrk -t"$T" -c"$C" -d"$DURATION" --latency -s "$BENCH_DIR/wrk/jump-path.lua" http://127.0.0.1:80 2>&1)
  kill "$SP" 2>/dev/null; wait "$SP" 2>/dev/null
  GC1=$(snap_gc)
  ROW=$(python3 - "$OUT" "$GC0" "$GC1" "$(tail -1 "$SAMPLER" 2>/dev/null)" <<'PY'
import re, sys
out, g0, g1, cpu = sys.argv[1], sys.argv[2], sys.argv[3], (sys.argv[4] or "-")
def grab(pat):
    m = re.search(pat, out)
    return m.group(1) if m else "-"
qps, p50, p99 = grab(r"Requests/sec:\s+([\d.]+)"), grab(r"\s+50%\s+([\d.]+\w+)"), grab(r"\s+99%\s+([\d.]+\w+)")
mx = grab(r"Latency\s+[\d.]+\w+\s+[\d.]+\w+\s+([\d.]+\w+)")
def minor_major(s):
    mm = re.search(r"minor=(\d+)次", s); mj = re.search(r"major=(\d+)次", s)
    ms = re.search(r"minor=\d+次/([\d.]+)s", s); js = re.search(r"major=\d+次/([\d.]+)s", s)
    return int(mm.group(1)), float(ms.group(1)), int(mj.group(1)), float(js.group(1))
a, b = minor_major(g0), minor_major(g1)
gc = f"+{b[0]-a[0]}次/{b[1]-a[1]:.2f}s 与 +{b[2]-a[2]}次/{b[3]-a[3]:.2f}s"
print(f"| {int(float(qps)):,} | {p50} | {p99} | {mx} | {gc} | {cpu} |")
PY
)
  echo "  $T/$C → $(echo "$ROW" | tr -d '|')"
  { echo "| $T | $C | $(echo "$ROW" | sed 's/^| //')"; } >> "$REPORT"   # ROW 自带前导 |，去掉才不会多一列
  sleep 5
done

{
  echo
  echo "## 结论口径"
  echo
  echo '- 判定 SLO 以 **DESIGN 1.3 的 2000 QPS + P99<50ms** 为准；本表回答的是"要多大并发才撞到拐点"。'
  echo '- 每点独占 20s，点间停 5s 让队列排空，避免把上一轮尾巴算进下一轮。' 
  echo "- 同机客户端与容器争抢 CPU，QPS 上界包含压测端自身开销，按保守口径读。"
} >> "$REPORT"
echo "已写入 $REPORT"
