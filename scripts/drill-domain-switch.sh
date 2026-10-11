#!/usr/bin/env bash
# M3-10 演练：多域名等价 + 一键切主域（DESIGN 9.5，口径见 DEVELOPMENT_PLAN「M3-09/M3-10 的落地口径」第 8–10 条）
#
#   A. 同一条码在 xy1/xy2/xy3 三个 server_name 下 302 的 Location 完全一致（code 与域无绑定）
#   B. 选域规则的确定性：seed 里三个域 weight 都是 100 ⇒ 生效域必须是字典序最小的 xy1.test，且两次结果相同
#   C. 把 xy1.test 置 status=2（模拟封禁告警落库）→ 新建的码换域，并**量出换域生效用了多少秒**（选域有 30s 缓存）
#   D. 旧域不下线：C 之后，A 那条存量码在 xy1.test 仍要 302（否则一次切换就把发出去的宣传物料全作废）
#   E. switch_time 落库可查 + 指标 xsl_domain_pool_total 读得到（失败要能看出来是"退回兜底"还是"真在读池子"）
#   F. 复位成 seed 形状，再建一条码应回到 xy1.test（切回来也要成立）
#
# 这个脚本会改 xsl_base.domain_pool 与新建链接，两者都在 trap 里复位（改了部署/数据状态就必须自带兜底复位——
# ITER-M3 §4 记过两次"中断把环境留在脏状态"）。域名切换不动 nginx：三个 server_name 都指向同一组 upstream，
# "封禁"绝不等于从 nginx 里删掉一个域。
set -uo pipefail
source "$(dirname "$0")/lib-devenv.sh"

IP="${NGINX_ENTRY_IP:-127.0.0.1}"
PORT="${NGINX_PORT:-80}"
ADMIN="${ADMIN_DIRECT:-http://localhost:8030}"
TENANT=1001
RUN=$(date +%s)
TTL_SECONDS=30                 # 与 xsl.domain-pool.refresh-seconds 的默认值一致，切换生效延迟的上界就是它
CODE_FILE="/tmp/m310-codes.$RUN.txt"; : > "$CODE_FILE"

pass=0; fail=0
check() { if [[ "$2" == "$3" ]]; then echo "PASS $1 ($3)"; ((pass++)); else echo "FAIL $1 expect=$2 actual=$3"; ((fail++)); fi; }
truthy() { if [[ "$2" == "1" ]]; then echo "PASS $1 ($3)"; ((pass++)); else echo "FAIL $1 ($3)"; ((fail++)); fi; }
note() { echo "     · $*"; }
MY() { $MYSQL_CLI -N -B -e "$1" 2>/dev/null; }

domain_of() { printf '%s' "$1" | sed -n 's#https://\([^/]*\)/.*#\1#p'; }
create_link() {
  local body resp code
  body=$(printf '{"origin_url":"https://mock.ticketsales.test/show/m310-%s","channel_id":"c-m310","campaign_id":"p-m310-%s","promoter_id":"pr-m310","redirect_type":1,"rate_limit_per_minute":600}' "$2" "$RUN")
  resp=$(curl -s -X POST "$ADMIN/api/v1/links" -H 'Content-Type: application/json' \
    -H "X-Tenant-Id: $TENANT" -H "Idempotency-Key: m310-$RUN-$2-$RANDOM" -d "$body")
  code=$(printf '%s' "$resp" | sed -n 's/.*"short_code":"\([A-Za-z0-9]*\)".*/\1/p')
  if [[ -z "$code" ]]; then echo "FATAL 创建失败: $resp" >&2; exit 1; fi
  printf '%s\n' "$code" >> "$CODE_FILE"
  printf '%s' "$resp" | sed -n 's/.*"short_url":"\([^"]*\)".*/\1/p'
}
hit() { # hit <host> <code> → "状态 Location"
  curl -s -o /dev/null -m 10 -w '%{http_code} %{redirect_url}' --resolve "$1:$PORT:$IP" "http://$1:$PORT/s/$2"
}

cleanup() {
  set +u
  # 数据字典回到 seed 形状：不复位就是给下一个跑的人留"域名不知道为什么变了"
  MY "UPDATE xsl_base.domain_pool SET status = 0, weight = 100, switch_time = NULL;" >/dev/null
  if [[ -s "$CODE_FILE" ]]; then
    local codes in
    codes=$(cat "$CODE_FILE"); in=$(printf "'%s'," $codes); in="(${in%,})"
    MY "DELETE FROM xsl_00.short_link WHERE short_code IN $in; DELETE FROM xsl_01.short_link WHERE short_code IN $in;
        DELETE FROM xsl_00.link_route WHERE short_code IN $in; DELETE FROM xsl_01.link_route WHERE short_code IN $in;
        DELETE FROM xsl_00.code_tenant_index WHERE short_code IN $in; DELETE FROM xsl_01.code_tenant_index WHERE short_code IN $in;
        DELETE FROM xsl_00.outbox WHERE aggregate_id IN $in; DELETE FROM xsl_01.outbox WHERE aggregate_id IN $in;
        DELETE FROM xsl_00.route_rule WHERE short_code IN $in; DELETE FROM xsl_01.route_rule WHERE short_code IN $in;
        DELETE FROM xsl_00.short_code_pool WHERE short_code IN $in; DELETE FROM xsl_01.short_code_pool WHERE short_code IN $in;" >/dev/null
    for c in $codes; do $COMPOSE exec -T redis redis-cli DEL "sl:r:$c" "sl:code:$c" >/dev/null; done
  fi
  rm -f "$CODE_FILE"
}
trap cleanup EXIT

echo "== M3-10 多域名切换演练（run=$RUN，选域缓存 ${TTL_SECONDS}s）=="
H=$(curl -s -m 5 "$ADMIN/actuator/health")
truthy "P0-admin-up" "$( [[ "$H" == *'"status":"UP"'* ]] && echo 1 || echo 0 )" "admin 健康"
SHAPE=$(MY "SELECT count(*) FROM xsl_base.domain_pool WHERE status = 0;" | head -1)
check "P0-pool-seeded" 3 "$SHAPE"

# ---------------- A. 三域等价 ----------------
echo "-- A 同一条码在三个域上跳转必须完全一致"
URL_A=$(create_link "$RUN" same)
CODE_A=$(printf '%s' "$URL_A" | sed 's#.*/##')
LOC1=$(hit xy1.test "$CODE_A"); LOC2=$(hit xy2.test "$CODE_A"); LOC3=$(hit xy3.test "$CODE_A")
printf '%s\n' "$LOC1" "$LOC2" "$LOC3" > /tmp/m310-loc.$RUN.txt
# 比的是"去掉本次点击 id 之后完全一致"，不是整串相等：xy_click_id 本来就是**一次点击一个**（M3-01），
# 三个请求必然是三个不同的 id。拿整串比会把一条正确的性质写成一条永远红不了的断言。
STRIPPED=$(awk '{sub(/^302 /,""); sub(/xy_click_id=[^&]*/,"xy_click_id=X"); print}' /tmp/m310-loc.$RUN.txt | sort -u | grep -c "" || true)
check "A-three-domains-same-target" 1 "$STRIPPED"
check "A-all-302" 3 "$(grep -c "^302 " /tmp/m310-loc.$RUN.txt)"
IDS=$(grep -o 'xy_click_id=[^&]*' /tmp/m310-loc.$RUN.txt | sort -u | grep -c "" || true)
check "A-one-click-id-per-click" 3 "$IDS"
note "xy1/xy2/xy3 → 同一目标（除每次点击自己的 xy_click_id 外逐字符相同）"

# ---------------- B. 选域的确定性 ----------------
echo "-- B weight 全等时按字典序，且两次结果必须相同"
DOM_B=$(domain_of "$(create_link "$RUN" pick1)")
check "B-effective-domain-is-lexicographically-first" "xy1.test" "$DOM_B"
DOM_B2=$(domain_of "$(create_link "$RUN" pick2)")
check "B-choice-is-stable" "$DOM_B" "$DOM_B2"

# ---------------- C. 封禁 → 换域，并量出生效延迟 ----------------
echo "-- C 置 xy1.test status=2（模拟封禁告警落库），新码要换域"
START=$(date +%s)
MY "UPDATE xsl_base.domain_pool SET status = 2, switch_time = NOW() WHERE domain = 'xy1.test';" >/dev/null
SWITCHED=""
waited=0
while [[ $waited -lt $((TTL_SECONDS + 20)) ]]; do
  D=$(domain_of "$(create_link "$RUN" flip-$waited)")
  if [[ "$D" != "xy1.test" ]]; then SWITCHED="$D"; break; fi
  sleep 3; waited=$((waited+3))
done
ELAPSED=$(( $(date +%s) - START ))
check "C-switched-to-next-domain" "xy2.test" "$SWITCHED"
truthy "C-latency-within-ttl" "$( [[ $ELAPSED -le $((TTL_SECONDS + 20)) ]] && echo 1 || echo 0 )" "换域生效用了 ${ELAPSED}s（界就是选域缓存 ${TTL_SECONDS}s，不是 Bug）"
note "生效延迟上界 = xsl.domain-pool.refresh-seconds；要立刻定住域就 XSL_DOMAIN_POOL=false 走环境变量兜底"

# ---------------- D. 旧域不下线 ----------------
echo "-- D 被封的 xy1.test 上，存量码必须仍然能跳（否则切一次域就作废了所有已发物料）"
OLD=$(hit xy1.test "$CODE_A")
check "D-banned-domain-still-serves-existing-code" "302" "$(printf '%s' "$OLD" | cut -d' ' -f1)"
ST=$(MY "SELECT count(*) FROM xsl_base.domain_pool WHERE domain = 'xy1.test' AND status = 2 AND switch_time IS NOT NULL;" | head -1)
check "D-switch-time-persisted" 1 "$ST"
MET=$(curl -s -m 10 "$ADMIN/actuator/prometheus" | awk '$1 ~ /^xsl_domain_pool_total/ && index($1,"result=\"pool\"")>0 {print $2; exit}')
truthy "D-metric-pool-readably-on" "$( [[ -n "${MET:-}" ]] && echo 1 || echo 0 )" "xsl_domain_pool_total{result=pool} = ${MET:-读不到}（读不到就是选域根本没启用，不能算演练通过）"

# ---------------- F. 复位并切回 ----------------
echo "-- F 复位成 seed 形状，新建的码要回到 xy1.test"
MY "UPDATE xsl_base.domain_pool SET status = 0, weight = 100, switch_time = NULL;" >/dev/null
BACK=""
waited=0
while [[ $waited -le $TTL_SECONDS ]]; do
  D=$(domain_of "$(create_link "$RUN" back-$waited)")
  [[ "$D" == "xy1.test" ]] && { BACK="$D"; break; }
  sleep 3; waited=$((waited+3))
done
check "F-back-to-first-domain" "xy1.test" "$BACK"

echo
echo "== 结果 PASS=$pass FAIL=$fail =="
[[ $fail -eq 0 ]] && echo "M3-10 演练通过：三域等价、选域确定、切域生效、旧域不下线" || exit 1
