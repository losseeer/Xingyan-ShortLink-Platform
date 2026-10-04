#!/usr/bin/env bash
# nginx reload 的"确认真的解析到新地址"检查（M2-36）。
#
# 背景：数据面 upstream 是静态块 + keepalive，nginx 只在启动/reload 时用当时 DNS 的结果，
# 之后不再重解析。`make up` 末尾的 reload 通常够用，但当一次 up 里 jump 被反复重建
# （验收脚本切形态、连续 up），reload 的时机可能落在换 IP 之前 —— 结果是对着旧 IP 打，
# 表现为 connect timeout / Host is unreachable，nginx 把该实例临时摘掉 5s，客户端侧却仍全是 302。
# 也就是说：**用户看不见故障，只有负载分布悄悄偏了**（本机实测：60 击里旧 IP 只分到 15）。
# 这类"看起来成功"的失败最贵，所以补一把可跑的核对。
set -uo pipefail

IP="${NGINX_ENTRY_IP:-127.0.0.1}"
PORT="${NGINX_PORT:-80}"
COMPOSE="docker compose -f $(cd "$(dirname "$0")/.." && pwd)/deploy/compose/docker-compose.yml"

spread() { # 打一撮请求，数 nginx 日志里出现过的上游地址
  for _ in $(seq 12); do
    curl -s -o /dev/null -m 3 --resolve "xy1.test:$PORT:$IP" "http://xy1.test:$PORT/healthz-probe" 2>/dev/null
  done
  sleep 1
  $COMPOSE logs --since 5s nginx 2>&1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+:8020' | sort -u
}

peers() { spread | grep -c . ; }

$COMPOSE exec -T nginx sh -c 'nginx -t && nginx -s reload' >/dev/null 2>&1 || {
  echo "nginx reload 失败（配置语法或容器未就绪），手工看：make nginx-reload"; exit 1; }

n=$(peers)
if [[ "$n" -lt 2 ]]; then
  # 常见原因就是 nginx 记着某个实例的旧 IP：再 reload 一次让它重解析
  $COMPOSE exec -T nginx sh -c 'nginx -s reload' >/dev/null 2>&1
  n=$(peers)
fi

if [[ "$n" -lt 2 ]]; then
  echo "警告：reload 后 12 击只命中 $n 个 jump 实例（应为 2）。"
  echo "      多半是某个实例被 nginx 临时摘除（换 IP 或连不上）——查：docker logs xsl-nginx-1 --since 1m | grep -i upstream"
  echo "      当前上游地址：$(spread | tr '\n' ' ')"
else
  echo "nginx 已 reload，数据面两个实例都在轮询里：$(spread | tr '\n' ' ')"
fi
exit 0
