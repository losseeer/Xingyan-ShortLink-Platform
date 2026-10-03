#!/usr/bin/env bash
# M2-13 验收（防刷主线的端到端证据，覆盖 M2-11 全部行为）：
#   A. 链接级阈值：3 次内 302，第 4 次起 429 + Retry-After
#   B. 维度隔离：同 IP 换 code 不受影响；不同来源 IP 各自独立计数
#   C. 伪造不可绕：经 nginx 时自带 X-Forwarded-For / X-Real-IP 不改变身份（安全断言）
#   D. 顺序证据：被频控拦下的请求不消耗 access_limit（余数 = 上限 − 放行数）
#   E. 事件仍记录：被拦的击进 ClickHouse 且 risk_score=25（否则看板只看到流量凭空消失）
#   F. 窗口滑过后恢复放行
#   G. Redis 停机：跳转不跌零，频控退化为进程内计数并带 mode=local 指标（DESIGN 8.3）
#   H. 配额对账（M2-12）：Redis 权威值镜像进 MySQL；丢键后按快照收口而不是回到满额
# 前置：make images && make up（jump/admin 为含频控的镜像）。
# 口径：断言一律走 nginx :80 真实入口；只有 B/G 用 8020 直连来模拟"另一个来源 IP"
#       （直连时没有 nginx，X-Real-IP 由脚本自己给，正是用来验证 jump 侧的取值优先级）。
set -uo pipefail
source "$(dirname "$0")/lib-devenv.sh"   # 口令从 deploy/compose/.env 注入，脚本里不写明文

IP="${NGINX_ENTRY_IP:-127.0.0.1}"
PORT="${NGINX_PORT:-80}"
ADMIN="${ADMIN_DIRECT:-http://localhost:8030}"
JUMP="${JUMP_DIRECT:-http://localhost:8020}"
TENANT=1001
RCLI="$COMPOSE exec -T redis redis-cli"
RUN=$(date +%s)
CODES=()

pass=0; fail=0
check() { if [[ "$2" == "$3" ]]; then echo "PASS $1 ($3)"; ((pass++)); else echo "FAIL $1 expect=$2 actual=$3"; ((fail++)); fi; }
note() { echo "     · $*"; }

# --- 发请求：状态码与 Retry-After 一次拿全（为看头而多发一次会让计数断言失准）---
req() { # req <code> [额外 curl 参数...] → "<status> <retryAfter|->"
  local code=$1; shift
  curl -s -o /dev/null -m 10 --resolve "xy1.test:$PORT:$IP" "$@" -D /tmp/m213-h.txt \
    -w '%{http_code}' "http://xy1.test:$PORT/s/$code" | tr -d '\r' | {
      read -r st
      ra=$(awk 'tolower($1)=="retry-after:"{gsub(/\r/,"",$2); print $2}' /tmp/m213-h.txt)
      echo "${st} ${ra:--}"
    }
}
req_direct() { # req_direct <code> <x-real-ip> → "<status> <retryAfter|->"（直连 8020 模拟来源 IP）
  local code=$1 ip=$2
  curl -s -o /dev/null -m 10 -H "X-Real-IP: $ip" -D /tmp/m213-h.txt \
    -w '%{http_code}' "$JUMP/s/$code" | tr -d '\r' | {
      read -r st
      ra=$(awk 'tolower($1)=="retry-after:"{gsub(/\r/,"",$2); print $2}' /tmp/m213-h.txt)
      echo "${st} ${ra:--}"
    }
}
status_of() { echo "${1%% *}"; }

create_link() { # create_link <rate_limit_per_minute> <access_limit|null> → short_code
  local body resp
  body=$(printf '{"origin_url":"https://mock.ticketsales.test/m213?seat=A#p","channel_id":"c-m213","campaign_id":"p-m213-%s","promoter_id":"pr-1","redirect_type":1,"rate_limit_per_minute":%s,"access_limit":%s}' \
    "$RUN" "$1" "$2")
  resp=$(curl -s -X POST "$ADMIN/api/v1/links" -H 'Content-Type: application/json' \
    -H "X-Tenant-Id: $TENANT" -H "Idempotency-Key: m213-$RUN-$1-$2" -d "$body")
  if ! resp_code=$(python3 -c "import json,sys;print(json.loads(sys.argv[1])['data']['short_code'])" "$resp" 2>/dev/null); then
    echo "FATAL 创建失败: $resp" >&2; exit 1
  fi
  echo "$resp_code"
}

del_keys() { # del_keys <code...>：路由缓存 + 配额 + 全部频控窗口键
  for c in "$@"; do
    for k in $($RCLI KEYS "sl:rl:$c:*" 2>/dev/null | tr -d '\r'); do $RCLI DEL "$k" > /dev/null 2>&1; done
    $RCLI DEL "sl:r:$c" "sl:r:nx:$c" "sl:cnt:$c" "sl:code:$c" > /dev/null 2>&1
  done
}

cleanup() {
  [[ ${#CODES[@]} -eq 0 ]] && return 0
  local in; in=$(printf "'%s'," "${CODES[@]}"); in="(${in%,})"
  for db in xsl_00 xsl_01; do
    $MYSQL_EXEC -N -uroot "$db" -e \
      "DELETE FROM link_route WHERE short_code IN $in;
       DELETE FROM code_tenant_index WHERE short_code IN $in;
       DELETE FROM short_link WHERE short_code IN $in;
       DELETE FROM short_code_pool WHERE short_code IN $in;
       DELETE FROM outbox WHERE aggregate_id IN $in;" >/dev/null 2>&1
  done
  del_keys "${CODES[@]}"
  for c in "${CODES[@]}"; do
    curl -s -m 20 -u "$CH_CRED" 'http://127.0.0.1:8123/' \
      --data-binary "ALTER TABLE xsl.click_event DELETE WHERE short_code='$c'" >/dev/null 2>&1
  done
  note "cleanup: ${#CODES[@]} 个 fixture、Redis 键与 CH 事件已删除"
}
trap cleanup EXIT

curl -s -m 5 -o /dev/null "$JUMP/actuator/health" || { echo "sl-jump 不可达，先 make up"; exit 1; }
$RCLI ping > /dev/null 2>&1 || { echo "redis 不可达，先 make up"; exit 1; }

CODE_A=$(create_link 3 50);  CODES+=("$CODE_A")
CODE_B=$(create_link 3 null); CODES+=("$CODE_B")
CODE_G=$(create_link 2 null); CODES+=("$CODE_G")
note "fixtures：A=$CODE_A(rate=3,quota=50) B=$CODE_B(rate=3) G=$CODE_G(rate=2)"
del_keys "$CODE_A" "$CODE_B" "$CODE_G"    # 建链时写过的缓存/键清掉，保证从冷码开始

allowed=0; blocked=0
tally() { # tally <status>
  if [[ "$1" == "302" ]]; then allowed=$((allowed+1)); else blocked=$((blocked+1)); fi
}

# ================= A. 链接级阈值 =================
A_OK=0; A_BLOCK=0; RA=-
for i in 1 2 3 4 5 6; do
  r=$(req "$CODE_A"); s=$(status_of "$r"); tally "$s"
  [[ "$s" == "302" ]] && A_OK=$((A_OK+1))
  [[ "$s" == "429" ]] && { A_BLOCK=$((A_BLOCK+1)); RA="${r##* }"; }
done
check "A1 阈值 3 → 恰好放行 3 次" 3 "$A_OK"
check "A2 越界 3 次全 429" 3 "$A_BLOCK"
check "A3 Retry-After 落在 1..60 秒" "yes" \
  "$(python3 -c "import sys;v=sys.argv[1];print('yes' if v.isdigit() and 0 < int(v) <= 60 else 'no')" "$RA")"
note "第 6 击的 Retry-After=$RA"

# ================= B. 维度隔离 =================
r=$(req "$CODE_B"); tally "$(status_of "$r")"
check "B1 同 IP 换 code 不受影响（键含 short_code）" 302 "$(status_of "$r")"
B_IP1=(); for i in 1 2 3 4; do r=$(req_direct "$CODE_B" "203.0.113.11"); s=$(status_of "$r"); tally "$s"; B_IP1+=("$s"); done
r=$(req_direct "$CODE_B" "203.0.113.12"); s=$(status_of "$r"); tally "$s"
check "B2 某来源 IP 用满窗口后 429" "302 302 302 429" "${B_IP1[*]}"
check "B3 另一来源 IP 不受牵连（键含 ip_hash）" 302 "$s"

# ================= C. 伪造不可绕（安全断言）=================
# nginx 用 $remote_addr 覆盖 X-Real-IP，jump 也不信 XFF 首元素 → 伪造身份拿不到新窗口。
C_ST=()
for i in 1 2 3 4; do
  r=$(req "$CODE_A" -H 'X-Forwarded-For: 8.8.8.8' -H 'X-Real-IP: 9.9.9.9')
  s=$(status_of "$r"); tally "$s"; C_ST+=("$s")
done
check "C1 自带 XFF/X-Real-IP 绕不过频控（4 次全 429）" "429 429 429 429" "${C_ST[*]}"

# ================= D. 频控不烧配额 =================
REMAIN=$($RCLI GET "sl:cnt:$CODE_A" | tr -d '\r')
check "D1 被拦的请求不消耗 access_limit" "$((50 - A_OK))" "$REMAIN"
note "sl:cnt:$CODE_A 余数=$REMAIN（A 段放行 $A_OK、拦 $A_BLOCK，C 段又拦 4 次）"

# ================= E. 被拦的击仍进事件流 =================
EXPECTED=$blocked
got=0
for _ in $(seq 15); do
  sleep 2
  got=$(curl -s -m 20 -u "$CH_CRED" 'http://127.0.0.1:8123/' \
    --data-binary "SELECT count() FROM xsl.click_event FINAL WHERE short_code IN ('$CODE_A','$CODE_B') AND risk_score=25")
  [[ "$got" -ge "$EXPECTED" ]] && break
done
check "E1 被频控拦下的点击以 risk_score=25 落库" "yes" \
  "$(python3 -c "import sys;print('yes' if int(sys.argv[1]) >= int(sys.argv[2]) else 'no')" "$got" "$EXPECTED")"
ok_in_ch=$(curl -s -m 20 -u "$CH_CRED" 'http://127.0.0.1:8123/' \
  --data-binary "SELECT count() FROM xsl.click_event FINAL WHERE short_code IN ('$CODE_A','$CODE_B') AND risk_score=0")
note "click_event：risk_score=25 共 $got 行（脚本内拦下 $EXPECTED）、risk_score=0 共 $ok_in_ch 行（放行 $allowed）"

# ================= F. 窗口滑过后恢复 =================
WAIT=$(python3 -c "import time;print(int(60 - time.time() % 60) + 2)")
note "等本窗口结束（${WAIT}s）…"
sleep "$WAIT"
r=$(req "$CODE_A"); tally "$(status_of "$r")"
check "F1 下一窗口恢复放行" 302 "$(status_of "$r")"

# ================= G. Redis 停机：不跌零 + 频控降级 =================
note "docker stop xsl-redis-1（DESIGN 8.3：频控退化为进程内计数，跳转不跌零）"
docker stop xsl-redis-1 > /dev/null 2>&1
G=()
for i in 1 2 3 4; do G+=("$(status_of "$(req_direct "$CODE_G" "203.0.113.77")")"); done
LOCAL=$(curl -s -m 5 "$JUMP/actuator/prometheus" | awk '/xsl_jump_rate_limit_total\{.*mode="local"/{s+=$2} END{printf "%.0f", s+0}')
docker start xsl-redis-1 > /dev/null 2>&1
for _ in $(seq 25); do $RCLI ping > /dev/null 2>&1 && break; sleep 1; done
check "G1 Redis 停机期间冷码仍 302（读侧兜底 + DB 回源）" "302 302" "${G[0]} ${G[1]}"
check "G2 进程内计数仍生效：阈值 2，第 3 次起 429" "429 429" "${G[2]} ${G[3]}"
check "G3 降级样本可用 mode=local 单独查出" "yes" \
  "$(python3 -c "import sys;print('yes' if int(sys.argv[1]) >= 3 else 'no')" "${LOCAL:-0}")"
note "xsl_jump_rate_limit_total{mode=\"local\"} 增量=$LOCAL"

# ================= H. 配额对账与 Redis 丢键后的冷重建（M2-12）=================
CODE_H=$(create_link 100000 5); CODES+=("$CODE_H")
del_keys "$CODE_H"
note "fixture H=$CODE_H（quota=5、频控放宽）：先正常消耗 3 次"
H_PRE=()
for i in 1 2 3; do H_PRE+=("$(status_of "$(req "$CODE_H")")"); done
check "H1 消耗前 3 击正常 302" "302 302 302" "${H_PRE[*]}"
check "H2 Redis 权威余数=2" "2" "$($RCLI GET "sl:cnt:$CODE_H" | tr -d '\r')"

snapshot_of() { # snapshot_of <code> → link_route.access_used（两分片里取有值的那个）
  for db in xsl_00 xsl_01; do
    local v
    v=$($MYSQL_EXEC -N -uroot -e "SELECT access_used FROM $db.link_route WHERE short_code='$1'" 2>/dev/null | tail -1)
    [[ -n "$v" ]] && { echo "$v"; return; }
  done
  echo "-"
}
USED=-
for _ in $(seq 30); do
  sleep 3
  USED=$(snapshot_of "$CODE_H")
  [[ "$USED" == "3" ]] && break
done
check "H3 管理面对账把权威值镜像进 MySQL（access_used=3）" "3" "$USED"
note "等待对账周期（默认 60s）实测耗时；指标 xsl_quota_reconcile_total 可查"

del_keys "$CODE_H"    # 只删配额键，模拟 Redis 丢数据（路由缓存随后回源）
REBUILD=()
for i in 1 2 3; do s=$(status_of "$(req "$CODE_H")"); tally "$s"; REBUILD+=("$s"); done
# 播种值 = 5 − 快照已用 3 − min(缓冲, 剩余额度 1/4=0) = 2 → 再放 2 次后 410
check "H4 丢键后按快照收口（只再放 2 次，而不是回到满额 5 次）" "302 302 410" "${REBUILD[*]}"

echo "== $pass passed, $fail failed =="
exit $(( fail > 0 ? 1 : 0 ))
