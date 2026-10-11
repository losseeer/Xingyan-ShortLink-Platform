#!/usr/bin/env bash
# M3-02 验收（outbox 同步器 + version 强校验通道 + 缓存失效广播，DESIGN 8.4/7.2）：
#   A. 亚秒收敛：封禁一条链接后，两个 jump 实例都在 2s 内改口，且各自的 evict 计数都涨
#      （只有 30s 本地缓存 TTL 的旧实现下这条必红，所以它自带对照）
#   B. 定时任务在部署形态下真的跑：把路由行弄没，不手动干预，等它自己补回来
#   C. 开关不是假的：XSL_OUTBOX_SYNC=false 时定时不跑，但手动 replay 仍能修（enabled 只管定时那一半）
#   —— 以下都在"定时关闭"的窗口里做，于是每一节的 scanned 都是确定值 ——
#   D. 缺行补偿：路由行与归属索引都没有（直写整段失败的最坏情况）→ 补回后 404 变 302
#   E. 落后补偿：快照被留在旧 version → 一轮补偿回到权威值
#   F. 不倒退：version 更低的事件重放，路由表与缓存都不认它，且该事件被置为已投递（不再重试）
#   G. 失败终态：payload 没有 route_json 的坏事件重试到上限后置 2，并进积压指标
#   H. 一轮扫描的代价：outbox 按 tenant_id 分片而扫描没有分片键（广播两库），灌 2000 行实测
#   I. 复位开关并确认恢复
# 前置：make images && make up（admin/gateway/jump 都得是含 M3-02 的镜像；admin 的
#       /actuator/prometheus 在补上 micrometer-registry-prometheus 之前一直是 404，见 ITER-M3 §4）。
# 口径：A/D/I 走 jump 直连端口（8020/8022）而不是 nginx——要按实例分别计时，轮询会把归属搞混；
#       另外经 nginx 的一次入口可达性放在 D 里断言，证明对外路径同样被补偿修复。
set -uo pipefail
source "$(dirname "$0")/lib-devenv.sh"   # 口令从 deploy/compose/.env 注入，脚本里不写明文

IP="${NGINX_ENTRY_IP:-127.0.0.1}"
PORT="${NGINX_PORT:-80}"
ADMIN="${ADMIN_DIRECT:-http://localhost:8030}"
J1="${JUMP1_DIRECT:-http://localhost:8020}"
J2="${JUMP2_DIRECT:-http://localhost:8022}"
TENANT=1001
RCLI="$COMPOSE exec -T redis redis-cli"
EVICT_CHANNEL="xsl:route:evict"   # 与 sl-common 的 RouteCacheContract.EVICT_CHANNEL 必须一致（写错这里探测就永远不通）
RUN=$(date +%s)
TAG="m302-$RUN"
CODE_FILE="/tmp/m302-codes.$RUN.txt"
: > "$CODE_FILE"

pass=0; fail=0
check() { if [[ "$2" == "$3" ]]; then echo "PASS $1 ($3)"; ((pass++)); else echo "FAIL $1 expect=$2 actual=$3"; ((fail++)); fi; }
check_le() { # check_le <名> <上限> <实际> [单位]
  if [[ "$3" -le "$2" ]]; then echo "PASS $1 (${3}${4:-} ≤ ${2}${4:-})"; ((pass++));
  else echo "FAIL $1 actual=${3}${4:-} limit=${2}${4:-}"; ((fail++)); fi; }
truthy() { # truthy <名> <0|1> <说明>
  if [[ "$2" == "1" ]]; then echo "PASS $1 ($3)"; ((pass++)); else echo "FAIL $1 ($3)"; ((fail++)); fi; }
note() { echo "     · $*"; }

# 两个物理库都要跑（outbox 按 tenant_id、link_route 按 short_code，分片轴不同）
MY() { $MYSQL_CLI -N -B -e "$1"; }
jstat() { curl -s -o /dev/null -m 10 -w '%{http_code}' "$1/$2"; }
jloc() { curl -s -o /dev/null -m 10 -D - "$1/$2" | tr -d '\r' | awk 'tolower($1)=="location:"{print $2}'; }
nginx_stat() { curl -s -o /dev/null -m 10 --resolve "xy1.test:$PORT:$IP" -w '%{http_code}' "http://xy1.test:$PORT/s/$1"; }

create_link() { # create_link <rate_limit> → short_code（限流阈值给高：A 段要按 100ms 反复打同一码）
  local body resp code
  body=$(printf '{"origin_url":"https://mock.ticketsales.test/%s","channel_id":"c-m302","campaign_id":"p-m302","promoter_id":"pr-1","redirect_type":1,"rate_limit_per_minute":%s}' "$TAG" "$1")
  resp=$(curl -s -X POST "$ADMIN/api/v1/links" -H 'Content-Type: application/json' \
    -H "X-Tenant-Id: $TENANT" -H "Idempotency-Key: m302-$RUN-$RANDOM" -d "$body")
  # 取值一律用 sed，不嵌 python（M3-01 的引号教训：双引号里的单引号字符类会把串提前闭合）
  code=$(printf '%s' "$resp" | sed -n 's/.*"short_code":"\([A-Za-z0-9]*\)".*/\1/p')
  if [[ -z "$code" ]]; then echo "FATAL 创建失败: $resp" >&2; exit 1; fi
  printf '%s\n' "$code" >> "$CODE_FILE"          # 见 cleanup() 的注释：这里在子 shell 里，只有文件能带出去
  echo "$code"
}
patch_link() { curl -s -o /dev/null -m 10 -w '%{http_code}' -X PATCH "$ADMIN/api/v1/links/$1" \
  -H "X-Tenant-Id: $TENANT" -H 'Content-Type: application/json' -d "$2"; }
replay() { # replay [short_code] → 响应 JSON 原文
  if [[ $# -gt 0 ]]; then curl -s -m 30 -XPOST "$ADMIN/internal/outbox/replay?short_code=$1";
  else curl -s -m 30 -XPOST "$ADMIN/internal/outbox/replay"; fi
}
replay_timed() { curl -s -o /tmp/m302-replay.json -m 60 -XPOST "$ADMIN/internal/outbox/replay" -w '%{time_total}'; }
jfield() { printf '%s' "$1" | sed -n "s/.*\"$2\":\([0-9-]*\).*/\1/p"; }
metric() { # metric <base-url> <指标名> [标签子串] → 数值（取不到当 0，别让空值把算术炸掉）
  local v
  # 名称后面必须紧跟 { 或空白，否则 xsl_..._created 那种派生行会被当成计数值本身
  v=$(curl -s -m 5 "$1/actuator/prometheus" \
      | awk -v n="$2" -v l="${3:-}" '$1 ~ ("^" n "($|[[:space:]]|\\{)") && (l == "" || index($1,l)>0) {print $2; exit}')
  echo "${v:-0}"
}
route_version() { MY "SELECT version FROM xsl_00.link_route WHERE short_code='$1' UNION ALL SELECT version FROM xsl_01.link_route WHERE short_code='$1';" | head -1; }
route_json_of() { MY "SELECT route_json FROM xsl_00.link_route WHERE short_code='$1' UNION ALL SELECT route_json FROM xsl_01.link_route WHERE short_code='$1';" | head -1; }
cache_version() { # 缓存里那份快照的 version（路由表读回来的文本被 MySQL 归一化成 "version": 1，要容得下空格）
  $RCLI GET "sl:r:$1" 2>/dev/null | sed -n 's/.*"version":[[:space:]]*\([0-9]*\).*/\1/p';
}
event_field() { # event_field <code> <event_type> <列> → 该事件的某一列（fixture 事件用专用 event_type，取数就不含糊）
  MY "SELECT $3 FROM xsl_00.outbox WHERE aggregate_id='$1' AND event_type='$2' UNION ALL
      SELECT $3 FROM xsl_01.outbox WHERE aggregate_id='$1' AND event_type='$2';" | head -1;
}

# 把一条链接复原成"直写整段失败"的样子：路由行、归属索引、缓存都没有，事件回到待投递
break_route() {
  MY "DELETE FROM xsl_00.link_route WHERE short_code='$1'; DELETE FROM xsl_01.link_route WHERE short_code='$1';
      DELETE FROM xsl_00.code_tenant_index WHERE short_code='$1'; DELETE FROM xsl_01.code_tenant_index WHERE short_code='$1';
      UPDATE xsl_00.outbox SET status=0, retry_count=0 WHERE aggregate_id='$1';
      UPDATE xsl_01.outbox SET status=0, retry_count=0 WHERE aggregate_id='$1';" >/dev/null
  $RCLI DEL "sl:r:$1" "sl:r:nx:$1" >/dev/null
}

put_event() { # put_event <code> <event_type> <route_json 或 任意 payload> <版本> —— 直插租户所在分片库
  local db=xsl_01 payload=$3
  [[ $((TENANT % 2)) -eq 0 ]] && db=xsl_00
  if [[ "$4" == "-" ]]; then payload="$3"; else
    payload="{\"short_code\":\"$1\",\"route_json\":$3,\"reason\":\"FIXTURE\"}"
  fi
  MY "INSERT INTO $db.outbox (aggregate_type, aggregate_id, tenant_id, event_type, payload_json, status)
      VALUES ('LINK', '$1', $TENANT, '$2', CAST('$(printf '%s' "$payload" | sed "s/'/''/g")' AS JSON), 0);" >/dev/null
}

FLIPPED=0
cleanup() {
  # 被中断时也必须把定时开关复位：上一轮 Ctrl-C/管道截断会把 admin 留在 false 上，
  # 于是下一轮的 B 节"等定时自己补"永远等不到——那是环境的残留状态，不是产品缺陷（本机实测踩过）。
  if [[ $FLIPPED -eq 1 ]]; then
    XSL_OUTBOX_SYNC=true $COMPOSE up -d admin >/dev/null 2>&1
  fi
  # 收集短码要用文件，不能用数组：create_link 是在 $(...) 里调用的，命令替换=子 shell，
  # 子 shell 里的 CODES+=() 改的是副本、父 shell 永远看不见——上一版就是这样，cleanup 静默空跑了 5 轮，
  # 本机因此留下 42 个 fixture 码（已手工清掉，教训记进 ITER-M3 §4）。
  if [[ -s "$CODE_FILE" ]]; then
    local in; in=$(printf "'%s'," $(cat "$CODE_FILE")); in="(${in%,})"
    MY "DELETE FROM xsl_00.short_link WHERE short_code IN $in; DELETE FROM xsl_01.short_link WHERE short_code IN $in;
        DELETE FROM xsl_00.link_route WHERE short_code IN $in; DELETE FROM xsl_01.link_route WHERE short_code IN $in;
        DELETE FROM xsl_00.code_tenant_index WHERE short_code IN $in; DELETE FROM xsl_01.code_tenant_index WHERE short_code IN $in;
        DELETE FROM xsl_00.outbox WHERE aggregate_id IN $in; DELETE FROM xsl_01.outbox WHERE aggregate_id IN $in;
        DELETE FROM xsl_00.short_code_pool WHERE short_code IN $in; DELETE FROM xsl_01.short_code_pool WHERE short_code IN $in;" >/dev/null
    while read -r c; do $RCLI DEL "sl:r:$c" "sl:r:nx:$c" "sl:code:$c" >/dev/null; done < "$CODE_FILE"
    rm -f "$CODE_FILE"
  fi
  MY "DELETE FROM xsl_00.outbox WHERE event_type LIKE 'M302-LOAD%'; DELETE FROM xsl_01.outbox WHERE event_type LIKE 'M302-LOAD%';" >/dev/null
}
trap cleanup EXIT

set_sync() { # set_sync <true|false>：改一个环境变量就换掉定时那一半（compose 已透传）
  XSL_OUTBOX_SYNC=$1 $COMPOSE up -d --wait admin >/dev/null 2>&1
  [[ "$1" == "false" ]] && FLIPPED=1 || FLIPPED=0
  docker exec xsl-admin-1 printenv XSL_OUTBOX_SYNC 2>/dev/null
}

echo "== M3-02 outbox 补偿与缓存失效广播（run=$RUN tag=$TAG）=="
# 前置探测：两个 jump 实例是否真能收到广播。
# 上一轮验收里 Redis 被停过（accept-m2-13 的 H 段就是 `docker stop redis`），而订阅恢复实测要 6–18s
# （2026-10-11 本机两次量过）——那个窗口里发广播必丢，A 段的"3s 内收敛"就成了环境问题而不是产品问题。
# 先探测再断言：不通就一直等，最多 36s，仍不通就明确报"环境不满足"，别让它伪装成一次回归。
num_gt() { awk -v a="$1" -v b="$2" 'BEGIN{print (a+0>b+0)?1:0}'; }
P1=$(metric "$J1" xsl_jump_route_evict_total); P2=$(metric "$J2" xsl_jump_route_evict_total)
probed=0
for i in $(seq 1 12); do
  $RCLI PUBLISH "$EVICT_CHANNEL" "m302-probe-$i" >/dev/null
  sleep 3
  Q1=$(metric "$J1" xsl_jump_route_evict_total); Q2=$(metric "$J2" xsl_jump_route_evict_total)
  if [[ "$(num_gt "$Q1" "$P1")" == "1" && "$(num_gt "$Q2" "$P2")" == "1" ]]; then probed=1; break; fi
  P1=$Q1; P2=$Q2
done
if [[ $probed -ne 1 ]]; then
  echo "FATAL 两个实例收不到广播（探测 36s 未果；jump-1=$P1 jump-2=$P2）"
  echo "      多半是 Redis 刚被停过、订阅还没回来，或 admin 侧广播链路断了。等一会儿重跑；"
  echo "      期间正确性不受影响——新值仍在 link_route 与 L2，本地缓存在 L1 TTL（30s）后自愈（DESIGN 8.4）。"
  exit 1
fi
note "前置探测通过：第 $i 次探测起两个实例都能收到广播（约 $((i*3))s）"
# 前置：定时必须是开着的——B 节要靠它自己补。上一轮被中断时没复位就先到这里停住，别让 B 假失败。
if [[ "$(docker exec xsl-admin-1 printenv XSL_OUTBOX_SYNC 2>/dev/null)" != "true" ]]; then
  echo "FATAL admin 容器的 XSL_OUTBOX_SYNC 不是 true（定时被上一轮验收关掉了？）"
  echo "      先复位：XSL_OUTBOX_SYNC=true docker compose -f deploy/compose/docker-compose.yml up -d admin"
  exit 1
fi

# ---------------- A. 封禁的跨实例收敛 ----------------
echo "-- A 亚秒收敛：紧急封禁要两个实例几乎同时改口"
EV1=$(metric "$J1" xsl_jump_route_evict_total); EV2=$(metric "$J2" xsl_jump_route_evict_total)
A=$(create_link 900)
check "A-warm-jump1-302" 302 "$(jstat "$J1" "$A")"      # 冷：回源并填进本地缓存
check "A-warm-jump2-302" 302 "$(jstat "$J2" "$A")"
# 下面这几行是"0ms 收敛"的前提：两个实例确实各自握着旧快照，而不是根本没缓存过
L1A=$(metric "$J1" xsl_jump_route_lookup_total 'level="local"'); L2A=$(metric "$J2" xsl_jump_route_lookup_total 'level="local"')
check "A-cached-jump1-302" 302 "$(jstat "$J1" "$A")"
check "A-cached-jump2-302" 302 "$(jstat "$J2" "$A")"
L1B=$(metric "$J1" xsl_jump_route_lookup_total 'level="local"'); L2B=$(metric "$J2" xsl_jump_route_lookup_total 'level="local"')
truthy "A-both-instances-hold-l1" "$( [[ "${L1B%%.*}" -gt "${L1A%%.*}" && "${L2B%%.*}" -gt "${L2A%%.*}" ]] && echo 1 || echo 0 )" \
  "二查都命中本地缓存（jump1 $L1A→$L1B，jump2 $L2A→$L2B）"
patch_link "$A" '{"status":2}' >/dev/null
tick=0; a1=""; a2=""
while [[ $tick -lt 30 ]]; do
  if [[ -z $a1 && "$(jstat "$J1" "$A")" == "403" ]]; then a1=$tick; fi
  if [[ -z $a2 && "$(jstat "$J2" "$A")" == "403" ]]; then a2=$tick; fi
  if [[ -n $a1 && -n $a2 ]]; then break; fi
  tick=$((tick+1)); sleep 0.1
done
if [[ -z $a1 || -z $a2 ]]; then
  check "A-converge-both-instances" "两个实例都在 3s 内返回 403" "jump1=${a1:-未收敛} jump2=${a2:-未收敛}"
else
  d=$((a1 - a2)); [[ $d -lt 0 ]] && d=$((-d))
  check_le "A-converge-jump1" 2000 $((a1 * 100)) ms
  check_le "A-converge-jump2" 2000 $((a2 * 100)) ms
  check_le "A-converge-skew" 500 $((d * 100)) ms
  note "收敛耗时：jump1=$((a1 * 100))ms、jump2=$((a2 * 100))ms、两实例时差=$((d * 100))ms（没有广播时只能等 L1 的 30s TTL）"
fi
NEW1=$(metric "$J1" xsl_jump_route_evict_total); NEW2=$(metric "$J2" xsl_jump_route_evict_total)
truthy "A-evict-counters-up" "$( [[ "${NEW1%%.*}" -gt "${EV1%%.*}" && "${NEW2%%.*}" -gt "${EV2%%.*}" ]] && echo 1 || echo 0 )" \
  "两实例的 evict 计数都增长（jump1 $EV1→$NEW1，jump2 $EV2→$NEW2）"
patch_link "$A" '{"status":0}' >/dev/null
sleep 1
check "A-unban-jump1" 302 "$(jstat "$J1" "$A")"
check "A-unban-jump2" 302 "$(jstat "$J2" "$A")"

# ---------------- B. 部署形态下定时任务自己会跑 ----------------
echo "-- B 定时补偿：不手动干预，等 @Scheduled 自己把缺行补回来"
B=$(create_link 60)
break_route "$B"
check "B-broken-404" 404 "$(jstat "$J1" "$B")"
waited=0
while [[ $waited -lt 30 && -z "$(route_version "$B")" ]]; do sleep 1; waited=$((waited+1)); done
check_le "B-auto-fixed-after-seconds" 15 "$waited" s
check "B-auto-restored-302" 302 "$(jstat "$J1" "$B")"
check "B-auto-event-delivered" 1 "$(event_field "$B" LINK_CREATED status)"

# ---------------- C. 开关透传：关掉定时 ----------------
echo "-- C 开关：XSL_OUTBOX_SYNC=false 时定时不跑"
C=$(create_link 60)
IN_ENV=$(set_sync false)
check "C-env-pass-through" "false" "${IN_ENV:-缺失}"
break_route "$C"
sleep 12
check "C-scheduled-skipped" 0 "$(event_field "$C" LINK_CREATED status)"
check "C-still-broken" 404 "$(jstat "$J1" "$C")"
RESP=$(replay "$C")
check "C-manual-replay-works" 1 "$(jfield "$RESP" inserted)"
check "C-fixed-after-manual" 302 "$(jstat "$J1" "$C")"
note "从这里往下都在定时关闭的窗口里跑，每节 scanned 都是确定值"

# ---------------- D. 缺行补偿（成对补） ----------------
echo "-- D 缺行补偿：路由行与归属索引都要补回来"
D=$(create_link 60)
break_route "$D"
check "D-broken-404" 404 "$(jstat "$J1" "$D")"
RESP=$(replay "$D")
check "D-scanned" 1 "$(jfield "$RESP" scanned)"
check "D-inserted" 1 "$(jfield "$RESP" inserted)"
check "D-applied" 0 "$(jfield "$RESP" applied)"
check "D-route-version" 1 "$(route_version "$D")"
N_INDEX=$(MY "SELECT count(*) FROM xsl_00.code_tenant_index WHERE short_code='$D' UNION ALL SELECT count(*) FROM xsl_01.code_tenant_index WHERE short_code='$D';" | awk '{s+=$1} END{print s+0}')
check "D-tenant-index-paired" 1 "${N_INDEX:-0}"
truthy "D-route-json-is-the-event" "$(route_json_of "$D" | grep -c "$TAG" | awk '{print ($1>0)?1:0}')" "补出来的快照就是事件里那一份"
check "D-cache-version" 1 "$(cache_version "$D")"
check "D-jump-302" 302 "$(jstat "$J1" "$D")"
truthy "D-location-carries-origin" "$(jloc "$J1" "$D" | grep -c "$TAG" | awk '{print ($1>0)?1:0}')" "302 的 Location 回到事件里的 origin_url"
check "D-via-nginx-302" 302 "$(nginx_stat "$D")"
check "D-event-delivered" 1 "$(event_field "$D" LINK_CREATED status)"

# ---------------- E. 落后补偿 ----------------
echo "-- E 落后补偿：快照被留在旧 version 时要回到权威值"
E=$(create_link 60)
patch_link "$E" '{"access_limit":200}' >/dev/null      # version 1 → 2
MY "UPDATE xsl_00.link_route SET route_json = CAST('{\"origin_url\":\"https://mock.ticketsales.test/STALE-$TAG\",\"tenant_id\":$TENANT,\"redirect_type\":1,\"expire_time\":null,\"access_limit\":7,\"status\":0,\"version\":1}' AS JSON), version=1 WHERE short_code='$E';
    UPDATE xsl_01.link_route SET route_json = CAST('{\"origin_url\":\"https://mock.ticketsales.test/STALE-$TAG\",\"tenant_id\":$TENANT,\"redirect_type\":1,\"expire_time\":null,\"access_limit\":7,\"status\":0,\"version\":1}' AS JSON), version=1 WHERE short_code='$E';" >/dev/null
$RCLI DEL "sl:r:$E" >/dev/null
RESP=$(replay "$E")
check "E-scanned" 2 "$(jfield "$RESP" scanned)"        # 创建(v1) + 改配额(v2) 两条事件都待投递
check "E-applied" 1 "$(jfield "$RESP" applied)"
check "E-superseded" 1 "$(jfield "$RESP" superseded)"  # v1 那条已被表里的 v1 挡住（不写表、也不动缓存）
check "E-version-back" 2 "$(route_version "$E")"
truthy "E-stale-gone" "$(route_json_of "$E" | grep -c "STALE" | awk '{print ($1==0)?1:0}')" "落后的快照被事件推进"
check "E-cache-version" 2 "$(cache_version "$E")"
check "E-jump-302-uses-fresh-origin" 1 "$(jloc "$J1" "$E" | grep -c "$TAG" | awk '{print ($1>0)?1:0}')"

# ---------------- F. 旧事件绝不倒退 ----------------
echo "-- F 不倒退：version 更低的事件重放，路由表与缓存都不认它"
F=$(create_link 60)
patch_link "$F" '{"access_limit":300}' >/dev/null
patch_link "$F" '{"access_limit":400}' >/dev/null      # v3
replay "$F" >/dev/null                                 # 先把真事件投递干净，scanned 才是确定值
check "F-version-3" 3 "$(route_version "$F")"
put_event "$F" "M302-OLD" "{\"origin_url\":\"https://mock.ticketsales.test/NEVER-$TAG\",\"tenant_id\":$TENANT,\"redirect_type\":1,\"expire_time\":null,\"access_limit\":7,\"status\":0,\"version\":2}" 2
RESP=$(replay "$F")
check "F-scanned" 1 "$(jfield "$RESP" scanned)"
check "F-superseded" 1 "$(jfield "$RESP" superseded)"
check "F-applied" 0 "$(jfield "$RESP" applied)"
check "F-version-kept" 3 "$(route_version "$F")"
truthy "F-old-event-not-landed" "$(route_json_of "$F" | grep -c "NEVER" | awk '{print ($1==0)?1:0}')" "旧事件内容没进路由表"
check "F-cache-kept-authoritative" 3 "$(cache_version "$F")"
truthy "F-cache-not-overwritten" "$($RCLI GET "sl:r:$F" 2>/dev/null | grep -c "NEVER" | awk '{print ($1==0)?1:0}')" "缓存也不被旧事件覆盖（写的是表里那份权威值）"
check "F-old-event-delivered" 1 "$(event_field "$F" M302-OLD status)"
note "旧事件被判为已被取代后直接置 1，不会每轮重投（这是「重放整张 outbox 安全」的另一半）"

# ---------------- G. 坏事件到上限置 2 ----------------
echo "-- G 失败终态：补不动的事件要停下并可见"
G=$(create_link 60)
replay "$G" >/dev/null
put_event "$G" "M302-BAD" '{"short_code":"PLACEHOLDER","reason":"NO_ROUTE_JSON"}' -
round=0
while [[ $round -lt 14 && "$(event_field "$G" M302-BAD status)" != "2" ]]; do
  replay "$G" >/dev/null; round=$((round+1)); sleep 0.2
done
check "G-status-failed" 2 "$(event_field "$G" M302-BAD status)"
RETRY=$(event_field "$G" M302-BAD retry_count)
truthy "G-retry-count-moved" "$( [[ -n "$RETRY" && "$RETRY" -ge 3 ]] && echo 1 || echo 0 )" "重试 $RETRY 次后置 2（部署口径 max-retry=10）"
check "G-route-untouched" 1 "$(route_version "$G")"
BACKLOG2=$(metric "$ADMIN" xsl_outbox_backlog 'status="2"')
FAILED_M=$(metric "$ADMIN" xsl_outbox_sync_total 'result="failed"')
truthy "G-backlog-gauge-visible" "$( [[ "${BACKLOG2%%.*}" -ge 1 ]] && echo 1 || echo 0 )" "积压指标 status=2 = $BACKLOG2"
truthy "G-failed-metric-visible" "$( [[ "${FAILED_M%%.*}" -ge 1 ]] && echo 1 || echo 0 )" "失败计数 = $FAILED_M"

# ---------------- H. 一轮扫描的代价 ----------------
echo "-- H 扫描代价：outbox 按 tenant_id 分片而扫描没有分片键（广播两库）"
MY "INSERT INTO xsl_01.outbox (aggregate_type, aggregate_id, tenant_id, event_type, payload_json, status)
    SELECT 'LINK', lr.short_code, ci.tenant_id, 'M302-LOAD', JSON_OBJECT('short_code', lr.short_code, 'route_json', CAST(lr.route_json AS JSON)), 0
    FROM xsl_01.link_route lr JOIN xsl_01.code_tenant_index ci ON ci.short_code = lr.short_code
    WHERE ci.tenant_id % 2 = 1 LIMIT 1000;
    INSERT INTO xsl_00.outbox (aggregate_type, aggregate_id, tenant_id, event_type, payload_json, status)
    SELECT 'LINK', lr.short_code, ci.tenant_id, 'M302-LOAD', JSON_OBJECT('short_code', lr.short_code, 'route_json', CAST(lr.route_json AS JSON)), 0
    FROM xsl_00.link_route lr JOIN xsl_00.code_tenant_index ci ON ci.short_code = lr.short_code
    WHERE ci.tenant_id % 2 = 0 LIMIT 1000;" >/dev/null
LOAD_ROWS=$(MY "SELECT count(*) FROM xsl_00.outbox WHERE event_type='M302-LOAD';" | head -1)
LOAD_ROWS=$((LOAD_ROWS + $(MY "SELECT count(*) FROM xsl_01.outbox WHERE event_type='M302-LOAD';" | head -1)))
APPLIED_BEFORE=$(metric "$ADMIN" xsl_outbox_sync_total 'result="applied"')
WR0=$(metric "$ADMIN" xsl_route_cache_write_total 'result="written"'); KP0=$(metric "$ADMIN" xsl_route_cache_write_total 'result="kept"')
SENT0=$(metric "$ADMIN" xsl_route_evict_total 'result="sent"')
total=0; rounds=0; sup=0; scanned=0; left=1
while [[ $rounds -lt 12 ]]; do
  t=$(replay_timed); rounds=$((rounds+1))
  total=$(awk -v a="$total" -v b="${t:-0}" 'BEGIN{printf "%.3f", a+b}')
  body=$(cat /tmp/m302-replay.json)
  s1=$(jfield "$body" superseded | head -1); sup=$((sup + ${s1:-0}))
  s2=$(jfield "$body" scanned | head -1); scanned=$((scanned + ${s2:-0}))
  left=$(jfield "$body" backlog | head -1)
  [[ "${left:-1}" -eq 0 ]] && break
done
check "H-drained-to-zero" 0 "${left:-1}"
truthy "H-all-superseded" "$( [[ $sup -ge $LOAD_ROWS && $scanned -ge $LOAD_ROWS ]] && echo 1 || echo 0 )" "全部判为已被取代：scanned=$scanned superseded=$sup / 灌入=$LOAD_ROWS"
APPLIED_AFTER=$(metric "$ADMIN" xsl_outbox_sync_total 'result="applied"')
truthy "H-no-route-touched" "$( [[ "${APPLIED_AFTER%%.*}" -eq "${APPLIED_BEFORE%%.*}" ]] && echo 1 || echo 0 )"   "整批重放没有改动任何一行路由（applied 计数 ${APPLIED_BEFORE}→${APPLIED_AFTER}）"
WR1=$(metric "$ADMIN" xsl_route_cache_write_total 'result="written"'); KP1=$(metric "$ADMIN" xsl_route_cache_write_total 'result="kept"')
SENT1=$(metric "$ADMIN" xsl_route_evict_total 'result="sent"')
DW=$(( ${WR1%%.*} - ${WR0%%.*} )); DK=$(( ${KP1%%.*} - ${KP0%%.*} )); DS=$(( ${SENT1%%.*} - ${SENT0%%.*} ))
truthy "H-every-row-decided-once" "$( [[ $((DW + DK)) -eq $LOAD_ROWS ]] && echo 1 || echo 0 )" \
  "每行都过一次 version 守卫：写动 $DW + 被挡下 $DK = 灌入 $LOAD_ROWS"
truthy "H-broadcast-follows-writes" "$( [[ $DS -le $DW ]] && echo 1 || echo 0 )" \
  "广播条数 $DS ≤ 写动条数 $DW（被守卫挡下的那些不发通知，两实例不会被白清）"
note "定时关闭时手动整批 replay ${rounds} 轮扫完 ${LOAD_ROWS} 行，累计 ${total}s（一轮 batch=500；每轮两条广播 count + 每行一次 Lua EVAL + 写动才广播）"
note "灌入按分片轴配平：xsl_01（tenant_id 奇数）=$(MY "SELECT count(*) FROM xsl_01.outbox WHERE event_type='M302-LOAD';" | head -1) 行、xsl_00（偶数）=$(MY "SELECT count(*) FROM xsl_00.outbox WHERE event_type='M302-LOAD';" | head -1) 行；插错库的话 ShardingSphere 按 tenant_id 路由根本读不到，所以另一库里那些属于奇数租户的码要跳过"
note "本机 link_route 总行数=$(MY "SELECT count(*) FROM xsl_00.link_route;" | head -1)+$(MY "SELECT count(*) FROM xsl_01.link_route;" | head -1)，本节的样本取自其中真实存在的链接（事件与快照同 version）"

# ---------------- I. 复位开关 ----------------
echo "-- I 复位：改回 true 后定时重新接管"
IN_ENV=$(set_sync true)
check "I-env-restored" "true" "${IN_ENV:-缺失}"
I=$(create_link 60)
break_route "$I"
check "I-broken-404" 404 "$(jstat "$J1" "$I")"
waited=0
while [[ $waited -lt 30 && "$(jstat "$J1" "$I")" != "302" ]]; do sleep 1; waited=$((waited+1)); done
check_le "I-auto-fixed-seconds" 30 "$waited" s
check "I-event-delivered" 1 "$(event_field "$I" LINK_CREATED status)"

echo
echo "== 结果 PASS=$pass FAIL=$fail =="
[[ $fail -eq 0 ]] && echo "M3-02 验收全绿" || exit 1
