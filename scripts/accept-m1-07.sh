#!/usr/bin/env bash
# M1-07 验收脚本：302 Location 正确 / 不存在→404+空值标记 / 停用→403 / 过期·超限→410 /
# 冷码回源后二查命中缓存（metrics xsl_jump_route_lookup_total{level="db"} 不再增长佐证）。
# 口径：直连 sl-jump:8020（/s/**→jump 的 nginx 接线在 M1-10）；fixture 直插物理分片。
set -uo pipefail
JUMP="${JUMP:-http://localhost:8020}"
COMPOSE="docker compose -f deploy/compose/docker-compose.yml"
MYSQL="$COMPOSE exec -T mysql mysql -N -uroot -pxsl-dev"
RCLI="$COMPOSE exec -T redis redis-cli"
ORIGIN="https://mock.ticketsales.test/e2e-m107"
RUN=$(python3 -c 'import time;print(int(time.time()*1000))')
TAIL=$((RUN % 100000))
CODES=("m107ok$TAIL" "m107exp$TAIL" "m107off$TAIL" "m107lim$TAIL" "m107nn$TAIL" "m107wm$TAIL")

pass=0; fail=0
check() { if [[ "$2" == "$3" ]]; then echo "PASS $1 ($3)"; ((pass++)); else echo "FAIL $1 expect=$2 actual=$3"; ((fail++)); fi; }

route_json() { # route_json <status> <expire|null> <limit|null>
  printf '{"origin_url":"%s","tenant_id":1001,"redirect_type":1,"expire_time":%s,"access_limit":%s,"status":%s,"version":1}' "$ORIGIN" "$2" "$3" "$1"
}

# Java String.hashCode → 物理库（INLINE: Math.abs(hashCode)%2），与 sharding-jump.yaml 完全一致
phys_db() {
  python3 -c "
s='$1'
h=0
for ch in s: h=(31*h+ord(ch)) & 0xFFFFFFFF
if h >= 2**31: h -= 2**32
import math
print('xsl_%02d' % (abs(h) % 2))"
}

insert_route() { # insert_route <code> <json>
  local db=$(phys_db "$1")
  $MYSQL -e "INSERT INTO $db.link_route (short_code, route_json, version) VALUES ('$1', CAST('$2' AS JSON), 1)
             ON DUPLICATE KEY UPDATE route_json=VALUES(route_json);" > /dev/null 2>&1
  # 短码必须 ≤12 字符（列宽），插不进去立即失败而不是伪装成 404
  local n=$($MYSQL -N -e "SELECT COUNT(*) FROM $db.link_route WHERE short_code='$1';" 2>/dev/null | tail -1)
  [[ "$n" == "1" ]] || { echo "fixture 落库失败: $1 → $db (count=$n)"; exit 1; }
  echo "$db"
}

cleanup() {
  local in=$(printf "'%s'," "${CODES[@]}"); in="(${in%,})"
  $MYSQL -e "DELETE FROM xsl_00.link_route WHERE short_code IN $in; DELETE FROM xsl_01.link_route WHERE short_code IN $in;" >/dev/null 2>&1
  for c in "${CODES[@]}"; do $RCLI DEL "sl:r:$c" "sl:r:nx:$c" "sl:cnt:$c" > /dev/null; done
}
trap cleanup EXIT

curl -sf "$JUMP/actuator/health" >/dev/null || { echo "sl-jump 不可达（$JUMP），先启动 java -jar sl-jump/target/sl-jump-0.1.0.jar"; exit 1; }

# fixtures：直插物理分片 + 清缓存，模拟冷码
cleanup
DB_OK=$(insert_route "${CODES[0]}" "$(route_json 0 null null)")
DB_EXP=$(insert_route "${CODES[1]}" "$(route_json 0 "\"2025-01-01T00:00:00\"" null)")
DB_OFF=$(insert_route "${CODES[2]}" "$(route_json 1 null null)")
DB_LIM=$(insert_route "${CODES[3]}" "$(route_json 0 null 2)")
DB_WARM=$(insert_route "${CODES[5]}" "$(route_json 0 null null)")
echo "fixtures → $DB_OK/$DB_EXP/$DB_OFF/$DB_LIM/(warm)$DB_WARM"

code_of() { curl -s -o /tmp/m107-body -w '%{http_code}' "$JUMP$1"; }
loc_of() { curl -s -o /dev/null -D - "$JUMP$1" | awk 'tolower($1)=="location:"{print $2}' | tr -d '\r'; }
metric_db() { curl -s "$JUMP/actuator/prometheus" | awk '/xsl_jump_route_lookup_total\{.*level="db"/{print $2}' | tail -1; }

# 1. 正常码 → 302 + Location 正确（/s/ 前缀与裸路径同义）
check "redirect-302" 302 "$(code_of "/s/${CODES[0]}")"
check "redirect-location" "$ORIGIN" "$(loc_of "/s/${CODES[0]}")"
check "bare-path-302" 302 "$(code_of "/${CODES[0]}")"

# 2. 冷码二查命中缓存：首查 db+1，二查 db 不增；Redis 写回存在
M1=$(metric_db)
check "warm-first-302" 302 "$(code_of "/s/${CODES[5]}")"
M2=$(metric_db)
check "warm-second-302" 302 "$(code_of "/s/${CODES[5]}")"
M3=$(metric_db)
if [[ -n "$M2" && "$M3" == "$M2" ]]; then echo "PASS db-hit-once ($M2→$M3)"; ((pass++)); else echo "FAIL db-hit-once: '$M2' vs '$M3'"; ((fail++)); fi
if [[ -n "$M1" ]]; then echo "note: baseline db=$M1"; fi
check "writeback-redis" 1 "$($RCLI EXISTS "sl:r:${CODES[5]}")"

# 3. 不存在 → 404 + 空值标记
check "notfound-404" 404 "$(code_of "/s/${CODES[4]}")"
check "null-marker-set" 1 "$($RCLI EXISTS "sl:r:nx:${CODES[4]}")"

# 4. 过期 → 410；停用 → 403
check "expired-410" 410 "$(code_of "/s/${CODES[1]}")"
check "disabled-403" 403 "$(code_of "/s/${CODES[2]}")"

# 5. access_limit=2：302,302,410
check "limit-first" 302 "$(code_of "/s/${CODES[3]}")"
check "limit-second" 302 "$(code_of "/s/${CODES[3]}")"
check "limit-third-410" 410 "$(code_of "/s/${CODES[3]}")"

echo "== $pass passed, $fail failed =="
[[ $fail -eq 0 ]]
