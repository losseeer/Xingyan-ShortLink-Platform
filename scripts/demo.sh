#!/usr/bin/env bash
# M1-12 一键演示（DEVELOPMENT_PLAN M1-12 验收列）：从零走完真实链路并打印每一环的证据。
#   ① nginx :80 ──/api/v1/links──> sl-gateway（HMAC 鉴权）──> sl-admin ──> MySQL 分片 + Redis 缓存
#   ② nginx :80 ──/{code}、/s/{code}──> upstream xsl_jump（jump-1/jump-2 轮询）──> 302 + 归因参数
#   ③ jump ──> Kafka shortlink-click ──> sl-consumer ──> ClickHouse ──> /api/v1/stats/links/{code}
# 前置：Docker Desktop 已启动；镜像已构建（make images）。干净复现：
#   docker compose -f deploy/compose/docker-compose.yml down -v && make demo
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
COMPOSE="docker compose -f $REPO_ROOT/deploy/compose/docker-compose.yml"
NGINX_IP="${NGINX_IP:-127.0.0.1}"
NGINX_PORT="${NGINX_PORT:-80}"
API_KEY="${API_KEY:-xy-key-alice-001}"   # M1 口径：api_key 同时是 HMAC 共享密钥（DESIGN 9.4 的 key/secret 分离留 M2）
CLICKS_PER_DOMAIN="${CLICKS_PER_DOMAIN:-3}"
STATS_WAIT_SECS="${STATS_WAIT_SECS:-60}"

entry() { printf '%s' "http://$NGINX_IP:$NGINX_PORT"; }          # 不带 Host：只命中 default server（/healthz、444）
host_url() { printf '%s' "http://$1:$NGINX_PORT"; }              # 带 Host：需 --resolve，走 xyN.test 的 server_name
resolve() { printf -- '--resolve %s:%s:%s' "$1" "$NGINX_PORT" "$NGINX_IP"; }
step() { printf '\n== %s ==\n' "$1"; }

# ================= 0. 前置自检 =================
step "0. 前置检查"
docker info >/dev/null 2>&1 || { echo "Docker 守护进程未运行：open -a Docker 后重试"; exit 1; }
MISSING=()
for m in gateway admin jump consumer; do
  docker image inspect "xsl/$m:dev" >/dev/null 2>&1 || MISSING+=("xsl/$m:dev")
done
[[ ${#MISSING[@]} -gt 0 ]] && { echo "缺镜像：${MISSING[*]}　先执行 make images（宿主 mvn 产物 + JRE，见 scripts/build-images.sh）"; exit 1; }
echo "OK docker 可用，四个应用镜像就位"

# ================= 1. 起全栈 =================
step "1. docker compose up -d --wait（十个容器全部 healthy）"
$COMPOSE up -d --wait || { echo "up --wait 超时，看 $COMPOSE ps 与 make logs"; exit 1; }
$COMPOSE ps --format 'table {{.Service}}\t{{.Status}}'

# ================= 2. 数据面就位：建库 + 租户字典 =================
# 全新数据卷由 mysql 镜像 entrypoint 自动执行 init/mysql/*.sql；已有卷则靠幂等脚本补建。
step "2. 建库（幂等）+ 租户 api_key 字典 seed"
for f in "$REPO_ROOT"/deploy/compose/init/mysql/*.sql; do
  $COMPOSE exec -T mysql mysql -uroot -pxsl-dev < "$f" >/dev/null || { echo "schema 初始化失败：$f"; exit 1; }
done
$COMPOSE exec -T redis redis-cli HSET sl:tenant:api "$API_KEY" 1001 \
  xy-key-bob-002 1002 xy-key-carol-003 1003 >/dev/null
echo "OK schema 可重放、sl:tenant:api 已就位"

# 入口健康：nginx 只在启动时解析数据面 upstream 容器名，故 up 之后它就是可用的
for _ in $(seq 15); do
  [[ "$(curl -s -m 3 -o /dev/null -w '%{http_code}' "$(entry)/healthz")" == "200" ]] && break
  sleep 2
done
check_entry=$(curl -s -m 3 -o /dev/null -w '%{http_code}' "$(entry)/healthz")
[[ "$check_entry" == "200" ]] || { echo "nginx 入口 /healthz = $check_entry（预期 200）"; exit 1; }

# ================= 3. 创建短链（走真实鉴权链路，不注入 X-Tenant-Id） =================
step "3. HMAC 签名创建短链（nginx → gateway → admin）"
RUN=$(python3 -c 'import time;print(int(time.time()*1000))')
TS=$RUN
NONCE="demo-$RUN"
BODY=$(cat <<JSON
{"origin_url":"https://mock.ticketsales.test/show/$RUN?ticket=vip&seat=A1#buy",
 "channel_id":"ch-douyin","campaign_id":"cp-m1-2026","promoter_id":"pr-alice",
 "redirect_type":1}
JSON
)
SIG=$(python3 - "$API_KEY" POST /api/v1/links "$TS" "$NONCE" "$BODY" <<'PY'
import sys, hmac, hashlib
secret, method, path, ts, nonce, body = sys.argv[1:7]
sha = hashlib.sha256(body.encode()).hexdigest()
canonical = "\n".join([method, path, ts, nonce, sha])
print(hmac.new(secret.encode(), canonical.encode(), hashlib.sha256).hexdigest())
PY
)
CREATED=$(curl -s $(resolve xy1.test) -X POST "$(host_url xy1.test)/api/v1/links" \
  -H "X-Api-Key: $API_KEY" -H "X-Timestamp: $TS" -H "X-Nonce: $NONCE" -H "X-Signature: $SIG" \
  -H "Idempotency-Key: demo-$RUN" -H 'Content-Type: application/json' -d "$BODY")
CODE=$(python3 -c "import json,sys;print(json.loads(sys.argv[1])['data']['short_code'])" "$CREATED") \
  || { echo "创建失败：$CREATED"; exit 1; }
SHORT_URL=$(python3 -c "import json,sys;print(json.loads(sys.argv[1])['data']['short_url'])" "$CREATED")
echo "OK code=$CODE"
echo "   API 返回 short_url = $SHORT_URL（生产口径 https；本机 nginx 只监听 $NGINX_PORT，演示点击走 http 等价地址）"
echo "   本机可点地址 = $(host_url xy1.test)/$CODE"

# 未登记域名对照：证明入口按 server_name 准入（DESIGN 9.5 域名治理）
echo "   未登记 Host evil.test → $(curl -s -m 3 -o /dev/null -w '%{http_code}' --resolve "evil.test:$NGINX_PORT:$NGINX_IP" "http://evil.test/$CODE")（444=nginx 直接掐断，curl 记 000）"

# ================= 4. 三域点击（跨域通用 + 双实例承载） =================
step "4. 模拟粉丝点击：xy1/xy2/xy3.test × $CLICKS_PER_DOMAIN 击"
EXPECT=$(( CLICKS_PER_DOMAIN * 3 ))
for host in xy1.test xy2.test xy3.test; do
  for _ in $(seq "$CLICKS_PER_DOMAIN"); do
    OUT=$(curl -s -o /dev/null -w '%{http_code} %{redirect_url}' $(resolve "$host") "$(host_url "$host")/$CODE?utm_source=douyin")
    echo "   $host -> $OUT"
  done
done
# /s/ 前缀与裸 code 同义（DESIGN 7.1）
echo "   /s/ 形态 $(entry) -> $(curl -s -o /dev/null -w '%{http_code} %{redirect_url}' $(resolve xy1.test) "$(host_url xy1.test)/s/$CODE")"
# 上一条是第 EXPECT+1 击，统计口径按实际发出的请求数计
EXPECT=$(( EXPECT + 1 ))

# ================= 5. 归因数据落库 → 看板出数 =================
step "5. ClickHouse 聚合 → GET /api/v1/stats/links/$CODE（nginx 按最长前缀命中 consumer）"
DEADLINE=$(( $(date +%s) + STATS_WAIT_SECS ))
STATS=""
COUNT=0
while [[ $(date +%s) -lt $DEADLINE ]]; do
  STATS=$(curl -s -m 8 $(resolve xy1.test) "$(host_url xy1.test)/api/v1/stats/links/$CODE")
  COUNT=$(python3 -c "import json,sys;print(int(json.loads(sys.argv[1])['data']['click_count']))" "$STATS" 2>/dev/null) || COUNT=0
  [[ "$COUNT" -ge "$EXPECT" ]] && break
  sleep 2
done
echo "   期望事件数 = $EXPECT，ClickHouse 可见 = ${COUNT:-0}（耗时 $(( STATS_WAIT_SECS - (DEADLINE - $(date +%s)) ))s）"
echo "   stats 响应：$STATS"
[[ "$COUNT" -ge "$EXPECT" ]] || { echo "统计未在 ${STATS_WAIT_SECS}s 内追平，查 consumer/kafka：make logs"; exit 1; }

# ================= 6. 收口指引 =================
step "6. 演示完成"
cat <<EOT
链路三环全部走通：签名创建 → 三域 302 归因 → 看板出数。
接下来可以手动复看：
  · 跳转明细（含 xy_click_id 与归因参数）：curl $(resolve xy1.test) -i $(host_url xy1.test)/$CODE
  · 直查 ClickHouse：curl -u xsl_app:xsl-dev 'http://127.0.0.1:8123/' --data-binary \\
      "SELECT count() FROM xsl.click_event FINAL WHERE short_code='$CODE'"
  · 数据面多实例承载与轮询证据：make logs（nginx route 日志里的 \$upstream_addr）
  · 滚动重启零中断：docker restart xsl-jump-1-1 期间持续点击（scripts/accept-m1-10.sh 已把这段做成断言）
复现下一次演示：docker compose -f deploy/compose/docker-compose.yml down -v && make demo
EOT
