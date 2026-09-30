#!/usr/bin/env bash
# M1-10 验收（DEVELOPMENT_PLAN 验收列）：
#   A. curl --resolve 三个域同一 code 得同一 302（跨域通用首证，DESIGN 10.2）
#   B. nginx upstream 轮询两个 jump 实例（access log 实测分布）
#   C. 滚动重启：重启其中一个 jump 实例期间持续打流，0×5xx（DESIGN 3.3 卖点实测 #1）
#   D. 被摘实例回轮询；管理面（gateway+admin）整体宕机不影响存量跳转（数据面独立）
#   E. 未登记 Host 掐断；/stats 读侧经 nginx 命中 consumer
# 前置：scripts/build-images.sh 已出镜像且 docker compose up -d 已拉起。
# 口径：不改宿主 /etc/hosts，全部用 curl --resolve 打到 127.0.0.1:80，由 nginx 按 server_name 分流。
set -uo pipefail
IP="${NGINX_ENTRY_IP:-127.0.0.1}"
PORT="${NGINX_PORT:-80}"
DOMAINS=(xy1.test xy2.test xy3.test)
ADMIN_DIRECT="${ADMIN_DIRECT:-http://localhost:8030}"
COMPOSE="docker compose -f deploy/compose/docker-compose.yml"
J1=xsl-jump-1-1
J2=xsl-jump-2-1
TENANT=1001
LOAD_SECS=${LOAD_SECS:-20}
pass=0; fail=0
check() { if [[ "$2" == "$3" ]]; then echo "PASS $1 ($3)"; ((pass++)); else echo "FAIL $1 expect=$2 actual=$3"; ((fail++)); fi; }
note() { echo "     · $*"; }
u() { curl -s -o /dev/null -m 10 --resolve "$1:$PORT:$IP" -w '%{http_code}|%{redirect_url}' "http://$1$2"; }
# 剔除每次点击都变的 xy_click_id，只比可复现部分
loc_comparable() { python3 -c "
import sys
url = sys.argv[1]
base, _, tail = url.partition('?')
if not tail: print(url); raise SystemExit
query, sep, frag = tail.partition('#')
pairs = [p for p in query.split('&') if p and not p.startswith('xy_click_id')]
print(base + '?' + '&'.join(pairs) + sep + frag)" "$1"; }

$COMPOSE exec -T nginx nginx -t >/dev/null 2>&1 || { echo "nginx 配置未就绪，先 docker compose up -d"; exit 1; }
curl -s -m 5 -o /dev/null "http://$IP:$PORT/healthz" || { echo "nginx :$PORT 不可达"; exit 1; }

RUN=$(date +%s)
BODY=$(cat <<JSON
{"origin_url":"https://mock.ticketsales.test/m110?seat=A#p",
 "channel_id":"c-m110","campaign_id":"p-m110-$RUN","promoter_id":"pr-1","redirect_type":1}
JSON
)
CREATED=$(curl -s -X POST "$ADMIN_DIRECT/api/v1/links" -H 'Content-Type: application/json' \
  -H "X-Tenant-Id: $TENANT" -H "Idempotency-Key: m110-$RUN" -d "$BODY")
CODE=$(python3 -c "import json,sys;print(json.loads(sys.argv[1])['data']['short_code'])" "$CREATED") \
  || { echo "FATAL create: $CREATED"; exit 1; }
note "fixture code=$CODE → http://{xy1,xy2,xy3}.test:$PORT/s/$CODE"

# ================= A. 三域同一 code → 同一 302 =================
declare -a L ST
for i in "${!DOMAINS[@]}"; do
  d=${DOMAINS[$i]}
  r=$(u "$d" "/s/$CODE?utm_source=douyin")
  ST[$i]="${r%%|*}"; L[$i]=$(loc_comparable "${r#*|}")
done
for i in "${!DOMAINS[@]}"; do check "A$((i+1)) ${DOMAINS[$i]} 返回 302" "302" "${ST[$i]}"; done
check "A4 三域 Location 一致（code 与域名无绑定）" "${L[0]}" "${L[1]}"
check "A5 与第三域也一致" "${L[0]}" "${L[2]}"
note "共同 Location: ${L[0]}"
check "A6 归因参数拼在 fragment 之前" "1" \
  "$(python3 -c "import sys;l=sys.argv[1];print(int(l.endswith('#p') and 'channel_id=' in l.split('#')[0]))" "${L[0]}")"
check "A7 裸 /{code} 形态同样 302" "302" "$(u xy2.test "/$CODE" | cut -d'|' -f1)"

# ================= B. upstream 轮询两实例 =================
seq 60 | xargs -P 8 -I{} curl -s -o /dev/null --resolve "xy1.test:$PORT:$IP" "http://xy1.test/s/$CODE"
sleep 1
DIST=$(docker logs xsl-nginx-1 --since 20s 2>&1 | grep -oE '172\.18\.0\.[0-9]+:8020' | sort | uniq -c)
NPEERS=$(echo "$DIST" | grep -c .)
MIN_HIT=$(echo "$DIST" | awk 'BEGIN{m=99999} {if ($1+0 < m) m=$1+0} END{print m}')
check "B1 上游命中两个 jump 实例（非单点）" "2" "$NPEERS"
note "分布（每实例命中次数）：$(echo "$DIST" | awk '{printf "%s=%s ", $2, $1}')"
check "B2 轮询近似均分（较少一侧 ≥20/60）" "yes" "$( [ "${MIN_HIT:-0}" -ge 20 ] && echo yes || echo no )"

# ================= C. 滚动重启零中断 =================
# 对其中一个实例做 docker restart（等价一次发布：停容器 → JVM 冷启动约 25s），期间 8 并发持续打流。
# 期望：nginx 被动健康检查（max_fails=3/fail_timeout=5s）+ proxy_next_upstream 让客户端只看到 302，
#       且存活实例的跳转计数在窗口内继续增长（证明流量真被接住了，不是「碰巧没失败」）。
ctr() { # $1=宿主端口 $2=instance 标签
  curl -s -m 5 "http://127.0.0.1:$1/actuator/prometheus" \
    | awk -v i="$2" '$0 ~ "xsl_jump_requests_total\\{instance=\""i"\",outcome=\"found\"" {print $2}'
}
J2_BEFORE=$(ctr 8022 jump-2)
END=$(( $(date +%s) + LOAD_SECS ))
rm -f /tmp/xsl-m110-load-*.txt
for _w in 1 2 3 4 5 6 7 8; do
  ( while [ "$(date +%s)" -lt "$END" ]; do u xy1.test "/s/$CODE" | cut -d'|' -f1; done ) \
    > /tmp/xsl-m110-load-$_w.txt 2>/dev/null &
done
sleep 3
docker restart "$J1" >/dev/null 2>&1; note "docker restart $J1 @ $(date +%T)（滚动重启开始，打流继续 ${LOAD_SECS}s）"
wait
cat /tmp/xsl-m110-load-*.txt > /tmp/xsl-m110-load.txt
TOTAL=$(wc -l < /tmp/xsl-m110-load.txt | tr -d ' ')
X5XX=$(awk '$0 ~ /^5/ {n+=$1} END{print n+0}' /tmp/xsl-m110-load.txt)
X302=$(awk '$0=="302"{n++} END{print n+0}' /tmp/xsl-m110-load.txt)
note "${LOAD_SECS}s × 8 并发共 $TOTAL 次请求，状态码分布：$(sort /tmp/xsl-m110-load.txt | uniq -c | tr '\n' ' ')"
check "C1 打流量足够（>500 次）" "yes" "$( [ "$TOTAL" -gt 500 ] && echo yes || echo no )"
check "C2 零 5xx（滚动重启不中断）" "0" "$X5XX"
check "C3 全部 302" "$TOTAL" "$X302"
J2_AFTER=$(ctr 8022 jump-2)
J2_DELTA=$(python3 -c "import sys;print(int(float(sys.argv[2])-float(sys.argv[1])))" "$J2_BEFORE" "$J2_AFTER")
check "C4 存活实例窗口内持续承接跳转（>200）" "yes" "$( [ "${J2_DELTA:-0}" -gt 200 ] && echo yes || echo no )"
note "jump-2 found 计数 $J2_BEFORE → $J2_AFTER（+$J2_DELTA），窗口内 jump-1 正在重启"

# ================= D. 回轮询 + 数据面独立 =================
for _ in $(seq 60); do
  [[ "$(curl -s -m 3 -o /dev/null -w '%{http_code}' "http://127.0.0.1:8020/actuator/health")" == "200" ]] && break
  sleep 2
done
seq 40 | xargs -P 8 -I{} curl -s -o /dev/null --resolve "xy1.test:$PORT:$IP" "http://xy1.test/s/$CODE"
sleep 1
REJOIN=$(docker logs xsl-nginx-1 --since 15s 2>&1 | grep -oE '172\.18\.0\.[0-9]+:8020' | sort -u | wc -l | tr -d ' ')
check "D1 重启实例自动回到轮询" "2" "$REJOIN"

$COMPOSE stop gateway admin >/dev/null 2>&1
check "D2 管理面宕机时存量跳转仍 302" "302" "$(u xy1.test "/s/$CODE" | cut -d'|' -f1)"
note "同一时刻 /api/** 返回 $(curl -s -o /dev/null -m 5 -w '%{http_code}' --resolve "xy1.test:$PORT:$IP" "http://xy1.test/api/v1/links/$CODE")（管理面确实不可用）"
$COMPOSE start gateway admin >/dev/null 2>&1
for _ in $(seq 40); do
  [[ "$(curl -s -m 3 -o /dev/null -w '%{http_code}' "$ADMIN_DIRECT/actuator/health")" == "200" ]] \
    && [[ "$(curl -s -m 3 -o /dev/null -w '%{http_code}' "http://127.0.0.1:8110/actuator/health")" == "200" ]] && break
  sleep 2
done
# 管理面 downtime 期间 nginx 会把 gateway 上游判失败（fail_timeout=5s 冷却），且 stop/start 会让
# Docker 重新分配容器 IP —— 控制面走 resolver+变量正是为此（无需 reload 自愈）。
# 因此这里轮询而不是单发：等到 401 即证明「管理面重启后入口自动复位」。
API_CODE=""
for _ in $(seq 15); do
  API_CODE=$(curl -s -o /dev/null -m 8 -w '%{http_code}' --resolve "xy1.test:$PORT:$IP" "http://xy1.test/api/v1/links/$CODE")
  [[ "$API_CODE" == "401" ]] && break
  sleep 2
done
check "D3 管理面恢复后 /api/** 免 reload 复位（未签名 401）" "401" "$API_CODE"

# ================= E. 域名白名单 + 统计读侧 =================
check "E1 未登记 Host 被掐断（444→curl 000）" "000" \
  "$(curl -s -o /dev/null -m 5 -w '%{http_code}' "http://$IP:$PORT/s/$CODE")"
CNT=$(curl -s -m 8 --resolve "xy1.test:$PORT:$IP" "http://xy1.test/api/v1/stats/links/$CODE")
check "E2 /api/v1/stats/** 经 nginx 命中 consumer 且计数已累积" "00000 positive" \
  "$(python3 -c "import json,sys;d=json.loads(sys.argv[1]);print(d['code'],'positive' if int(d['data']['click_count'])>=100 else 'low')" "$CNT" 2>/dev/null)"
note "stats: $CNT"
check "E3 restart:always 生效（restart 策略非 no）" "always" \
  "$(docker inspect -f '{{.HostConfig.RestartPolicy.Name}}' $J1)"

# ---- 清理：一次跑会写入 ~10k 次点击事件与一条 fixture 链接，不删就是脏数据 ----
cleanup() {
  [[ -z "${CODE:-}" ]] && return 0
  for db in xsl_00 xsl_01; do
    docker exec xsl-mysql-1 mysql -N -uroot -pxsl-dev "$db" -e \
      "DELETE FROM link_route WHERE short_code='$CODE';
       DELETE FROM code_tenant_index WHERE short_code='$CODE';
       DELETE FROM short_link WHERE short_code='$CODE';
       DELETE FROM short_code_pool WHERE short_code='$CODE';
       DELETE FROM outbox WHERE entity_id='$CODE';" >/dev/null 2>&1
  done
  docker exec xsl-redis-1 redis-cli DEL "sl:r:$CODE" "sl:r:nx:$CODE" "sl:cnt:$CODE" "sl:code:$CODE" >/dev/null 2>&1
  curl -s -m 20 -u xsl_app:xsl-dev 'http://127.0.0.1:8123/' \
    --data-binary "ALTER TABLE xsl.click_event DELETE WHERE short_code='$CODE'" >/dev/null 2>&1
  rm -f /tmp/xsl-m110-load-*.txt /tmp/xsl-m110-load.txt
  note "cleanup: fixture $CODE 与压测事件已删除"
}
trap cleanup EXIT

echo "== $pass passed, $fail failed =="
exit $(( fail > 0 ? 1 : 0 ))
