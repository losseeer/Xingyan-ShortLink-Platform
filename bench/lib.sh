#!/usr/bin/env bash
# bench 公用件：凭据装载、指标快照、宿主与 JVM 事实、报告命名。
# 各 run-*.sh 顶部 source 本文件；不要在终端里手敲口令——一律经 lib-devenv 从 .env 取。
set -uo pipefail

BENCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$BENCH_DIR/.." && pwd)"
source "$REPO_ROOT/scripts/lib-devenv.sh"

TMP_DIR="$BENCH_DIR/.tmp"
REPORT_DIR="$BENCH_DIR/reports"
mkdir -p "$TMP_DIR" "$REPORT_DIR"

CH_URL="${CH_URL:-http://127.0.0.1:8123/}"
now_ms() { python3 -c "import time;print(int(time.time()*1000))"; }

chq() { curl -s -m "${CH_TIMEOUT:-120}" -u "$CH_CRED" "$CH_URL" --data-binary "$1"; }

# 两个 jump 实例的计数求和（micrometer 计数器按实例打标签，压测口径要看总和）
jump_metric() { # jump_metric <指标名(含 label 片段)>
  local sum=0 v
  for p in 8020 8022; do
    v=$(curl -s -m 5 "http://127.0.0.1:$p/actuator/prometheus" | awk -v m="$1" '$0 ~ m {s+=$2} END {printf "%.0f", s+0}')
    sum=$((sum + v))
  done
  echo "$sum"
}

# 路由层级分布快照：命中率 = (local + redis) / 总查询
snap_routes() {
  # 标签顺序是 {instance=...,level=...}，所以匹配式必须用 .* 跨过去
  echo "local=$(jump_metric 'xsl_jump_route_lookup_total.*level="local"')" \
       "redis=$(jump_metric 'xsl_jump_route_lookup_total.*level="redis"')" \
       "db=$(jump_metric 'xsl_jump_route_lookup_total.*level="db"')" \
       "miss=$(jump_metric 'xsl_jump_route_lookup_total.*level="miss"')" \
       "requests=$(jump_metric 'xsl_jump_requests_total')"
}

# GC 停顿证据：Boot 自带 jvm_gc_pause 直方图，比看 CPU 百分比更能指认 SerialGC 的影响
# GC 停顿证据：Boot 自带 jvm_gc_pause_seconds（count/sum/max，按 action 分序列）。
# 注意标签值里带空格（action="end of minor GC"），所以取值只能用最后一列，
# 不能按 $2 解析——本机第一版就这么读成了 0，白跑一轮。
snap_gc() {
  local acc=""
  for p in 8020 8022; do
    curl -s -m 5 "http://127.0.0.1:$p/actuator/prometheus" >> "$TMP_DIR/prom-$p.txt" && cp "$TMP_DIR/prom-$p.txt" "$TMP_DIR/prom-last.txt"
  done
  : > "$TMP_DIR/prom-both.txt"
  for p in 8020 8022; do curl -s -m 5 "http://127.0.0.1:$p/actuator/prometheus" >> "$TMP_DIR/prom-both.txt"; done
  awk '
    /^jvm_gc_pause_seconds_count/ { if ($0 ~ /minor/) { c+=$NF } else if ($0 ~ /major/) { C+=$NF } }
    /^jvm_gc_pause_seconds_sum/   { if ($0 ~ /minor/) { s+=$NF } else if ($0 ~ /major/) { S+=$NF } }
    /^jvm_gc_pause_seconds_max/   { if ($0 ~ /minor/) { if ($NF>m) m=$NF } else if ($0 ~ /major/) { if ($NF>M) M=$NF } }
    END { printf "minor=%d次/%.3fs(单次峰值%.0fms) major=%d次/%.3fs(单次峰值%.0fms)", c, s, m*1000, C, S, M*1000 }
  ' "$TMP_DIR/prom-both.txt"
  rm -f "$TMP_DIR"/prom-80*.txt "$TMP_DIR/prom-last.txt" "$TMP_DIR/prom-both.txt"
}

consumer_lag() {
  $COMPOSE exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
    --describe --group sl-consumer 2>/dev/null \
    | awk 'NR>1 && $6 ~ /^[0-9]+$/ {s+=$6} END {print s+0}'   # 第 6 列才是 LAG（4/5 是 offset）
}

host_facts() {
  cat <<EOF
- 时间：$(date '+%Y-%m-%d %H:%M:%S %z')
- 宿主：$(sysctl -n machdep.cpu.brand_string 2>/dev/null || echo unknown) / $(sysctl -n hw.memsize | awk '{printf "%.0f GiB 物理内存", $1/1073741824}') / $(sysctl -n hw.ncpu) 逻辑核 / $(uname -m)
- Docker VM：$(docker info --format '{{.MemTotal}}' | awk '{printf "%.2f GiB", $1/1073741824}')、$(docker info --format '{{.NCPU}}') 核；驱动 $(docker info --format '{{.Driver}}')
- 提交：$(git -C "$REPO_ROOT" rev-parse --short HEAD)$(git -C "$REPO_ROOT" status --porcelain >/dev/null && [ -n "$(git -C "$REPO_ROOT" status --porcelain)" ] && echo '（含未提交改动）')
- jump JVM：$(docker exec xsl-jump-1-1 sh -c 'tr "\0" " " < /proc/1/cmdline' | sed 's/ -jar.*//')
- 容器内存实测：$(docker stats --no-stream --format '{{.Name}}={{.MemUsage}}' | grep -E 'jump|nginx' | tr '\n' ' ')
EOF
}

report_new() { # report_new <文件名后缀> —— 返回本轮报告路径；bench/reports/ 只增不改（见 DEVELOPMENT_PLAN）
  local f="$REPORT_DIR/$(date '+%Y-%m-%d-%H%M')-$1.md"
  { echo "# 压测存档：$1"; echo; host_facts; echo; } > "$f"
  echo "$f"
}
