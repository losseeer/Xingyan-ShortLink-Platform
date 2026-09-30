#!/usr/bin/env bash
# MySQL root 口令轮换：把「数据卷里真实的账号口令」改成 deploy/compose/.env 里的新值。
# 为什么需要它：改 .env 只改容器环境变量，而 MYSQL_ROOT_PASSWORD 仅在全新数据卷首次初始化时生效；
# 卷已存在时不改真实账号，于是 compose 的 healthcheck 与应用连接会一起变成"口令不符"。
#
#   XSL_MYSQL_PASSWORD_OLD=<旧口令> bash scripts/rotate-mysql-password.sh   （或 make mysql-rotate）
#
# 旧口令只走命令行环境，不写进任何文件；脚本只验证新口令可用，不回显任何一个。
source "$(dirname "$0")/lib-devenv.sh"

OLD="${XSL_MYSQL_PASSWORD_OLD:-}"
[[ -n "$OLD" ]] || { echo "缺 XSL_MYSQL_PASSWORD_OLD（旧口令只走环境变量，不落文件）"; exit 1; }
[[ "$OLD" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "旧口令含非法字符，无法安全转义"; exit 1; }

mysql_with() { # mysql_with <口令> <SQL> —— 口令经 MYSQL_PWD 传入容器，不出现在进程参数里
  $COMPOSE exec -T -e MYSQL_PWD="$1" mysql mysql -N -uroot -e "$2"
}

mysql_with "$OLD" "SELECT 1" >/dev/null 2>&1 \
  || { echo "旧口令登录失败：确认 XSL_MYSQL_PASSWORD_OLD 是否正确、mysql 容器是否在运行"; exit 1; }

# 逐个改 root 账号：官方镜像至少有 root@localhost 与 root@% 两条，漏一条就有一半连接路径失败。
# 必须在同一个会话里一次性改完——ALTER USER 立即生效，若分次连接，改完 localhost 后
# 拿旧口令再连就已经进不来了（本机实测踩过：第二次连接报 1045）。
SQL=""
for h in $(mysql_with "$OLD" "SELECT host FROM mysql.user WHERE user='root';"); do
  SQL="${SQL}ALTER USER 'root'@'$h' IDENTIFIED BY '$XSL_MYSQL_PASSWORD';"
  echo "   root@$h 待更新"
done
[[ -n "$SQL" ]] || { echo "mysql.user 里查不到 root 账号，异常中止"; exit 1; }
mysql_with "$OLD" "$SQL" || { echo "ALTER USER 失败"; exit 1; }   # ALTER 即时生效，无需 FLUSH

mysql_with "$XSL_MYSQL_PASSWORD" "SELECT 'rotate-ok'" >/dev/null \
  || { echo "新口令登录失败，轮换未生效"; exit 1; }
echo "OK MySQL root 口令已与 .env 对齐；接着 make up 让容器 env 与 healthcheck 用上同一个值"
