# 星演宣发短链平台

演出票务场景的短链 + 渠道归因平台：主办方在抖音/微博/微信分发一条短链，用户点击后 302 直达购票页并携带归因参数，点击事件经消息链路进列式库，渠道看板据此区分有效点击与疑似刷量。

个人项目，单机定位：全部拓扑跑在一台 16G PC 的 Docker Compose 上，容量与 SLO 均以"单机可证伪"为口径。设计与计划的权威来源是 [docs/DESIGN.md](docs/DESIGN.md)（v2.2）与 [docs/DEVELOPMENT_PLAN.md](docs/DEVELOPMENT_PLAN.md)，本 README 只讲怎么跑起来。

## 架构速览

```
                 nginx :80（唯一对外入口，server_name xy1/xy2/xy3.test）
                    │
   /api/v1/stats/**─┼──────────────> sl-consumer :8040 ──> ClickHouse（count FINAL）
   /api/**──────────┼──> sl-gateway :8010（HMAC 鉴权）──> sl-admin :8030 ──> MySQL xsl_00/xsl_01
   /s/{code}、/{code}┴──> upstream xsl_jump（jump-1 + jump-2 轮询）
                                      │
                        Caffeine→Redis→link_route 三级读，302 + 归因参数
                                      └──> Kafka shortlink-click ──> sl-consumer
                                          （不可达时落本地 WAL，恢复后重放）
```

| 模块 | 职责 |
|---|---|
| `sl-common` | 归因拼接（白名单 + `xy_click_id`）、短码生成、错误码、ClickEvent DTO |
| `sl-gateway` | 管理面入口：HMAC 签名、时间戳窗口、nonce 防重放、注入 `X-Tenant-Id` |
| `sl-admin` | 链接签发与治理：短码池、准入白名单、双分片轴写入 + outbox、缓存同步 |
| `sl-jump` | 数据面：纯 302 跳转 + 点击事件投递（Kafka / WAL 兜底），不依赖管理面 |
| `sl-consumer` | 事件消费：批量幂等写 ClickHouse，`GET /api/v1/stats/links/{code}` |
| `sl-mock` | 购票页/宣发平台 mock（M3 落地） |
| `deploy/` | compose 编排、nginx 配置、MySQL/ClickHouse 初始化脚本、应用镜像 Dockerfile |
| `scripts/` | 镜像构建 + 各任务验收脚本 + 一键演示 |

依赖方向由 maven-enforcer 强制：`sl-jump` 不得依赖 `sl-admin`/`sl-gateway`（数据面独立性）。

## 前置条件

- **JDK 21（或 17+）用于构建**：macOS 上系统默认 `java` 若是 JDK 8，`make verify` / `make images` 会自动解析 keg-only 的 `openjdk@21`（`JAVA21_CASK`，见 [Makefile](Makefile) 顶部），编译 target 仍是 17。
- **Docker Desktop 已启动**：守护进程不会随系统自启，先 `open -a Docker`，`docker info` 通过再往下走。
- **凭据**：`make devenv` 生成 `deploy/compose/.env`（随机口令、0600、已被 `.gitignore` 排除）。仓库里不含任何口令——compose 用 `${VAR:?}` 强制注入，`scripts/*.sh` 经 `scripts/lib-devenv.sh` 取值，活体测试读环境变量（缺变量则自动跳过）。模板见 [deploy/compose/.env.example](deploy/compose/.env.example)。
- **宿主端口**：本机若已有服务占用标准端口，compose 的偏移已固化——MySQL 走宿主 **3307**、Redis 走 **6380**、网关走 **8110**（容器网络内仍是 `mysql:3306`/`redis:6379`/`gateway:8010`）。

## 快速开始

```bash
make devenv     # 生成 deploy/compose/.env（已存在则不覆盖）
make verify     # 全仓单元 + 活体集成测试（需 Docker 已起，MySQL 3307 / Redis 6380）
make images     # 宿主 mvn package 产物 + JRE 一层 → xsl/{gateway,admin,jump,consumer}:dev
make up         # docker compose up -d --wait，十个容器全部 healthy
make db-init    # MySQL schema 幂等重放（全新数据卷由镜像 entrypoint 自动执行）
make ch-init    # ClickHouse 应用账号 xsl_app（口令从 .env 经 SQL 写入，不落仓库）
make seed       # 灌网关鉴权所需 api_key → tenant 字典
make demo       # 一键演示，见下节（内部已含 db-init/ch-init/seed）
```

其他入口：`make ps` / `make logs` / `make down` / `make nginx-reload`（单独 `--force-recreate` 某个 jump 换 IP 后优雅重载）。

轮换口令的顺序有讲究——只改 `.env` 不会改数据卷里的真实账号：

```bash
# 1) 编辑 .env（或删掉重跑 make devenv 生成一套新值）
XSL_MYSQL_PASSWORD_OLD=<旧口令> make mysql-rotate   # 把 MySQL root 账号改成 .env 里的新值
make ch-init                                        # ClickHouse 账号同步（CREATE 后 ALTER，幂等）
make up                                             # 容器 env 与 healthcheck 用上新值
```

## 一键演示

`make demo` 从空栈走到看板出数，逐环打印证据（完整链路 = 签名创建 → 三域 302 归因 → ClickHouse 聚合）：

```bash
docker compose -f deploy/compose/docker-compose.yml down -v   # 从零复现时先清卷
make images && make demo
```

脚本（[scripts/demo.sh](scripts/demo.sh)）做的事：

1. 自检 Docker 与四个应用镜像就位，`up -d --wait` 到全 healthy；
2. 幂等重放 `deploy/compose/init/mysql/*.sql` + 灌 `sl:tenant:api`；
3. 用 HMAC 签名经 **nginx → gateway → admin** 创建一条带 `channel_id/campaign_id/promoter_id` 的链接（不注入 `X-Tenant-Id`，走真实鉴权）；
4. `curl --resolve` 三个域名各点若干击，打印每条 302 的 Location——可看到归因参数插在 `#buy` 片段之前、`utm_source` 透传、非白名单参数被丢弃；顺带打印未登记 Host 的 444 拦截；
5. 轮询 `GET /api/v1/stats/links/{code}`（nginx 按最长前缀命中 consumer）直到 ClickHouse 可见计数追平实际点击数；
6. 结尾给出复看命令（直查 CH、看 nginx 的 `$upstream_addr` 轮询证据、`docker restart xsl-jump-1-1` 做滚动重启演练）。

可调环境变量：`CLICKS_PER_DOMAIN`、`STATS_WAIT_SECS`、`NGINX_PORT`、`API_KEY`。

**浏览器里点短链**需要先让本机解析这三个域：

```bash
echo '127.0.0.1 xy1.test xy2.test xy3.test' | sudo tee -a /etc/hosts
```

两点诚实口径：本机 nginx 只监听 80，API 返回的 `short_url` 是生产口径 `https://xy1.test/{code}`，浏览器演示请用等价地址 `http://xy1.test/{code}`；跳转目标 `mock.ticketsales.test` 目前无本地服务，302 之后浏览器是 DNS 错误——mock 购票页容器排在 M2/M3（见 [docs/iterations/ITER-M1.md](docs/iterations/ITER-M1.md) §6）。

## 验收脚本

每个任务的验收是一条可重跑的命令，输出即凭证（`docs/iterations/ITER-M1.md` §3 引用它们）：

| 脚本 | 覆盖 |
|---|---|
| `scripts/accept-m1-04.sh` | HMAC 签名/时间戳窗口/nonce 防重放/未知 key/幂等重放 |
| `scripts/accept-m1-06.sh` | 创建链路：三表 + outbox + 缓存一致、准入 400、越权 403、自定义码冲突 |
| `scripts/accept-m1-07.sh` | 跳转语义 302/404/403/410 + 冷码回源二查命中缓存（metrics 佐证） |
| `scripts/accept-m1-09.sh` | 1000+ 击端到端一致、Kafka pause 期间 WAL 兜底与恢复重放（丢失率首证） |
| `scripts/accept-m1-10.sh` | 三域同一 Location、双实例承载分布、滚动重启期间 0×5xx、控制面宕机不影响跳转 |
| `scripts/demo.sh` | 端到端演示（`make demo`） |

## 当前状态

M1（跳转最小闭环 + 事件链路）已收口，tag `m1`。唯一顺延项是 **M1-11 压测存档**：本机 Docker Desktop 内存 3.9G，全栈已占 3.3G，压测前需先提到 ≥8G，否则测的是资源争抢而非链路吞吐——`bench/reports/` 目前为空，[docs/DESIGN.md](docs/DESIGN.md) §1.3 的实测列里吞吐/延迟类指标显式标注"待 M1-11"，其余实测值均来自上述脚本的存档输出。
