#!/usr/bin/env bash
# M1-06 验收脚本（DEVELOPMENT_PLAN：创建 200+code / DB 三表+route_json 一致 / 非白名单 400 / 他租户 403）
# 口径：直连 sl-admin:8030 并注入 X-Tenant-Id（模拟网关放行后的头部）；
#       HMAC 全链路已由 accept-m1-04.sh 在网关上验证，M1-10/12 起本脚本并入全链路。
set -uo pipefail
source "$(dirname "$0")/lib-devenv.sh"   # 口令从 deploy/compose/.env 注入，脚本里不写明文
ADMIN="${ADMIN:-http://localhost:8030}"
MYSQL="$MYSQL_EXEC -N -uroot"
RCLI="$COMPOSE exec -T redis redis-cli"
TA="1001"; TB="1002"
RUN=$(python3 -c 'import time;print(int(time.time()*1000))')
CODES=()

pass=0; fail=0
curl -sf "$ADMIN/actuator/health" >/dev/null || { echo "sl-admin 不可达（$ADMIN），先启动：java -jar sl-admin/target/sl-admin-0.1.0.jar"; exit 1; }
check() { # check <name> <expect> <actual>
  if [[ "$2" == "$3" ]]; then echo "PASS $1 ($3)"; ((pass++)); else echo "FAIL $1 expect=$2 actual=$3"; ((fail++)); fi
}

post() { # post <tenant> <body> [Idempotency-Key]
  local args=(-s -o /tmp/m106-body -w '%{http_code}' -X POST "$ADMIN/api/v1/links"
    -H "X-Tenant-Id: $1" -H 'Content-Type: application/json' -d "$2")
  [[ -n "${3:-}" ]] && args+=(-H "Idempotency-Key: $3")
  curl "${args[@]}"
}
get() { curl -s -o /tmp/m106-body -w '%{http_code}' "$ADMIN/api/v1/links/$2" -H "X-Tenant-Id: $1"; }
patch() { curl -s -o /tmp/m106-body -w '%{http_code}' -X PATCH "$ADMIN/api/v1/links/$2" -H "X-Tenant-Id: $1" -H 'Content-Type: application/json' -d "$3"; }
jval() { python3 -c "import json,sys;print(json.load(open('/tmp/m106-body'))$1)"; }

cleanup() {
  if [[ ${#CODES[@]} -gt 0 ]]; then
    local in=$(printf "'%s'," "${CODES[@]}"); in="(${in%,})"
    $MYSQL -e "DELETE FROM xsl_00.short_link WHERE short_code IN $in; DELETE FROM xsl_01.short_link WHERE short_code IN $in;
      DELETE FROM xsl_00.link_route WHERE short_code IN $in; DELETE FROM xsl_01.link_route WHERE short_code IN $in;
      DELETE FROM xsl_00.code_tenant_index WHERE short_code IN $in; DELETE FROM xsl_01.code_tenant_index WHERE short_code IN $in;
      DELETE FROM xsl_00.outbox WHERE aggregate_id IN $in; DELETE FROM xsl_01.outbox WHERE aggregate_id IN $in;
      DELETE FROM xsl_00.short_code_pool WHERE short_code IN $in; DELETE FROM xsl_01.short_code_pool WHERE short_code IN $in;" >/dev/null
    for c in "${CODES[@]}"; do $RCLI DEL "sl:r:$c" "sl:code:$c" >/dev/null; done
  fi
}
trap cleanup EXIT

# ── 1. 创建成功 → 200 + short_code + short_url
BODY="{\"origin_url\":\"https://mock.ticketsales.test/show/$RUN\",\"channel_id\":\"ch-wx\",\"campaign_id\":\"cp-m106\"}"
C=$(post "$TA" "$BODY" "idem-$RUN-create")
check "create-200" 200 "$C"
CODE=$(jval "['data']['short_code']")
CODES+=("$CODE")
check "create-code-len7" 7 "${#CODE}"
check "create-short-url" "https://xy1.test/$CODE" "$(jval "['data']['short_url']")"

# ── 2. DB 三表 + route_json 一致（分片键不同，双库求和）
N_LINK=$($MYSQL -e "SELECT COUNT(*) FROM (SELECT 1 FROM xsl_00.short_link WHERE short_code='$CODE' UNION ALL SELECT 1 FROM xsl_01.short_link WHERE short_code='$CODE') t;" 2>/dev/null | tail -1)
N_ROUTE=$($MYSQL -e "SELECT route_json FROM xsl_00.link_route WHERE short_code='$CODE' UNION ALL SELECT route_json FROM xsl_01.link_route WHERE short_code='$CODE';" 2>/dev/null | tail -1)
N_IDX=$($MYSQL -e "SELECT COUNT(*) FROM (SELECT 1 FROM xsl_00.code_tenant_index WHERE short_code='$CODE' UNION ALL SELECT 1 FROM xsl_01.code_tenant_index WHERE short_code='$CODE') t;" 2>/dev/null | tail -1)
N_OUT=$($MYSQL -e "SELECT COUNT(*) FROM (SELECT 1 FROM xsl_00.outbox WHERE aggregate_id='$CODE' AND event_type='LINK_CREATED' UNION ALL SELECT 1 FROM xsl_01.outbox WHERE aggregate_id='$CODE' AND event_type='LINK_CREATED') t;" 2>/dev/null | tail -1)
check "db-short_link" 1 "$N_LINK"
check "db-code_tenant_index" 1 "$N_IDX"
check "db-outbox-LINK_CREATED" 1 "$N_OUT"
if [[ "$N_ROUTE" == *"mock.ticketsales.test"* && "$N_ROUTE" == *'"version": 1'* ]]; then
  echo "PASS db-route_json-consistent"; ((pass++))
else
  echo "FAIL db-route_json-consistent: $N_ROUTE"; ((fail++))
fi
CACHE=$($RCLI GET "sl:r:$CODE")
if [[ "$CACHE" == *"mock.ticketsales.test"* ]]; then echo "PASS redis-route-cache"; ((pass++)); else echo "FAIL redis-route-cache: $CACHE"; ((fail++)); fi

# ── 3. 非白名单 / http → 400 SL-4001
C=$(post "$TA" "{\"origin_url\":\"https://evil.test/$RUN\",\"channel_id\":\"c\",\"campaign_id\":\"cp\"}")
check "non-whitelist-400" 400 "$C"
check "non-whitelist-code" "SL-4001" "$(jval "['code']")"
C=$(post "$TA" "{\"origin_url\":\"http://mock.ticketsales.test/$RUN\",\"channel_id\":\"c\",\"campaign_id\":\"cp\"}")
check "non-https-400" 400 "$C"

# ── 4. 越权：他租户 GET → 403；本租户 → 200
C=$(get "$TB" "$CODE"); check "cross-tenant-403" 403 "$C"
check "cross-tenant-code" "SL-4030" "$(jval "['code']")"
C=$(get "$TA" "$CODE"); check "owner-get-200" 200 "$C"

# ── 5. 自定义短码：创建 + 冲突 409 + 保留字 400
CUSTOM="cust$((RUN % 100000))"
C=$(post "$TA" "{\"origin_url\":\"https://mock.ticketsales.test/c/$RUN\",\"channel_id\":\"c\",\"campaign_id\":\"cp\",\"short_code\":\"$CUSTOM\"}")
check "custom-code-200" 200 "$C"; CODES+=("$CUSTOM")
C=$(post "$TB" "{\"origin_url\":\"https://mock.ticketsales.test/c2/$RUN\",\"channel_id\":\"c\",\"campaign_id\":\"cp\",\"short_code\":\"$CUSTOM\"}")
check "custom-code-conflict-409" 409 "$C"
C=$(post "$TA" "{\"origin_url\":\"https://mock.ticketsales.test/c3\",\"channel_id\":\"c\",\"campaign_id\":\"cp\",\"short_code\":\"api\"}")
check "reserved-word-400" 400 "$C"

# ── 6. PATCH 更新：access_limit → 缓存与 link_route version=2
C=$(patch "$TA" "$CODE" '{"access_limit":100}')
check "patch-200" 200 "$C"
CACHE=$($RCLI GET "sl:r:$CODE")
V=$($MYSQL -e "SELECT version FROM xsl_00.link_route WHERE short_code='$CODE' UNION ALL SELECT version FROM xsl_01.link_route WHERE short_code='$CODE';" 2>/dev/null | awk '{if($1>m)m=$1}END{print m}')
if [[ "$CACHE" == *'"access_limit": 100'* || "$CACHE" == *'"access_limit":100'* ]]; then echo "PASS patch-cache-updated"; ((pass++)); else echo "FAIL patch-cache-updated: $CACHE"; ((fail++)); fi
check "patch-route-version" 2 "$V"

# ── 7. 幂等：同 Idempotency-Key 二次创建 → 同一 short_code
BODY2="{\"origin_url\":\"https://mock.ticketsales.test/idem/$RUN\",\"channel_id\":\"c\",\"campaign_id\":\"cp\"}"
C=$(post "$TA" "$BODY2" "idem-$RUN-twice"); CODE1=$(jval "['data']['short_code']")
C=$(post "$TA" "$BODY2" "idem-$RUN-twice"); CODE2=$(jval "['data']['short_code']")
CODES+=("$CODE1")
check "idempotent-same-code" "$CODE1" "$CODE2"

echo "== $pass passed, $fail failed =="
[[ $fail -eq 0 ]]
