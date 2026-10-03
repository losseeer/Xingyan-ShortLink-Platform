#!/usr/bin/env bash
# 开发栈凭据装载（M1 收口后引入）：口令一律来自 deploy/compose/.env，仓库里不留明文。
# 用法：脚本顶部 `source "$(dirname "$0")/lib-devenv.sh"`，之后可直接用
#   $MYSQL_CLI（对 compose 里的 mysql 执行 SQL）与 $CH_CRED（curl -u 用的 xsl_app:口令）。
# .env 不入库；缺失或口令含特殊字符时直接终止并给出修复命令，避免带空口令打到别的库。
set -uo pipefail

LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$LIB_DIR/.." && pwd)"
DEV_ENV="$REPO_ROOT/deploy/compose/.env"
COMPOSE_FILE="$REPO_ROOT/deploy/compose/docker-compose.yml"
COMPOSE="docker compose -f $COMPOSE_FILE"

if [[ ! -f "$DEV_ENV" ]]; then
  echo "缺少 $DEV_ENV"
  echo "先执行：cp deploy/compose/.env.example deploy/compose/.env（或 make devenv 生成随机口令）"
  exit 1
fi
set -a; . "$DEV_ENV"; set +a

for pair in "XSL_MYSQL_PASSWORD=$XSL_MYSQL_PASSWORD" "XSL_CH_PASSWORD=$XSL_CH_PASSWORD"; do
  v=${pair#*=}
  [[ ${#v} -ge 8 ]] && [[ "$v" =~ ^[A-Za-z0-9._-]+$ ]] || {
    echo "${pair%%=} 未设置或含非法字符（允许 [A-Za-z0-9._-]，长度 ≥8）：$DEV_ENV"
    exit 1
  }
done
: "${XSL_CH_USER:=xsl_app}"

# 口令经 MYSQL_PWD 传入，不出现在进程参数里（-pxxx 会在 ps 与日志中可见）。
# 这里与 Makefile 的 MYSQL_EXEC 形态**故意不同**：make 把整行交给 shell，可以用
# `sh -c '… "$@"' _` 让容器自己读 MYSQL_ROOT_PASSWORD（这样连宿主机回显里都没有明文）；
# 而 bash 脚本里 $MYSQL_EXEC 是无引号词分裂展开的，引号不会被重新解析，塞不进那种写法。
# 脚本侧不泄露的前提是"没人 echo 这条命令、也没人开 set -x"——目前确实没有（已 grep 核对）。
MYSQL_EXEC="$COMPOSE exec -T -e MYSQL_PWD=$XSL_MYSQL_PASSWORD mysql mysql"
MYSQL_CLI="$MYSQL_EXEC -uroot"
CH_CRED="$XSL_CH_USER:$XSL_CH_PASSWORD"
export MYSQL_PWD="$XSL_MYSQL_PASSWORD"
