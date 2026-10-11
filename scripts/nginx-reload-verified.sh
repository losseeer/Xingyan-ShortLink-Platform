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

# 只数"日志里出现过几个上游地址"是不够的：reload 前的旧地址还在日志窗口里，
# 一个实例真坏了也能凑出 2 个来。所以拿**当前**容器地址逐个比对。
wanted() { local ids
  ids=$($COMPOSE ps -q jump-1 jump-2 2>/dev/null)   # 服务名以 compose 为准（不是 jump）
  [[ -n "$ids" ]] || { echo 'ERROR 取不到 jump-1/jump-2 容器 id（compose 里服务名改了？这个检查本身就会失效）' >&2; exit 1; }
  docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}:8020{{end}}' $ids
}

spread() { # 打一撮请求，收集 nginx 日志里的上游地址
  for _ in $(seq 12); do
    curl -s -o /dev/null -m 3 --resolve "xy1.test:$PORT:$IP" "http://xy1.test:$PORT/healthz-probe" 2>/dev/null
  done
  sleep 1
  $COMPOSE logs --since 5s nginx 2>&1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+:8020' | sort -u
}

missing() { # 当前实例里有哪些没被轮到
  local seen=$1 addr
  for addr in $(wanted | sort -u); do
    grep -qxF "$addr" <<<"$seen" || echo "$addr"
  done
}

$COMPOSE exec -T nginx sh -c 'nginx -t && nginx -s reload' >/dev/null 2>&1 || {
  echo "nginx reload 失败（配置语法或容器未就绪），手工看：make nginx-reload"; exit 1; }

want=$(wanted | sort -u)
if [[ -z "$want" ]]; then
  echo "跳过核对：拿不到 jump 实例地址（上面已给出原因），不要把这一步当成通过"; exit 1
fi
seen=$(spread)
miss=$(missing "$seen")
if [[ -n "$miss" ]]; then
  # 最常见的原因就是 nginx 记着某个实例的旧 IP：再 reload 一次让它重解析
  $COMPOSE exec -T nginx sh -c 'nginx -s reload' >/dev/null 2>&1
  seen=$(spread); miss=$(missing "$seen")
fi

if [[ -n "$miss" ]]; then
  echo "警告：reload 后这些**当前** jump 地址一次都没被轮到：$(tr '\n' ' ' <<<"$miss")"
  echo "      多半是 nginx 仍记着旧地址或该实例被 max_fails 临时摘除——查："
  echo "        docker logs xsl-nginx-1 --since 1m | grep -i upstream"
  echo "      本次日志里的上游地址：$(tr '\n' ' ' <<<"$seen")"
else
  echo "nginx 已 reload，当前 jump 实例都在轮询里：$(tr '\n' ' ' <<<"$want")"
fi
exit 0
