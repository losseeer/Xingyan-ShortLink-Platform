# 星演宣发短链平台

演出票务场景的短链 + 渠道归因平台：主办方在抖音/微博/微信分发一条短链，用户点击后 302 直达购票页并携带归因参数，点击事件经消息链路进列式库，渠道看板据此区分有效点击与疑似刷量。

个人项目，单机定位：全部拓扑跑在一台 16G PC 的 Docker Compose 上，容量与 SLO 均以"单机可证伪"为口径。设计与计划的权威来源是 [docs/DESIGN.md](docs/DESIGN.md)（v2.2.2）与 [docs/DEVELOPMENT_PLAN.md](docs/DEVELOPMENT_PLAN.md)，本 README 只讲怎么跑起来。

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

   （刻意不经 nginx）sl-consumer ──每 5 分钟按分钟桶回传──> sl-mock :8050「宣发平台」
                                        batch_id + 校验和 + 幂等，落独立库 xsl_mock
```

| 模块 | 职责 |
|---|---|
| `sl-common` | 归因拼接（白名单 + `xy_click_id`）、短码生成、错误码、ClickEvent DTO |
| `sl-gateway` | 管理面入口：HMAC 签名、时间戳窗口、nonce 防重放、注入 `X-Tenant-Id` |
| `sl-admin` | 链接签发与治理：短码池、准入白名单、双分片轴写入 + outbox 补偿、缓存回写与失效广播 |
| `sl-jump` | 数据面：纯 302 跳转 + 点击事件投递（Kafka / WAL 兜底），不依赖管理面 |
| `sl-consumer` | 事件消费：批量幂等写 ClickHouse，`GET /api/v1/stats/links/{code}`，每 5 分钟按分钟桶归因回传（M3-03） |
| `sl-mock` | mock 宣发平台（M3-03 起进 reactor）：接收归因回传（验签 + 校验和 + `batch_id` 幂等 + 按桶覆盖），落独立库 `xsl_mock`；假订单接口随 M3-04 |
| `sl-console-web` | 主办方看板（M3-06 起，仓库里第一个前端）：Vue 3 + Vite + ECharts 三页只读视图，不在 Maven reactor 里、`dist/` 不入库；由 nginx 在 **8081** 静态托管并同源反代只读统计 |
| `deploy/` | `compose/`（编排、nginx、MySQL/ClickHouse 初始化、`overrides/` 压测叠加、`.env` 不入库）、`docker/`（应用镜像 Dockerfile）、`maven/`（构建 settings） |
| `scripts/` | 一任务一验收脚本 `accept-m1-NN.sh` + 演练脚本 `drill-8-3.sh`/`drill-domain-switch.sh` + 备份与读回验证 `backup.sh`/`restore-verify.sh` + 镜像构建/演示/凭据相关脚本，口令统一经 `lib-devenv.sh` 从 `.env` 取 |
| `bench/` | 压测脚本、wrk lua、`reports/` 存档（DESIGN 1.3 的数字只引用这里） |
| `docs/` | `DESIGN.md`（设计）、`DEVELOPMENT_PLAN.md`（计划与工程约定）、`iterations/ITER-<M>.md`（每期流水）+ 模板 |

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
make web        # 看板前端：npm install + vite build → sl-console-web/dist（`make up` 会检查它在不在）
make up         # docker compose up -d --wait，十个容器全部 healthy（前置 make tls，见下）
make db-init    # MySQL schema 幂等重放（全新数据卷由镜像 entrypoint 自动执行）
make ch-init    # ClickHouse 应用账号 xsl_app（口令从 .env 经 SQL 写入，不落仓库）
make seed       # 灌网关鉴权所需 api_key → tenant 字典
make demo       # 一键演示，见下节（内部已含 db-init/ch-init/seed）
make backup     # 备份：mysqldump 三库 + ClickHouse BACKUP 两张表 → deploy/backup/<ts>/（约 1.7G，不入库）
make restore-verify  # 读回验证：dump 灌一次性容器逐表比行数、CH 备份 RESTORE 成影子表比 count 后 DROP
```

看板起来后开 **http://xy1.test:8081/**（需要 hosts 里那条 `127.0.0.1  xy1.test`；直接开 http://127.0.0.1:8081/ 也行）。三页都是只读的：短码入口仍在 80，看板只是把 `/api/v1/stats/**` 在同一台 nginx 上转给 consumer。

`make up` 会先跑 `make tls`：为 mock 购票页生成**本地自签证书**到 `deploy/compose/tls/`（幂等、已 gitignore）。
这一步不是装饰——短链 302 的终点是 `https://mock.ticketsales.test/…`，nginx 不在 443 上应答就打不开；
而目录不存在时 Docker 会替我们建一个空目录，nginx 只在容器日志里报 `cannot load certificate`，
所以把它做成前置而不是让人去猜报错。

其他入口：`make ps` / `make logs` / `make down` / `make nginx-reload`（单独 `--force-recreate` 某个 jump 换 IP 后优雅重载）、`make tls`（换发自签证书：删掉 `deploy/compose/tls/` 再跑）。

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
5. **跟着 302 打到终点**（4b 段）：取上一步的 Location，用 `--resolve mock.ticketsales.test:443` 打进去，断言 200 并确认落地的是 mock 购票页——归因的最后一公里由此变成看得见的一环；
6. 轮询 `GET /api/v1/stats/links/{code}`（nginx 按最长前缀命中 consumer）直到 ClickHouse 可见计数追平实际点击数；
7. 结尾给出复看命令（直查 CH、看 nginx 的 `$upstream_addr` 轮询证据、`docker restart xsl-jump-1-1` 做滚动重启演练）。

可调环境变量：`CLICKS_PER_DOMAIN`、`STATS_WAIT_SECS`、`NGINX_PORT`、`API_KEY`。

**浏览器里点短链**需要先让本机解析这几个域（`.test` 是保留 TLD，公网不解析）：

```bash
echo '127.0.0.1 xy1.test xy2.test xy3.test mock.ticketsales.test' | sudo tee -a /etc/hosts
```

三点诚实口径：本机 nginx 只监听 80，API 返回的 `short_url` 是生产口径 `https://xy1.test/{code}`，浏览器演示请用等价地址 `http://xy1.test/{code}`；跳转终点 `https://mock.ticketsales.test/…` 由 nginx 在 443 上用**本地自签证书**应答（`make tls` 生成），浏览器会告警，手动继续即可——这里不宣称"证书受信"；购票页只回显参数与假选座，不下真订单（`xy_click_id` 与订单的关联回传属 M3，见 [docs/iterations/ITER-M2.md](docs/iterations/ITER-M2.md) §6）。

## 验收脚本

每个任务的验收是一条可重跑的命令，输出即凭证（`docs/iterations/ITER-M1.md` §3 引用它们）：

| 脚本 | 覆盖 |
|---|---|
| `scripts/accept-m1-04.sh` | HMAC 签名/时间戳窗口/nonce 防重放/未知 key/幂等重放 |
| `scripts/accept-m1-06.sh` | 创建链路：三表 + outbox + 缓存一致、准入 400、越权 403、自定义码冲突；M2-31 起含规则双轨契约（`rules[]` 绑定、编译序、PATCH 不清规则、空数组清空） |
| `scripts/accept-m1-07.sh` | 跳转语义 302/404/403/410 + 冷码回源二查命中缓存（metrics 佐证）。默认形态已是 `dynamic`，无规则链接的 Location 与 M1 逐字符同形就是靠它在守住 |
| `scripts/accept-m1-09.sh` | 1000+ 击端到端一致、Kafka pause 期间 WAL 兜底与恢复重放（丢失率首证）；M3-01 起还断"跳转 URL 上的 `xy_click_id` == ClickHouse 里那行的 `click_id`" |
| `scripts/accept-m1-10.sh` | 三域同一 Location、双实例承载分布、滚动重启期间 0×5xx、控制面宕机不影响跳转 |
| `scripts/accept-m2-13.sh` | 频控：阈值内 302／越界 429、伪造 XFF 绕不过、被拦不烧配额、被拦击仍入库（M3-01 起并断其 `click_id` 为空串、放行点击必带值）、丢键后按快照收口、Redis 停机降级 |
| `scripts/accept-m2-32.sh` | UA 特征表落库：13 条真实 UA 逐条对上 `click_event.(device_type, os)`、空值 0、聚合层 `wx_pv/douyin_pv/browser_pv` 与同一套词汇表贯通 |
| `scripts/accept-m2-35.sh` | **M2 出口标准**：15 条 UA 重放断 target_type／响应形态／中间页内容与 `no-store`／跨维度 priority 排序／无匹配落 origin／CH 取数／跟着打到 mock 购票页／`XSL_JUMP_MODE=direct302` 回退与复位 |
| `scripts/accept-m3-02.sh` | outbox 补偿：双实例亚秒收敛（前置断言「L1 确实是热的」）、缺行成对补齐后 404→302、落后快照回正、**旧事件重放绝不倒退**（表与缓存都验）、坏事件到上限置 2 并进积压指标、`XSL_OUTBOX_SYNC` 开关透传、整批重放的耗时与广播条数 |
| `scripts/accept-m3-03.sh` | 归因回传：端到端闭环（平台侧四个量 == CH 明细现算值）、`batch_id` 幂等与 `times_received`、校验和篡改 400 且台账不留行、错签/越窗/越权/无签名头各自 401·403、**迟到事件覆盖旧桶值**（不是被幂等丢掉）、10 分钟窗口扫明细的 read_rows/耗时、`XSL_PUSH_ENABLED` 开关与定时真的会跑 |
| `scripts/drill-8-3.sh` | **M3 出口标准的第三条**：DESIGN 8.3 那张降级预案表逐行真跑一遍（**36/36**）。每行都是"停 → 断言**行为** → 起 → 记恢复秒数"，不是断言"容器停了"：J 进程崩溃 19s 自愈 / 474 击零 5xx（并把 `docker kill` 拆成另一件事，因为 restart 策略不覆盖人为停止）；C 五条 stats 全 **503** 且是 `SL-5000` 错误信封而不是空数据、恢复后 517/517 一行不少；K 348 击 → WAL 348 行 → 明细 348 行、lag 归零；R 冷码靠 `link_route` 直读仍 302、频控退化 `mode=local`、**每击 44ms → 560ms**，且先探测到两实例都收到广播（12s）才断跨实例封禁；M 热码 269/269、`POST /links` 失败得可见（500 + 不回 `short_code`）、stats 照读、冷码 504 恢复后 302。原始计数与容器内存实测追加进 `bench/reports/2026-10-15-1501-m3-drill-8-3.md` |
| `scripts/backup.sh` + `scripts/restore-verify.sh` | DESIGN 8.3 第六行"数据丢失"的两半（`make backup` / `make restore-verify`，**本轮从零建起来——此前那行的"验证方式"写着一个根本不存在的目标**）：备份 = mysqldump 三库 + ClickHouse `BACKUP` 两张表（1.7 G）落 `deploy/backup/<ts>/`（不入库），并在备份前后各取一次全库行数、不相等就停住；读回 = dump 灌进**一次性容器**（tmpfs、不发布端口、`--rm`）**16 张表逐表行数与清单精确相等**，CH 备份 `RESTORE … AS xsl.*_vfy` 建**影子表**比完即 DROP——明细比行数区间、聚合比六项指标（`SummingMergeTree` 会折叠同键行，行数天生不是稳定量），两边都不碰现有数据 |
| `scripts/drill-domain-switch.sh` | 多域名切换演练（9.5）：三域等价（去掉本次 `xy_click_id` 后逐字符相同、每击一个新 id）、`weight` 全等时稳定选中字典序最小的域、封禁一条域后 **31s** 新码换域、**存量码在旧域仍能跳转**、`switch_time` 落库、选域指标读得到、复位后切回 |
| `scripts/accept-m3-05.sh` | **M3 出口标准的第二条**：铺一批形状已知的真点击（含一条极低配额的链接专门造出"被频控拦下"那一档），然后**五条只读接口的每个字段与脚本独立实现的 ClickHouse 直查逐个相等（0 容差，本轮共 69 个字段）**；边界段把护栏当被测对象（>8 天窗口 / from>to / 粒度不认识 / 租户非数字 / 短码带引号 / limit 越界都必须 400，空窗口必须是 0 而不是 500，别的租户看不到本轮的码）；交付面段钉静态件与缓存头，并逐条检查**页面绑定的字段路径真的在响应里**（借错接口那类失败只有这层网兜得住） |
| `scripts/recon-attribution.sh` | **日常核对工具**（不是验收脚本，T+1 也用这个）：给一个 `campaign_id` 就报 ① 传输完整性差异、② 订单↔点击的可 join 命中率、聚合表偏差（只打印不判），按阈值给退出码、可直接接告警。窗口可给可不给（不给就按明细自推区间），阈值 `--max-diff`/`--min-join` 可调，`--keep-bad` 只报数不变红 |
| `scripts/accept-m3-04.sh` | **M3 出口标准**：抓 302 上的真实 `xy_click_id` → 落明细 → 分钟关闭 → 回传 → 用它下单；① 差异 **0.00%** / ② 命中率 **100.00%**；订单幂等（重发不重复计入、`times_submitted` 可查）与坏输入 400 不留半行；**两组负向样本**——注入引用不存在 `click_id` 的订单必须报出恰好 3 条缺失，"只点击、不回传"那批必须报 100.00% 而不是 `n/a`；阈值旋钮调到不可能满足时必须变红（证明那个 PASS 不是写死的） |
| `scripts/demo.sh` | 端到端演示（`make demo`） |

## 当前状态

M1（跳转最小闭环 + 事件链路）**十三项任务全部完成并有实测存档**，收口 tag `m1`（仅本地）。M1-11 压测在收口次日补跑（前提：Docker VM 内存提到 8G、`brew install wrk`），存档在 `bench/reports/`；[docs/DESIGN.md](docs/DESIGN.md) §1.3 与 §8.1-A 的"实测"列只引用这些文件，**未达标项（签发 TPS、短码生成延迟）也照实写着**并附瓶颈分析与复测路径。

M2（动态路由与防刷）进行中。**M2-00 资源预算重排 + 跳转 P99 治理已于 2026-10-03 收口**，其结论主要是**测量口径**层面的：M1-11 在 200 连接（≈10× 目标负载）判"P99 未达标"，把 SLO 判定和容量上限混成了同一件事——在目标负载上（5,002 QPS）实测 P50 0.646ms／P99 21.08ms，**达标**；同时把尾延迟归因到事件链（负载中 ClickHouse ≈1.9 核，停 consumer 后同一入口 24,525 QPS），GC 假设已由对照实验证伪。

**防刷主线（M2-10…M2-15）同日收口**：Redis 窗口频控（`sl:rl:{code}:{iphash}:{slot}`，单脚本 INCR+首次 EXPIRE）命中返回 429 且**不消耗配额**；客户端 IP 只信 nginx 覆盖写入的 `X-Real-IP`（原来取 `X-Forwarded-For` 首元素，客户端可伪造 → 频控可绕、`ip_hash` 不可信）；被拦的击仍进事件流并记 `risk_score=25`；`access_limit` 补齐对账（Redis 权威值 → `link_route.access_used` 快照，管理面每 60s 回写），丢键后按快照重建而不是回到满额；Redis 停机时频控退化为进程内计数、跳转不跌零。端到端验收 `scripts/accept-m2-13.sh` 17/17，五套旧验收全绿，DESIGN 8.1-A 的「频控 Lua ≥3 万次/s」由未测转为实测（单连接 2.78 万/s、10 连接 16.7 万/s）。详见 [docs/iterations/ITER-M2.md](docs/iterations/ITER-M2.md)。

**M2-01 事件链批量化已于 2026-10-03 收口**（`async_insert(wait=1)` + 5000 行/批 + producer 攒批 + topic 1→4 分区：chain-live 平顶 12,185 → **18,193 QPS（+49%）**、追赶 29,578 → 41,223 events/s、事件链 CPU 2.17 → 0.92 核、丢失率全程 0.0000%；**≥20k 的目标未达成，差 9%**，瓶颈已移到数据面自身）。

**动态路由主线（M2-30…M2-36）已于 2026-10-04 收口**，M2 的出口标准"UA 重放脚本全绿"由 `scripts/accept-m2-35.sh` **87/87** 达成：

- **规则双轨**（M2-31）：`route_rule` 是管理面行式真源，保存时编译进 `link_route.route_json.rules` 成为数据面唯一读取源；`PUT /links/{code}/rules` 整体替换，非法规则 400 且**不占短码**，PATCH 元数据不会静默清空规则。
- **UA/容器特征引擎**（M2-32）：特征表只在 `sl-jump/application.yml` 里有一份（代码不留默认表，配错启动即失败），容器与设备是两次独立判定；`click_event.device_type/os` 从恒为空串变成真取值，且**规则条件值 = 事件取值 = CH 聚合字面量**同一套词汇表（这条贯通性由 `accept-m2-32.sh` 断言）。
- **一级决策 + 中间页 + 真开关**（M2-33）：按 priority 取首条命中，四类 target 各自决定 302 还是 200 中间页（`Cache-Control: no-store`——页里带着本次点击的 `xy_click_id`，缓存过就把 A 的 click_id 发给 B）；`xsl.jump.mode` 从注释变成条件装配，默认 `dynamic`，`XSL_JUMP_MODE=direct302 make up` 一键退回纯 302。
- **mock 购票页**（M2-34）：302 的终点在本地真的能打开（443 + 自签证书 + hosts 一条），query 与 `#fragment` 由页内 JS 回显。

M2 收口时留下的待办：行为评分规则表（`risk_score` 已可写、`is_bot` 仍恒 0）、签发链路换工具复测、M3 的归因回传与看板三页——其中**归因回传（M3-03）与端到端归因对账（M3-04）已于 2026-10-11 收口**（见下面两段），剩下评分规则表与看板三页。

**M3 开工（2026-10-04）**：**M3-01 对账键已收口**——`click_event` 加了一列 `click_id`，值就是跳转 URL 上的 `xy_click_id`（订单回传带回的也是它），于是"这笔订单来自哪一次点击"在 ClickHouse 里能 join 了。老数据卷靠 `make ch-schema` 幂等补列；**历史行是空串且不回填**（那个值从来没进过统计链路，填进去就是造数据）；被频控拦下的点击也是空串（压根没生成跳转目标），两者靠 `risk_score=25` 区分。实测按 `click_id` 点查 0.3–0.5 秒、一次查 1000 个 id 0.8 秒，据此决定**不加跳数索引**。M3 后续顺序与风险见 [docs/iterations/ITER-M3.md](docs/iterations/ITER-M3.md)。

**M3-02 outbox 同步器与缓存失效广播已于 2026-10-11 收口**（`scripts/accept-m3-02.sh` **62/62**）：`outbox` 从「只有写方」补齐成一条能测的补偿链路——sl-admin 的 `@Scheduled`（5s 一轮、≤500 行、`0→1`，坏事件 `retry_count++` 到上限置 `2`）+ `POST /internal/outbox/replay`（带 `?short_code=` 可定点修）。核心是**两条 version 条件写**：`UPDATE link_route … WHERE version<?`（缺行时把路由与归属索引**成对**补回）与缓存侧的 Lua `version 比较`，于是「重放整张 outbox」是安全的——实测灌 1000 条与快照同版本的事件：全部判为已被取代、`applied=0`、一行路由都没动、一条广播都没发。配置变更走 Redis `xsl:route:evict` 广播清各实例 Caffeine：封禁一条链接后两个 jump 实例都在**第一个采样点（≤100ms，采样步长 100ms）**改口 403，而旧口径只能等 30s TTL；广播是尽力而为的加速，丢消息由 TTL 自愈，正确性仍由条件写守住。

**M3-03 归因回传 + mock 宣发平台已于 2026-10-11 收口**（`scripts/accept-m3-03.sh` **37/37**）：点击侧的分钟桶现在会主动推给"外部平台"——`sl-mock` 从只有 README 的占位目录变成 reactor 里的第 6 个模块（端口 8050、独立库 `xsl_mock`、**不挂 nginx 对外路由**，因为它扮演的就是外部系统）。发送端在 sl-consumer 的 `@Scheduled`（5 分钟一轮、回看 3 个已关闭分钟），事实源取 **`click_event` 明细 `FINAL`** 现算 `pv/uv/valid_pv/bot_pv`——实测 10 分钟窗口只读 6,755 行 / 263 KB / 5ms（表内 4,836 万行），所以**不给聚合表加 `valid_pv` 列**，那个"数字不可接受就换"的出口当场关闭。三件语义都有断言兜着：`batch_id` 由「窗口 + 内容 hash」算出（所以迟到变全的那一版是**覆盖**而不是被幂等丢掉）、接收端自己重算校验和与 id（篡改 `pv` → 400 且台账一颗不涨）、同 `batch_id` 重投只加 `times_received`（数值不重复计入）。`bot_pv` 恒 0、`valid_pv` 现在等于放行数——这是评分层未做的**能力边界**，口径写死在 DESIGN 6.4，式子已就位等数据变准。

**M3-04 端到端归因对账已于 2026-10-11 收口**（`scripts/accept-m3-04.sh` **78/78**），M3 的出口标准"端到端归因对账误差 < 2%"第一次有了可点达的实测：**① 传输完整性差异 0.00%、② 归因可 join 命中率 100.00%**。做法是脚本扮演购票用户——从 302 的 `Location` 抓真实 `xy_click_id`（形态 `c`+雪花），确认它已落进 ClickHouse 明细、等分钟关闭、触发一轮回传，再拿同一个 `click_id` 去 mock 的假订单接口下单；订单侧 `order_id` 是幂等键（重发 3 笔只加 `times_submitted`、行数不动，否则"用户双击"会把 ② 的分母弄虚高，症状长得像"转化率莫名偏低"）。对账本身拆成两份：`scripts/recon-attribution.sh` 是可反复跑、按阈值给退出码的**核对工具**（T+1 手工核对也是它），`accept-m3-04.sh` 负责铺数据与断言。**两条判定式各配了负向样本**：注入 3 条引用不存在 `click_id` 的订单，工具报出的缺失恰好是 3 且退出码变红；另起一批"只点击、不回传"的活动，差异必须报 **100.00% 而不是 `n/a`**——"没数"与"丢数"得是两个能区分的值，否则最坏的失败形态会伪装成无害的数字。阈值旋钮也被证明是活的（`--max-diff 0`、`--min-join 101` 各自只红自己那一条）。诚实的边界：0.00% 是 mock 闭环的性质（两端同机、时钟同源），阈值是给生产留的余量；`<2%` 达成不等于链路无损。

**M3-05…M3-08 主办方看板已于 2026-10-11 收口**（`scripts/accept-m3-05.sh` **55/55**），M3 出口标准的第二条"看板三页面数据与 ClickHouse 直查一致"由此变成可重跑的：脚本先铺一批**形状已知**的点击（两条不同渠道 + 一条极低配额链接专门造出"被频控拦下"那一档，实测 `blocked_pv=3`），再让五条只读接口的**每个字段**与脚本自己独立实现的一份直查算式逐个相等——**0 容差，本轮共 69 个字段**。取数代价那条"由数字决定"也落了地：24h/7d 档每条 **7–35ms**，30d 档要读 4,885 万行、单条 **3.0–4.5s** ⇒ 不给聚合表加列、不动 MV，改为把**窗口上限压到 8 天**（超出直接 400），长窗口交给 `recon-attribution.sh`。前端是仓库第一个：`sl-console-web/`（Vue 3 + Vite + ECharts，按决定去掉了 Element Plus，也不引 TS/Pinia），nginx 在 **8081** 静态托管 + 同源反代，浏览器里不放任何密钥——那把 `api_key` 本身就是 HMAC secret；代价如实写在 DESIGN 7.2 与 9.1 的风险表里（只读聚合数字对能访问入口的人开放）。做页面时才发现接口是**五条**不是四条（双指标要的是租户时间序列，与渠道分布不是同一条 `GROUP BY` 轴），加上路由键形态写错——这两处 `make verify` 与 curl 全绿、只有真开浏览器才看得见，于是"页面绑定的字段必须真的在响应里"这层形状检查被补进了验收。

**M3-10 多域名切换已于 2026-10-11 演练通过**（`scripts/drill-domain-switch.sh` **13/13**）：DESIGN 9.5 那句"domain_pool 表与切换机制完整实现"此前只有表是真的——生效域其实是`XSL_LINK_BASE_DOMAIN` 一个环境变量，没有一行代码读那张表。现在 admin 创建链接时按`status=0 → weight DESC → domain ASC` 从表里选（30s 缓存；平手必须排死，否则"切到了哪个域"就成了随机事件、演练没法断言），把某个域置 `status=2` 就是一次切域：**31 秒后**新建的链接换到下一个域（这个界就是缓存 TTL，不是链路慢；要立刻定住域走 `XSL_DOMAIN_POOL=false`），而**存量码在旧域仍然照常跳转**——封禁的语义是"不再用它发新码"，不是把发出去的宣传物料全部作废。字典库读不到就退回兜底域名并计数 `xsl_domain_pool_total{result=fallback}`，连接超时压到 1.5s：活体测试跑出 30.3s 才发现 Hikari 默认要等 30 秒，那会把"创建链接"整个拖住。

**M3-09 降级演练已于 2026-10-15 把 8.3 那张表逐行跑完**（`scripts/drill-8-3.sh` **36/36**，存档 `bench/reports/2026-10-15-1501-m3-drill-8-3.md`），DESIGN 第十章那行 M3 的三条出口标准到此全部达成。这一格的价值不在"跑了一遍"，而在**三条只有把容器停下来才浮出来的事实**，它们改写了文档原来的写法：**① `restart: always` 只管"进程自己死了"**——`docker kill` 之后 20s 仍停在 `exited`（Docker 把人为停止排除在重启策略之外），而容器内 PID 1 被 `kill -9` 之后 19s 自动回 healthy、期间 474 击零 5xx；**② 管理面没有熔断器**——MySQL 挂时创建链接确实不会静默成功（500 + `SL-5000`、响应里没有 `short_code`），但快慢不稳（本轮 0s，上一次排练同一处耗满 30s 才由客户端放弃），所以 8.3 那句"管理面熔断"已改成"快速失败"；**③ 回源那条预案只有冷码才走到**——热码被 Caffeine 全接住，第一版把断言挂在热码上量到 `route_lookup{level="db"}` 0→0，那不是没回源而是没必要回源。顺带把 Redis 挂时的**代价**量出来了：可用性不掉（零 5xx、302 全拿到），**每击 44ms → 560ms**，因为每一击都要先等 Redis 调用失败才回落。**M3 十格到此全部收口**；不在 8.3 六行内的两项形态（Redis 停机期间那次配置变更的收敛、宣发平台不可达时 consumer 继续消费）照实留在 `docs/iterations/ITER-M3.md` §6 并各附解除条件，不当成已覆盖。

