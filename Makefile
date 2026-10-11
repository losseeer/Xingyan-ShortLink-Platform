# Xingyan ShortLink Platform — 工程入口（DESIGN 3.4 / DEVELOPMENT_PLAN 工程约定）

JAVA21_CASK := /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
# 按 javac 实存选择 JDK：macOS java_home 找不到目标版本时会回落输出 Applet JRE（无 javac 却 exit 0），
# 因此候选路径必须逐个 test -x bin/javac。覆盖：make JAVA_HOME=/path/to/jdk verify
export JAVA_HOME := $(shell for c in "$(JAVA21_CASK)" "$$(/usr/libexec/java_home -v 21 2>/dev/null)" "$$(/usr/libexec/java_home -v 17 2>/dev/null)"; do if test -x "$$c/bin/javac"; then echo "$$c"; break; fi; done)

COMPOSE := docker compose -f deploy/compose/docker-compose.yml
SETTINGS := $(wildcard deploy/maven/settings-aliyun.xml)
MVN := mvn -q $(if $(SETTINGS),-s $(SETTINGS),)

# 凭据只来自 deploy/compose/.env（不入库）。这里 include 后 export，使 make 自身、
# compose 的 ${VAR:?} 插值、以及 mvn 起的活体测试（System.getenv）看到同一份值。
DEVENV := deploy/compose/.env
-include $(DEVENV)
export XSL_MYSQL_PASSWORD XSL_CH_PASSWORD GRAFANA_PASSWORD

.PHONY: print-java verify up down ps logs demo db-init ch-schema seed ch-init images nginx-reload devenv tls mysql-rotate

# 首次使用：make devenv 生成随机口令（0600），或 cp .env.example .env 后手工填
devenv:
	@if [ -f $(DEVENV) ]; then echo "$(DEVENV) 已存在，不覆盖（要改口令请手工编辑，再 make mysql-rotate + make ch-init + make up）"; \
	else umask 077; printf '# 本机开发栈凭据，不入库。字符集 [A-Za-z0-9._-]。\nXSL_MYSQL_PASSWORD=%s\nXSL_CH_PASSWORD=%s\nGRAFANA_PASSWORD=%s\n' \
		"$$(openssl rand -hex 16)" "$$(openssl rand -hex 16)" "$$(openssl rand -hex 12)" > $(DEVENV); \
		echo "已生成 $(DEVENV)（随机口令，权限 0600）"; fi

# 口令不出宿主机 shell：MySQL 容器自己就有 MYSQL_ROOT_PASSWORD（compose 注入），
# 所以让容器内的 shell 用它组 MYSQL_PWD —— make 回显的命令行里只剩变量名，
# 容器内的进程参数里也不出现明文（`-p"$PW"` 那种写法会被 ps 看到）。
# 旧写法 `-e MYSQL_PWD=$(XSL_MYSQL_PASSWORD)` 的问题：make 会回显展开后的 recipe，
# 于是 `make db-init` 直接把口令打进终端（截图/贴日志就是泄露）。
# 注意：这个 `sh -c '… "$@"' _` 形态只在 make 里可用（make 把整行交给 shell）；
# bash 脚本里的 $MYSQL_EXEC 走的是无引号词分裂，塞不进引号，见 scripts/lib-devenv.sh 的注释。
MYSQL_EXEC := $(COMPOSE) exec -T mysql sh -c 'exec env MYSQL_PWD="$$MYSQL_ROOT_PASSWORD" mysql -uroot "$$@"' _
REDIS_EXEC := $(COMPOSE) exec -T redis redis-cli

# ClickHouse 应用账号：口令经 SQL 在运行时写入，仓库里没有 users.d 明文文件
ch-init:
	@bash scripts/init-ch-user.sh

# 已有数据卷下把 MySQL root 真实账号改成 .env 里的新值（改 .env 不会自动改库）
mysql-rotate:
	@bash scripts/rotate-mysql-password.sh

db-init:
	@for f in deploy/compose/init/mysql/*.sql; do echo "--> $$f"; $(MYSQL_EXEC) < $$f; done
	$(MYSQL_EXEC) -e "SELECT tenant_id, name FROM xsl_base.tenant ORDER BY tenant_id;"

# ClickHouse 表结构幂等重放：镜像的 entrypoint 只在**新建卷**时跑 init 脚本，
# 已有卷加不了列 —— 没有这一步，老卷上 consumer 写 click_id 会撞 "unknown field"（M3-01）。
ch-schema:
	@for f in deploy/compose/init/clickhouse/*.sql; do echo "--> $$f"; \
	  $(COMPOSE) exec -T clickhouse clickhouse-client --multiquery < $$f || exit 1; done
	@$(COMPOSE) exec -T clickhouse clickhouse-client --query \
	  "SELECT name FROM system.columns WHERE database='xsl' AND table='click_event' ORDER BY position" | tr '\n' ' '; echo

# 网关 HMAC 鉴权所需 api_key→tenant_id 字典（DESIGN 9.4；M3 改由 admin 变更后同步）
seed:
	$(REDIS_EXEC) HSET sl:tenant:api xy-key-alice-001 1001 xy-key-bob-002 1002 xy-key-carol-003 1003

# clean 不是保守，是纪律：Maven 增量按源文件时间戳决定编不编，改了 sl-common 的公共 record 之后，
# 没被碰过的模块（sl-jump 的测试源码）整模块跳过编译，签名不匹配要拖到下一次 clean 构建才暴露（ITER-M2 §4）。
verify:
	$(MVN) clean verify

# 应用镜像 = 宿主 mvn 产物 + JRE（M1-10）。compose 里四个服务只引用 tag，不做镜像构建，
# 所以 `up` 之前要先 `make images`（首次或改代码后）。
images:
	@bash scripts/build-images.sh --mvn

# tls 是 up 的前置：nginx 的 443 要挂 deploy/compose/tls/（M2-34 自签证书）。
# 目录不存在时 Docker 会替我们 mkdir 一个空目录，nginx 随即以 "cannot load certificate" 起不来，
# 报错还落在容器日志里而不是 Makefile 输出里——所以让它先跑脚本，而不是让人去猜。
up: tls
	$(COMPOSE) up -d --wait
	# 重建 jump 容器会让容器 IP 变化，而数据面 upstream 是静态块（keepalive 需要稳定 DNS 名，
	# nginx 只在启动/reload 时解析一次）——不 reload 就会对着旧 IP 打，全站 502。
	@bash scripts/nginx-reload-verified.sh

down:
	$(COMPOSE) down

ps:
	$(COMPOSE) ps

logs:
	$(COMPOSE) logs -f --tail=100

# 数据面 nginx 用静态 upstream（要 LB + 同请求改投），只在启动时解析容器名；
# 单独 recreate 某个 jump 若换了 IP，就要优雅重载一次（不断流）。控制面走 resolver 自愈，不需要。
# 本地自签证书（mock 购票页要在 443 上应答 https://mock.ticketsales.test，见 DESIGN 6.2）。
# 幂等：已存在就跳过。产物 deploy/compose/tls/ 不入库。
tls:
	@bash scripts/gen-mock-tls.sh

nginx-reload:
	$(COMPOSE) exec -T nginx sh -c 'nginx -t && nginx -s reload'

# 一键演示（M1-12）：up → 建库/seed → 签名创建 → 三域点击 → stats 出数，逐环打印证据。
# 从零复现口径：先 `docker compose -f deploy/compose/docker-compose.yml down -v` 再 make demo。
demo:
	@bash scripts/demo.sh

print-java:
	@echo "JAVA_HOME=$(JAVA_HOME)"
