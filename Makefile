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

.PHONY: print-java verify up down ps logs demo db-init seed ch-init images nginx-reload devenv mysql-rotate

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

# 网关 HMAC 鉴权所需 api_key→tenant_id 字典（DESIGN 9.4；M3 改由 admin 变更后同步）
seed:
	$(REDIS_EXEC) HSET sl:tenant:api xy-key-alice-001 1001 xy-key-bob-002 1002 xy-key-carol-003 1003

verify:
	$(MVN) verify

# 应用镜像 = 宿主 mvn 产物 + JRE（M1-10）。compose 里四个服务只引用 tag，不做镜像构建，
# 所以 `up` 之前要先 `make images`（首次或改代码后）。
images:
	@bash scripts/build-images.sh --mvn

up:
	$(COMPOSE) up -d --wait
	# 重建 jump 容器会让容器 IP 变化，而数据面 upstream 是静态块（keepalive 需要稳定 DNS 名，
	# nginx 只在启动/reload 时解析一次）——不 reload 就会对着旧 IP 打，全站 502。
	@$(COMPOSE) exec -T nginx nginx -s reload 2>/dev/null || echo "nginx reload 跳过（容器未就绪）"

down:
	$(COMPOSE) down

ps:
	$(COMPOSE) ps

logs:
	$(COMPOSE) logs -f --tail=100

# 数据面 nginx 用静态 upstream（要 LB + 同请求改投），只在启动时解析容器名；
# 单独 recreate 某个 jump 若换了 IP，就要优雅重载一次（不断流）。控制面走 resolver 自愈，不需要。
nginx-reload:
	$(COMPOSE) exec -T nginx sh -c 'nginx -t && nginx -s reload'

# 一键演示（M1-12）：up → 建库/seed → 签名创建 → 三域点击 → stats 出数，逐环打印证据。
# 从零复现口径：先 `docker compose -f deploy/compose/docker-compose.yml down -v` 再 make demo。
demo:
	@bash scripts/demo.sh

print-java:
	@echo "JAVA_HOME=$(JAVA_HOME)"
