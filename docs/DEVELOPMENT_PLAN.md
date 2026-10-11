# 开发计划（基于 DESIGN.md v2.2）

**节奏假设**：业余时间 10-15h/周；M1 ≈ 3 周。任务粒度 = 一次可提交的完整闭环（代码 + 测试 + 可执行验收命令）。
**总原则**：每个任务的验收动作必须是"跑一条命令/脚本看结果"，不接受"看起来对了"；压测与演练输出即时存档 `bench/`（1.3 口径凭证）。

---

## 一、M1 详细计划（跳转最小闭环 + 事件链路）

M1 范围裁剪说明：频控/行为评分/动态路由归 M2；`access_limit` 在 M1 只做 expire/status 检查，Lua 扣减归 M2；布隆阶段用 Guava BloomFilter + 空值缓存即可（M1 无封禁删除流程），Cuckoo 替换随 M2 停用/封禁流程一起做（DESIGN 5.2 的删除能力届时才成为硬需求）。

| # | 任务 | 内容与交付物 | 验收动作 | 依赖 | 估时 |
|---|---|---|---|---|---|
| M1-00 | **仓库骨架** | `git init` + monorepo 模块树（DESIGN 3.4：sl-common/gateway/admin/jump/consumer/console-web/mock + deploy + bench）、Java 17 + Boot 3.x BOM、`.gitignore` | `mvn -q verify` 全模块绿；`mvn dependency:tree` 验证 sl-jump 不依赖 sl-admin（Enforcer 规则 + `-ban` 断言） | — | 0.5 天 |
| M1-01 | **中间件环境** | `deploy/compose/docker-compose.yml`：mysql8、redis、kafka(KRaft)、clickhouse、nginx；healthcheck + `--profile observability`(prometheus/grafana) + `--profile redis-ha`(占位) | `make up` 后 5 分钟内 `docker compose ps` 全 healthy；Nacos 暂不引入（8G 友好，M1 用静态配置，M2 决策是否补） | M1-00 | 0.5 天 |
| M1-02 | **MySQL 数据模型 + 分片** | Flyway DDL：`xsl_00/xsl_01` 双库全套表（short_link/link_route/code_tenant_index/short_code_pool/outbox/tenant/audit_log），ShardingSphere JDBC（5.5.x，Boot3 兼容版）按 tenant_id / short_code 双策略路由 | 集成测试：插入后断言记录实际落库（`information_schema` 查询命中预期库）；同一 short_code 在两张分片键不同的表中可关联 | M1-01 | 1 天 |
| M1-03 | **ClickHouse 模型** | `click_event`(ReplacingMergeTree by event_id) + `click_stat_minute`(SummingMergeTree + 物化视图) DDL 脚本入 `deploy/` | `clickhouse-client` 执行建表 + 手动插两条同 event_id 验证去重（FINAL 查询只剩一条） | M1-01 | 0.5 天 |
| M1-04 | **租户与 HMAC 鉴权** | tenant 表 seed 3 个 mock 租户；网关 HMAC 签名过滤器（时间戳窗口 + nonce 防重放）；`Idempotency-Key` Redis 24h 中间件 | `curl` 正确签名→200；改一字节签名→401；重放旧时间戳→401；同 Idempotency-Key 二次 POST→返回同一 code | — | 1 天 |
| M1-05 | **短码池服务** | CSPRNG Base62 生成（长度 7）、批量租用（LPOP 1000/批）、DB 登记、水位 <20% 补池（Redis SETNX 锁） | 单测：字符集/长度/碰撞率；集成测：`xargs -P` 并发 1000 次租用无重复码；打空池观察补池日志且补后无重复 | M1-02 | 1 天 |
| M1-06 | **生成 API** | `POST /api/v1/links`：校验→origin_url https+域名白名单（seed 白名单含 mock 域）→事务写 3 表+outbox→写 Redis 路由缓存→布隆 add→返回短链；`GET/PATCH /links/{code}` 含 code_tenant_index 越权检查 | E2E 脚本：创建成功返回 200 + code；DB 三表 + route_json 一致；非白名单 URL→400；他租户查该 code→403 | M1-04/05 | 1.5 天 |
| M1-07 | **跳转服务（纯 302）** | sl-jump `GET /s/{code}`：布隆→Caffeine→Redis→link_route 回源（空值标记防穿透）→expire/status 检查→302；**纯 302 开关**（DESIGN 第十章要求）即默认形态，路由匹配留接口 | 单测 + E2E：正常 302 且 Location 正确；不存在→404 落地页；过期→410；冷码回源后二查命中缓存（Redis MONITOR 或 metrics 计数佐证） | M1-06 | 1.5 天 |
| M1-08 | **归因参数透传** | 白名单参数拼接模块（sl-common）：`&/?` 续接、URL-encode、禁覆盖原有参数、生成 `xy_click_id` + trace_id | 属性测试（jqwik）：任意 origin_url 带 query/编码字符/`#` 片段时，拼接结果可被 `java.net.URI` 正确解析且原参数无损；注入样本（`&`/`%26` 伪造参数）不改变语义 | M1-07 | 1 天 |
| M1-09 | **ClickEvent 链路** | jump 异步发 Kafka（失败落 WAL 文件）+ consumer 批量幂等写 ClickHouse + `GET /stats/links/{code}` 最小版 | 计数脚本：创建→模拟点击 N=1000（脚本循环）→CH `count() FINAL` 一致；`docker stop kafka` 期间点击→WAL 有记录→恢复重放后总数一致（丢失率 <0.01% 首证） | M1-03/07 | 1.5 天 |
| M1-10 | **网关与多域名** | nginx：`/s/**`→jump upstream ×2（`--scale jump=2`）、`/api/**`→admin；hosts 配 `xy1/xy2/xy3.test` 三 server_name；compose `restart: always` | `curl --resolve` 三个域同一 code 得同一 302（跨域通用首证）；`docker kill jump-1` 期间 wrk 流量 0×5xx（滚动重启零中断，DESIGN 3.3 卖点实测 #1） | M1-07 | 1 天 |
| M1-11 | **压测存档** | `bench/`：wrk 登录脚本（预置 1000 热码）、micrometer 命中率指标、报告模板（日期/JVM 参数/宿主型号/结果四元组） | 连续 3 轮 wrk：≥2000 QPS 且 P99<50ms 达标或如实记录实际值+瓶颈分析清单；结果提交入 `bench/reports/`（简历口径凭证 #1） | M1-10 | 1 天 |
| M1-12 | **里程碑收口** | tag `m1`；把 M1-11/09/10 实测数字回填 DESIGN 1.3 表格（新增"实测"列）；README 增加一键演示章节；**产出 `docs/iterations/ITER-M1.md`** | `make demo`（up→seed→创建→点击→看板 curl 出数）在干净 `docker compose down -v` 后从零可复现；ITER-M1.md 按模板填齐且凭证链接可点达 | 全部 | 0.5 天 |

合计 ≈ 12.5 人日 ≈ 3 周业余时间，含约 20% 缓冲。

> 执行状态（2026-10-01）：M1-00…M1-12 全部完成，其中 **M1-11 压测**因本机 Docker VM 只有 3.9G 而顺延到收口之后，在内存提到 8G 的次日补跑完成；实测存档见 `bench/reports/`，未达标项（跳转 P99 饱和区、签发 TPS）与其复测路径记在 `docs/iterations/ITER-M1.md` §3/§6。

**执行顺序**：M1-00→01→02/03（并行）→04→05→06→07→08→09→10→11→12。04 可与 02 并行（不碰分片表）。

---

## 二、M2 / M3 粗粒度任务（进入前按 M1 同格式细化）

**M2（+2-3 周）动态路由与防刷**：
Cuckoo 替换（含停用/封禁移除流程）｜route_rule 表 + 编译 route_json 双轨 + 管理接口｜UA 容器特征表 + 一级路由引擎｜中间页 HTML + Scheme 降级 JS + mock UA 重放测试集｜频控 Lua（滑动窗口 + access_limit 原子扣减）+ 对账任务｜行为评分规则表 + IDC/代理库离线更新脚本（free 库 + 手工样本）｜MyBatis 租户拦截器 + 三级配额｜Sentinel 网关限流 + 429。
出口标准（DESIGN 第十章）：UA 重放脚本全绿；bot 分类正确。

### 二-A、M2 已细化的部分

M2-00（资源预算重排 + 跳转 P99 治理）已单独收口（2026-10-03，见 ITER-M2）。**防刷主线**按下表细化和推进；其余粗项进入前同样细化。

| # | 任务 | 内容与交付物 | 验收动作 | 依赖 | 估时 |
|---|---|---|---|---|---|
| M2-10 | **口径先于代码** | 四处设计修正写进 DESIGN：频控置于配额扣减**之前**（5.2 步骤序）、本期做**固定 60s 窗口**而非真滑动（5.3/4.3，附两条理由）、客户端 IP 以 nginx 覆盖写入的 `X-Real-IP` 为准（4.2 ip_hash 口径）、对账任务落**管理面**（8.4） | DESIGN diff 与本表一致；ITER-M2 §5 有对应决策条目 | M2-00 | 0.2 天 |
| M2-11 | **频控 Lua + 429** | sl-jump `RateLimiter`：单脚本 `INCR + 首次 EXPIRE`，键 `sl:rl:{code}:{iphash}:{slot}`（60s 槽、默认 60 次/分钟、env 可配）；命中 → 429 + `Retry-After`，**不扣配额、不发跳转**；被拦击仍发 ClickEvent 且 `risk_score=25`（`is_bot` 判定属第 3 层评分，本期不越权标）；Redis 不可用时退化进程内计数（8.3）；`clientIp()` 统一口径：`X-Real-IP` → XFF 末段 → `remoteAddr`，频控与 ClickEvent.ipHash 共用 | 单测 + `RateLimiterLiveTest`（真 Redis：窗口内放行/越界拒/EXPIRE 只设一次/降级路径/换 IP 互不影响）；`xsl_jump_rate_limit_total{result=allow\|block\|degraded}` 可查 | M2-10 | 1.5 天 |
| M2-12 | **access_limit 对账与冷重建** | `link_route` 增 `access_used/access_used_at` 快照列（`01-schema.sql` 用 information_schema 守卫的幂等 ALTER，双分片库都要）；sl-admin `@Scheduled` 每 60s 以 Redis 权威值回写；jump 在键缺失时按 `access_limit − 快照已用 − 保守缓冲` 初始化（宁可少放不可超放） | M2-13 脚本 E 段：删掉 `sl:cnt:*` 后重建，剩余量 = 期望值 − 缓冲（不超放）；跑一轮对账后 MySQL 值与 Redis 权威一致 | M2-11 | 1 天 |
| M2-13 | **端到端验收脚本** | `scripts/accept-m2-13.sh`；三处旧脚本的 Redis 清理清单补 `sl:rl:*`（现只删 `sl:r:`/`sl:cnt:`） | 断言：阈值内 302、越界 429 且带 `Retry-After`；客户端自带 `X-Forwarded-For` **绕不过**频控；换 IP 不受影响；命中频控后 `sl:cnt` 余数不变（顺序修正的回归证据）；窗口滑过后恢复；被拦击进 CH 且 `risk_score=25`；`docker stop xsl-redis-1` 期间跳转成功率不跌零 | M2-11/12 | 0.5 天 |
| M2-14 | **频控 Lua 基线 + 跳转不退化** | `bench/run-ratelimit-bench.sh`：`redis-benchmark --eval` 在 redis 容器内跑同一份脚本（同机回环口径如实声明）；复跑 `run-jump-loadcurve.sh` | DESIGN 8.1-A 的「频控 Lua ≥3 万次/s」由"未测"改为实测值 + 存档；loadcurve 目标负载段 P99 相对 M2-00 基线（21.08ms@5,002 QPS）退化 <20%，否则如实记录并归因 | M2-11 | 0.5 天 |
| M2-15 | **防刷收口** | `make verify` 全量；重跑 `accept-m1-04/06/07/09/10`（本期动了 429、`risk_score`、ipHash 三处契约相关面）；顺路改名 `sharding-jump.yaml`→`sharding.yaml`、`ShortCodePoolServiceTest`→`ShortCodePoolServiceLiveTest`；ITER-M2 §2/§3/§4 回填 | 旧验收全绿或有偏差记录；ITER-M2 里每个数字都能点达到 `bench/reports/` 的具体行 | 全部 | 0.5 天 |

**M2-01 事件链批量化**（2026-10-03 细化；由 M2-00 的归因直接引出——负载中 ClickHouse ≈1.9 核、停 consumer 后同一入口从 ~14k 跳到 24,525 QPS，上限在写入侧不在数据面）

| # | 任务 | 内容与交付物 | 验收动作 | 依赖 | 估时 |
|---|---|---|---|---|---|
| M2-20 | **改动前先复采基线** | 同一台机器、安静时段跑齐三份：`run-jump-loadcurve.sh`（chain-live 上限）、`run-kafka-bench.sh`（堆积与追赶速率）、`run-jump-attribution.sh`（逐容器 CPU + CH merges/part 数）。producer 参数、批次大小、分区数**全部维持现状** | 三份存档齐备且 `env_snapshot` 与后续复采同档（loadavg/容器内存合计相近），否则不具备对照资格 | M2-15 | 0.3 天 |
| M2-21 | **ClickHouse 写入批量化** | ① consumer `max.poll.records` 500→5000（`XSL_CH_BATCH_SIZE`）+ `fetch.min.bytes`；② 写入 URL 加 `async_insert=1&wait_for_async_insert=1&async_insert_busy_timeout_ms=200`（**wait 必须为 1**，见 ITER-M2 §5 的丢失率论证）；③ jump 侧 producer 参数化：`linger.ms`/`batch.size`/`compression.type=lz4`/`buffer.memory`（经 compose 注入，默认值保守） | 每 5,000 行发起的 HTTP POST 次数相对改前下降 ≥5×；`system.parts` 增量与 `system.merges` 排队数下降；`accept-m1-09` 仍 0 丢失、WAL 演练通过 | M2-20 | 0.5 天 |
| M2-22 | **分区与消费并行** | topic `shortlink-click` 1→4 分区（`kafka-init` 服务改 + 已存在 topic 的 `--alter` 幂等说明）；consumer `Concurrency` 2→4。点击事件行间无先后依赖、去重靠 `event_id`+ReplacingMergeTree，故按 `short_code` 分区带来的跨分区乱序不影响正确性（这条要在 DESIGN 6.3 显式记下，别让它成为隐含假设） | 追赶速率随并行度的变化有存档（1/2/4 三档）；同一 event_id 双投递经 FINAL 仍恰为 2 行 | M2-21 | 0.5 天 |
| M2-23 | **复采与判定** | 与 M2-20 **同方法**复采三份；给出 chain-live QPS 上限、P99@目标负载、CH CPU、merge 排队、丢失率五个量 | 上限 ≥20k 且 P99@≥2000 QPS 相对 M2-00/频控基线不退化；没达到就照实写"为什么没到"，不把口径改成能过的样子 | M2-21/22 | 0.5 天 |
| M2-24 | **回写与收口** | DESIGN 6.3（批量语义与乱序）、8.1-A（吞吐/延迟行与限制说明）、8.3（Redis/Kafka 降级表若受影响）；重跑 `accept-m1-09`（动的是它整条链路）+ `accept-m2-13`；ITER-M2 §2/§3/§4/§6 回填 | 每个数字可点达到 `bench/reports/` 的具体行；改前/改后两组存档都在仓库里（ITER-M2 §5 的"改配置必须留前后两组"纪律） | 全部 | 0.3 天 |

**本期明确不做**：不调 ClickHouse 的 2G 内存上限（M2-00 已把它定为被测口径的一部分，改了历史数字就不可比）；不引入 Kafka exactly-once（8.4 已选定 at-least-once + 读时去重，换语义的收益抵不上事务标记的复杂度）；一次只动一个杠杆并单独存档——M2-00 的教训是十个变量一起改等于什么都没测出来。

### 二-B、M2 动态路由主线（2026-10-03 细化）

摸底发现三处"文档说做了、代码里其实没有"，本轮一并处理并记进 ITER-M2 §4：① `xsl.jump.mode` 只出现在注释里、没有任何 Bean 条件装配，**DESIGN 第十章承诺的"纯 302 回退开关"目前是假的**；② `route_rule` 有表、有分片配置，**Java 侧零引用**；③ outbox 只有写方（`LINK_CREATED/LINK_UPDATED`）没有同步方。

| # | 任务 | 内容与交付物 | 验收动作 | 依赖 | 估时 |
|---|---|---|---|---|---|
| M2-30 | **口径先于代码** | 六处写进 DESIGN 6.2：特征表自维护 + 可配置（不引第三方 UA 库）、未知事实一律落 default（不误伤）、`mode` 开关做成真条件装配、`wx_urllink` 在本项目是 mock provider（不接真实微信 API）、`geo_province` 在离线库接入前恒为"未知"、mock 购票页落地为 nginx 静态回显（不建 Java 模块） | DESIGN diff 与本表一致；ITER-M2 §5 有对应决策条目 | M2-24 | 0.2 天 |
| M2-31 | **route_rule 双轨** | `RouteConfig` 增 `rules`（编译产物进 `route_json`）；sl-admin 侧规则校验（condition_type/op/target_type 白名单、priority 非负、条数上限）+ 规则写入（`POST /links` 带 `rules[]`、`PUT /links/{code}/rules` 整体替换）+ 编译器（行式真源 → route_json，按 priority 升序）；版本递增与缓存回写沿用现路径 | `LinkServiceIntegrationTest` 扩展：存 3 条规则后 `route_json.rules` 有序、PATCH 后 version+1、非法 condition_type → 400、他租户 → 403；route_json 形状变了要重跑 `accept-m1-06` | M2-30 | 1 天 |
| M2-32 | **UA/容器特征引擎** | sl-common `UaClassifier`：微信/QQ/抖音/微博/支付宝容器 + iOS/Android 设备 + 未知；特征表从配置加载（DESIGN 的"月度更新"在单机就是改配置不改代码）；jump 填上 ClickEvent 的 `device_type/os`（此前恒为空串） | 单测覆盖 ≥12 条真实 UA 样本；CH 里 `device_type/os` 不再为空（成为 M2-35 与 6.4 双指标的取数依据） | M2-30 | 0.5 天 |
| M2-33 | **一级决策 + 中间页 + 真回退开关** | `DynamicRouteResolver`（priority 升序首条生效）；`@ConditionalOnProperty` 让 `xsl.jump.mode=direct302\|dynamic` 成为真开关（默认 dynamic，回退只改一个环境变量）；`h5/app_scheme/wx_urllink/intermediate` 四类 target；中间页由 jump 返回 200 HTML（带 `Cache-Control`），JS 用 `location.href=scheme` + 2.5s 内无 `visibilitychange/pagehide` 则降级 H5；`wx_urllink` 走 `UrlLinkProvider` 接口 + 本地 mock 实现 | 单测覆盖四类 target 与未识别 UA 落 default；`accept-m1-07` 在 dynamic 模式下 302 语义不变 | M2-31/32 | 1.5 天 |
| M2-34 | **mock 购票页回显** | nginx 加 `mock.ticketsales.test` server：静态页回显 query + fragment（此前该 Host 落 default_server 被 444 掐断，302 之后浏览器只看到 DNS 错误） | `curl --resolve` 能看到回显；`make demo` 的 302 终点从死链变成可看点 | M2-33 | 0.3 天 |
| M2-35 | **UA 重放测试集（M2 出口标准）** | `scripts/accept-m2-35.sh`：≥12 条真实 UA 逐条断言命中的 `target_type`、响应形态（302 / 200 中间页）、scheme 与 fallback、`device_type/os` 入库；外加"改一个 env 就回退 direct302"的开关验证 | 全绿即达成 M2 出口标准"UA 重放脚本全绿"；旧验收 `accept-m1-06/07/09/10` + `accept-m2-13` 全部重跑 | M2-33/34 | 0.7 天 |
| M2-36 | **回写与收口** | DESIGN 6.2/6.4/8.5/1.3；ITER-M2 §2/§3/§4/§5/§6；README 演示章节（浏览器里能看到中间页降级） | 每个实测数字可点达到存档；偏差有记录 | 全部 | 0.3 天 |

**本主线明确不做**：不接真实微信开放接口（无凭据，只留 provider 接口）；不引 yauaa/ua-parser（特征表自维护，代价是覆盖面窄，靠"未识别一律 default"保证不误伤）；不做 geo_province 的 IP 地理库（数据源缺失时该条件恒不匹配，与 IDC/代理库是同一件事，留给评分那格）；不写 outbox 同步器（M3 那一格，本轮沿用现路径直写 `route_json`）。

**M3（+3 周）归因闭环与看板**：
outbox 同步器 + `version` 强校验通道｜归因回传 mock 宣发平台（batch_id + 校验和幂等）｜**Vue 3 看板**：链接列表 / 渠道漏斗 / 双指标三页（Vite + ECharts，同源反代；2026-10-11 定口径时去掉 Element Plus，见二-C 第 9 条）｜8.3 降级演练全表（逐条 docker stop 存档）｜多域名切换演练｜端到端对账脚本 <2%。
出口标准 + tag `m2`/`m3` 同 M1-12 模式。

### 二-C、M3 已细化的部分（2026-10-04 开工，随做随细）

M2-36 收口时查出一条**前置缺口**：`click_event` 明细表里没有 `xy_click_id`，`trace_id` 也不落库，所以"某一笔订单是从哪一次点击来的"这个问题在 ClickHouse 里答不出来——端到端对账脚本、渠道转化率、看板的"下单转化"三件事都卡在这一个键上。所以 M3 从它开始，而不是从看板开始。

| # | 任务 | 内容与交付物 | 验收动作 | 依赖 | 估时 |
|---|---|---|---|---|---|
| M3-01 | **点击 ↔ 订单的对账键** | `click_event` 增 `click_id` 列（值就是跳转 URL 上的 `xy_click_id`，订单侧带的也是它）；`ClickEvent` 消息体加同名字段；jump 把归因拼接产出的 clickId 放进事件（**被频控拦下的点击没有跳转目标 → 留空串**，理由写进注释与设计）；consumer 落库；老数据卷用幂等 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` 补列 | ① `accept-m1-09` 新增断言：**同一次点击**的 Location 里 `xy_click_id=` 的值，等于 ClickHouse 里那条事件的 `click_id`；② 历史行 `click_id=''` 可数出来（不假装能回填）；③ 改契约必重跑：`make verify` + `accept-m1-09/07` + `accept-m2-32/35`；④ 实测一次"按 click_id 点查"的耗时并写进 ITER §3——**是否要加跳数索引由这个数决定**，不凭感觉加 | M2-36 | 0.5 天 |
| M3-02 | **outbox 同步器 + `version` 强校验通道** | sl-admin 侧 `@Scheduled` 同步器：读 `outbox` 的 `status=0`，把 payload 里的 `route_json` 按 version 守卫应用到 `link_route` 并回写 Redis 缓存，成功置 1、失败 `retry_count++` 超阈置 2；Redis Pub/Sub 失效广播让 jump×2 清掉本地缓存；`POST /internal/outbox/replay` 手动触发同一轮逻辑；积压与最老积压年龄进指标。落地口径五条见下方"M3-02 的落地口径" | `scripts/accept-m3-02.sh` 五组断言（缺行补齐后 404→302、落后 version 回正、重放整批不倒退、连打两次幂等、双实例亚秒收敛）+ 契约回归重跑 | M3-01 | 1.5 天 |
| M3-03 | **归因回传 + mock 宣发平台** | sl-consumer 的 `@Scheduled`（默认 5 分钟一轮、回看 3 个完整分钟）从 ClickHouse **明细表 FINAL** 现算 `(tenant, campaign, channel, minute)` 的 pv/uv/valid_pv/bot_pv，按租户切成 batch 推给新模块 **sl-mock**（端口 8050、MySQL 库 `xsl_mock`）；`batch_id` 由「窗口 + 内容」确定性地算出来，接收端做 HMAC 验签 → 重算 checksum → `batch_id` 幂等 → 按桶 upsert 覆盖。落地口径七条见下方「M3-03 的落地口径」 | `scripts/accept-m3-03.sh`：① 回传闭环（接收端台账与桶值 == CH 明细同窗口现算值）；② 篡改 items 后 checksum 不符 → 400 且台账不留值；③ 同 `batch_id` 重投 → 不重复计入、`times_received+1`；④ 错签／越窗 → 401；⑤ 迟到点击在同桶产生新内容 → 新 batch 覆盖旧值（不是被幂等丢掉）；⑥ 实测一次「5 分钟窗口明细聚合查询」的耗时并写进 ITER §3（**这个数字决定要不要改用聚合表 + 加 valid_pv 列**）；⑦ 契约回归重跑 | M3-01（对账键）、M3-02 | 1.5 天 |
| M3-04 | **端到端归因对账（M3 出口标准）** | mock 侧补一个假订单接口（`POST /mock/orders`，落 `xsl_mock.mock_order`（`order` 是 MySQL 保留字，表名带前缀），字段按附录 B：`click_id/channel_id/campaign_id/promoter_id`）；对账拆两份——`scripts/recon-attribution.sh` 是可反复跑的核对工具（输出两条差异率 + 聚合表偏差观测项，按阈值给退出码），`scripts/accept-m3-04.sh` 铺数据并调它。落地口径七条见下方「M3-04 的落地口径」 | 两条各自给数字、不合成总分：① 传输完整性＝「平台收到的 Σpv 与明细同窗口 pv 之差的绝对值」÷「明细 pv」< 2%（两条数必须取自同一个已关闭的 `[from,to)`）；② 归因可 join ≥ 98%，**并且注入几条引用不存在 click_id 的坏订单，断言对账报出的缺失条数恰好等于注入条数**（只测主组的话，100% 命中可能只是两边取了同一个集合） | M3-03 | 0.7 天 |
| M3-05 | **看板后端：只读统计接口（四条 → 五条）** | sl-consumer 侧把 DESIGN 7.1 承诺过却一直只有 `click_count` 一列的 `GET /stats/links/{code}` 补成真的（`from/to/granularity` + 时间序列），并新增 `GET /stats/tenants/{id}/links`（链接列表）、`/stats/tenants/{id}/channels`（渠道漏斗）、`/stats/campaigns/{id}`（活动趋势，7.1 那行空头支票的兑现）。**全部走 `/api/v1/stats/**` 这条已经不过网关 HMAC 的只读前缀**（第 2 条）。落地口径十条见下方「M3-05…M3-08 看板的落地口径」 | ① 每条接口的每个字段都等于脚本自己直查 ClickHouse 的同一个窗口值（**0 容差**）；② **实测 24h 与 7d 两个窗口扫明细的 read_rows/耗时**并写进 ITER §3——这个数字决定要不要改用 `click_stat_minute`（本项目第四次"由数字决定"）；③ 改了 stats 响应形状必重跑 `make demo` + `accept-m1-09`（它们都在读 `click_count`）——**已跑通**：`make demo` 全通、`make verify` **168 测 / 0 失败 / 0 错误 / 0 跳过**（本项新增 12：`StatsWindowTest` 5、`StatsServiceLiveTest` 7） | M3-04 | 1 天 |
| M3-06 | **看板工程与挂载：`sl-console-web/` 从零到能打开** | 新建 Vue 3 + Vite + ECharts 工程（**不含 Element Plus**，第 8 条），`make web` = `npm ci && npm run build`，产物 `dist/` 不入库；nginx 新增一个 `listen 8081` 的 server block 做**静态托管 + 同源反代**（`/` → dist、`/api/v1/stats/` → consumer），不新增容器 | ① `make web` 在干净仓库可从零产出 dist；② **`make up` 的前置要检查 dist 存在**并给出 `make web` 提示（挂一个不存在的目录 = 空站，与 `tls` 那条教训同款）；③ 浏览器打开 `http://xy1.test:8081/` 无 CORS 报错（同源）——**已验（2026-10-11）**：`make web` 产出 dist（1.12 MB / gzip 375 KB，几乎全是 ECharts），`up` 已把 console-dist 做成前置，8081 首页 200、`/api/v1/stats/**` 经同一 origin 200、其余 `/api/**` 在这上面返回 404（看板不该碰到写侧） | M3-05 | 0.5 天 |
| M3-07 | **三页实现：链接列表 / 渠道漏斗 / 双指标** | 三页各调一条接口 + 一张图；页面标题如实写"点击统计列表"（不是管理面台账，第 3 条）；漏斗画 渠道→点击→（有效／被频控拦下）三档，**转化那一档留空并标注"未接入"**（第 5 条）；双指标页把 `bot_pv` 恒 0 显示成"评分层未交付"而不是画一个 0 的柱子骗人 | 用真实浏览器逐页打开并截图，**页面上的数字与接口返回逐个一致**（接口对但页面错是另一类失败，只有浏览器能抓到）；空数据窗口（今天没人点）三页都要有明确空态而不是一片白——**已验（2026-10-11）**：三页渲染后的 DOM 数字与接口逐个相等（列表 254/19/254、50 行；漏斗 5 个渠道 + 容器三档 + 转化列显示「未接入」；双指标 8 个时间桶、有效 254、疑似刷量 0 并写明是能力边界），两张 ECharts 图都有 canvas。**这一轮抓出的两个缺陷只有浏览器看得见**（页面借了没有 `series` 的渠道接口 → 报「没有点击」而后端正常；路由键形态被我改错 → 三页全回落到第一页），见 DESIGN 附录 A v2.14.1 | M3-06 | 1.5 天 |
| M3-08 | **看板收口：验收 + 契约回归 + 文档回填** | `scripts/accept-m3-05.sh` 把 M3-05/07 的判定式做成脚本（铺一批真点击 → 调四条接口 → 逐字段与 CH 直查比对 → 读页面渲染值）；回填 DESIGN 1.3 的看板行实测、ITER-M3 §2/§3/§4/§5/§6、README 的验收表与模块表 | `make verify` + 旧验收全绿（`accept-m1-09`/`make demo` 是必重跑项，因为 stats 响应改了）；DESIGN 644 那行 M3 出口标准的"看板三页面数据与 ClickHouse 直查一致"由脚本给出可点达的凭证——**已收口（2026-10-11）**：`accept-m3-05.sh` **55/55**（69 个字段 0 容差、`blocked_pv=3` 第一次被测到非零）、`make verify` **169 测 / 0 失败**、旧验收 11 套全绿、`make demo` 全通 | M3-07 | 0.5 天 |
| M3-09 | **8.3 降级演练全表（M3 出口标准第三条）** | `scripts/drill-8-3.sh` 逐条演练 8.3 那六行：**停 → 断言的是"预案的行为"而不是"容器停了" → 起 → 量恢复时间**，每条原始计数追加进 `bench/reports/<ts>-m3-drill-8-3.md`（只增不改）。第六行"数据丢失"本轮一并补上它承诺的两个东西：`make backup`（mysqldump 三库 + ClickHouse `BACKUP`）与 `make restore-verify`（**恢复演练**：dump 灌进一次性 MySQL 容器核对、CH `RESTORE ... AS` 影子表比行数——都不碰现有卷）。落地口径见下方「M3-09/M3-10 的落地口径」 | 六行每行都要有：停期间的**成功率/错误码断言**（例如 CH 挂时 stats 必须 503 而不是空数据）、恢复后的**完整性断言**（事件总数、lag 归零）、以及一个**恢复耗时秒数**；`make restore-verify` 必须退出码 0 且打印"备份文件里读回来的行数 == 原表行数" | M3-08 | 1.5 天 |
| M3-10 | **多域名切换演练（9.5 的机制那一半）** | admin 侧真正从 `xsl_base.domain_pool` 选生效域（`status=0` 且 `weight` 最高，同分按域名字典序保证确定性；应急时 `XSL_DOMAIN_POOL=false` 把生效域定回 `base-domain`，不必改数据），切换时把 `switch_time` 落库。`scripts/drill-domain-switch.sh` 演练"告警→封禁一条域→新码换域、存量码三域通用"，并把 9.5 那句"切换机制完整实现"从愿望变成有脚本可跑 | ① 同一条码在 xy1/xy2/xy3 三个 `server_name` 下 302 的 Location 完全一致（code 与域无绑定）；② 把 `xy1.test` 置 `status=2` 后新建的链接 `short_url` 换到别的域，且**存量码在旧域仍能跳转**（旧域只是不再发新码，不是下线）；③ `domain_pool.switch_time` 有值可查；④ 告警来源用一条 `UPDATE` 模拟，真实 Grafana webhook 留推演——**已达成（2026-10-11）：`scripts/drill-domain-switch.sh` 13/13**（三域去掉本次 click_id 后逐字符相同、每击一个新 id、weight 全等时稳定选中 xy1.test、封禁后 **31s** 换到 xy2.test、存量码在旧域仍 302、`switch_time` 落库、`xsl_domain_pool_total{result="pool"}` 读到 14 次、复位后回到 xy1.test） | M3-09 | 1 天 |

**M3-01 明确不做（以及做完后的结论）**：不改 `click_event` 的排序键（`(short_code, click_time, event_id)` 是 ReplacingMergeTree 去重依据，动了等于换幂等语义）；**不加跳数索引**——2026-10-04 实测：点查一个不存在的 `click_id` 0.305／0.499s（扫 48,358,068 行、读 435 MB），一次 `IN` 1000 个 id 0.813s，而加索引要为 4.33 GiB 历史 parts 做 `MATERIALIZE`，代价大于收益（数字与命令见 ITER-M3 §3/§5）；**不回填历史行的 click_id**（那个值从来没进过消息体，回填=编造数据）；订单回传接口本身属 M3-03，不在这一项里顺手做。

**M3-01 已收口（2026-10-04）**：`make verify` 全仓 125 测 0 失败；`accept-m1-09` 16/16（新增两断言：URL 上的 `xy_click_id` 与 CH 里那行的 `click_id` 是同一个串）、`accept-m2-13` 17→19/19（被拦击 `click_id` 为空串、放行点击必带值）；契约回归七套全绿含 `accept-m2-35` 87/87。

**M3-02 的落地口径（写代码之前定下来，DESIGN 8.4 同步细化）**——摸底结论先摆出来：`outbox` 自 M1-06 起有三处 INSERT（`LINK_CREATED`／`LINK_UPDATED`×2）**但零读方**，`link_route` 与缓存全靠管理面直写；直写一旦失败只留一行 error 日志，最坏的一种是创建时 `insertRouteAndIndex` 失败 → **`link_route` 根本没有这一行 → 这条短链永久 404**，而主表 `short_link` 里它活得好好的。下面五条把"补偿"从一句话变成能测的东西：

1. **谁跑、多久**：`@Scheduled` 放 **sl-admin**（与 M2-12 的配额对账同一侧：管理面已有 MySQL 写权与 Redis 读写，数据面继续保持"只写 Redis、不写业务表"）。默认 5s 一轮、每轮最多 500 行（上限放宽到 5000 是为了首次上线那一趟：本机历史积压实测 7,955 行全是 `status=0`，一轮 200 行会把新事件压在队尾）、按 `id` 升序；`xsl.outbox-sync.*` 可调（interval / batch / max-retry / enabled）。
2. **状态机**：`0 待投递 → 1 已投递`；抛异常则 `retry_count++` 并**留在 0**（下一轮自然重试），`retry_count ≥ max-retry`（默认 10）置 `2 失败` 并计指标——2 是"要人看"的终态，不再自动重试，避免一条坏 payload 永久占住一轮批次。
3. **`version` 强校验通道 = 同批条件写，不另造机制**：`UPDATE link_route SET route_json=?, version=? WHERE short_code=? AND version<?`，**0 行受影响时先看这一行在不在**——不在就补插，而且是**成对补**：`code_tenant_index` 与 `link_route` 是同一段代码写进去的，只补路由会得到一条「跳转能 302、管理面按码查归属却 404」的链接（这正是上面那条最坏情况），在且 version 不低于 payload 就是"已被更新的事件取代"，直接置 1 而不是重试。Redis 侧沿用 jump 回源那套 Lua `version 比较`，并且**缓存永远写当时最权威的那一份**（事件被取代时拿表里那份更高的去修缓存），任何情况下都不拿旧事件覆盖缓存——否则会得到「路由表 v5、缓存 v4」，跳转要等缓存 24h 过期才回到 v5。**只有真的写动了缓存才发广播**：重放历史积压时绝大多数事件一行都不用改，为它们各发一条通知只会让两个 jump 实例白清一遍本地缓存。紧急封禁的"立即失效"就落在这两处条件写 + 下面第 4 条的广播上。
4. **缓存失效广播**：通道 `xsl:route:evict`，载荷就是短码字符串（订阅方只做一件事，不必传 JSON）。**发布点在管理面每次写完 `link_route`+缓存之后**（直写路径与同步器路径都发）；jump×2 各起一个订阅容器，收到就 `invalidate(code)` 清 L1。**尽力而为、不持久化**：Redis 重启或订阅瞬断会丢消息，此时正确性由 L1 的 30s TTL 兜底（DESIGN 8.4 原本就接受这个窗口），广播只是把窗口从 30s 压到亚秒。
5. **手动补偿接口**：`POST /internal/outbox/replay` 同步跑一轮并回汇总；带 `?short_code=xxx` 时只补那一条链接（定点修，不必等整批按 id 升序扫到它——测试与验收脚本因此都是确定性的）。**刻意不放在 `/api/` 前缀下**——`/api/**` 才经 gateway 的租户 HMAC 鉴权、也只有 `/api/**`/`/s/`/`/{code}` 会被 nginx 路由，所以它天然只在内网与 admin 直连端口可达（DESIGN 7.2 那行同步注明）。它做的事与定时任务同一份逻辑，且第 3 条的 version 守卫保证**多跑不错、跑不坏**，因此不再为其单独造一套凭据。

**M3-02 明确不做**：不引新依赖（不装 Kafka/Quartz/ ShedLock——单机 admin 单实例，重复调度不存在）；**不改 `outbox`/`link_route` 的分片轴**（扫描 `status=0` 会广播到 xsl_00/xsl_01 两库再归并，200 行/轮的代价实测在验收里给数字，不为此加表）；不把 `payload_json` 换成事件类层次（现在只有 `LINK_CREATED`/`LINK_UPDATED` 两种，payload 里带完整 `route_json`，同步器不需要知道事件类型语义）；不给 Pub/Sub 加持久化与重试；不动 `access_used` 快照列（它有自己的对账周期，与本项无关）；**不做 outbox 行的清理/归档**（`status=1` 的行留着当审计线索，容量属 9.6 保留期那格）。

**M3-02 已收口（2026-10-11）**：`scripts/accept-m3-02.sh` **62/62**；`make verify` 全仓 **134 测 / 0 失败 / 0 跳过**（新增 9 测：补偿 5 + 缓存与广播 3 + jump 本地失效 1）；契约回归八套全绿（`m1-04` 7、`m1-06` 33、`m1-07` 15、`m1-09` 16、`m1-10` 19、`m2-13` 19、`m2-32` 19、**`m2-35` 87**）+ `make demo` 全通。实测口径见 ITER-M3 §3；两处「看起来能过、其实什么都没测到」的修复（admin 与 gateway 的 `/actuator/prometheus` 一直是 404；验收脚本被自己的管道截断后把定时开关留在了 false 上）记在 §4。

**M3-03 的落地口径（写代码之前定下来，DESIGN 6.3/7.3/6.4 同步细化）**：

1. **谁跑、多久**：`@Scheduled` 放 **sl-consumer**（`xsl.push.interval-ms:300000` = 5 分钟一轮、`xsl.push.lookback-minutes:3` 回看三个完整分钟）。为什么不是 sl-admin：**ClickHouse 只有 consumer 会读**（DESIGN 6.4 的面分离，admin 连 CH 客户端都没有）；`@EnableScheduling` 挂 `ConsumerApplication`（M3-02 的教训：调度是应用级能力，不该由某个任务类掌管）。
2. **事实源是明细表 `click_event FINAL`，不是聚合表 `click_stat_minute`**：① 聚合表没有 `valid_pv` 列，为一个字段去动 MV + 老卷迁移不划算；② 聚合表靠 `async_insert` + 后台合并，part 未合并时读它本身就有重复/缺失风险，而回传给外部平台的必须是最权威的那份数；③ 下一步的对账需要一个能当基准的事实源。代价是每轮要扫一遍窗口内的明细——**这个耗时本轮实测出来写进 ITER §3，数字不可接受就换回聚合表并加列**（M3-01 那次"由数字决定加不加索引"的同一姿势）。
3. **batch 以租户为边界，`batch_id` 是内容寻址的**：一轮里按 `tenant_id` 分组，一个租户一个 batch；`batch_id = xsl-{firstMinute}-{lastMinute}-{sha1(规范化 items) 前 10 位}`，`checksum = sha1(规范化 items)` 全串。规范化 items = 按 `(campaign_id, channel_id, minute)` 升序、每行 `campaign|channel|minute|pv|uv|valid_pv|bot_pv`、行间用 `,` 连接。**为什么 id 里要带内容 hash**：同一窗口的数据会因迟到而变全，纯窗口 id 会把"更全的那一版"当成重复丢掉；带内容之后，重投（内容没变）id 相同、被幂等吸收，补投（内容变了）id 不同、走覆盖更新。
4. **`valid_pv` 与 `bot_pv` 的临时口径**（评分层没做、`is_bot` 恒 0，必须写清而不是假装准确）：`valid_pv = countIf(is_bot = 0 AND risk_score < 25)`，现在等价于"被放行的点击数"；`bot_pv = countIf(is_bot = 1)`，**当前恒 0，这是能力边界不是链路缺陷**。评分层落地后这两个式子一个字都不用改，数值自然变对。
5. **接收端只做三件事**：HMAC 验签 + 时间戳窗口（复用 sl-common 的 `HmacSigner`，密钥用 DESIGN 9.4 里设计内的演示密钥，**不新增明文凭据**）→ 重算 checksum，不符直接 **400 拒收**且台账不留值 → 过了才 `batch_id` 幂等（同 id 重复计入被拒、只 `times_received+1`）并按 `(tenant, campaign, channel, minute)` **upsert 覆盖**。**刻意不做 nonce 防重放**：幂等键已经把重复投递消化掉了，加一张 nonce 表只是再养一份要清理的状态。
6. **失败不重试、不水位**：HTTP 非 2xx 或超时 → 本轮放弃，下一轮回看窗口内同批内容算出同一个 `batch_id` 重投，接收端幂等吸收。所以**不需要持久化水位**（Redis/MySQL 都不加新状态），代价是"迟到超过回看窗口的点击不进回传"——这条边界写进 DESIGN 6.3。
7. **sl-mock 从占位目录变成真模块**：进 reactor、Spring Boot 服务、端口 8050、MySQL 新库 `xsl_mock`（`init/mysql/03-mock-schema.sql` 幂等建表（排在 02-seed-tenant 之后，编号即执行顺序），普通 JDBC，不经 ShardingSphere）。**不挂 nginx 对外路由**——它是"外部平台"的等价物，只从 compose 内网被 consumer 调用，脚本对账时经宿主 8050 取数。

**M3-03 明确不做**：不接真实宣发平台/微信（等价实现，DESIGN 风险 #1 的口径）；不改 `click_stat_minute`、不加 `valid_pv` 列、不动 MV（除非第 2 条那个实测数字逼着改）；不做 nonce 表与重放检测；不引入水位/游标存储；**假订单接口属 M3-04**（对账要用它，那时一并做）；不做 key/secret 分离与轮换（DESIGN 9.4 里一直欠着，仍是 P2）；回传不做压缩/分页/流式（一桶一行、一轮最多几千行，用不上）。

**M3-03 已收口（2026-10-11）**：`scripts/accept-m3-03.sh` **37/37**（A 端到端闭环、B 幂等、C 校验和、D 身份四例、E 迟到吸收、F 取数代价、G 开关与定时）。**第 2 条那个"由数字决定"的出口当场就关闭了**：10 分钟窗口的明细聚合实测读 6,755 行 / 263 KB、耗时 5ms（表内 48,358,256 行），服务自报本轮取数 9–10ms ⇒ **明细 FINAL 这条路够用，不给聚合表加列**。`make verify` 全仓 **153 测 / 0 失败 / 0 跳过**（新增 19：codec 6 + 验签 7 + 接收端活体 6）；旧验收八套全绿（`m1-04` 7、`m1-06` 33、`m1-07` 15、`m1-09` 16、`m1-10` 19、`m2-13` 19、`m2-32` 19、**`m2-35` 87**）+ `accept-m3-02` 62/62 + `make demo` 全通。五条过程教训（含一次"看起来是产品回归、其实是 Redis 停机窗口"的定性）记在 ITER-M3 §4。

**M3-04 的落地口径（写代码之前定下来，DESIGN 附录 B/6.4/8.5 同步细化）**——这一项没有新机制，全部价值在"两条差异率能不能被算出来、算错了能不能被发现"：

1. **假订单接口落在 sl-mock**（`POST /mock/orders`，表 `xsl_mock.mock_order`）。字段严格按附录 B：`click_id / channel_id / campaign_id / promoter_id / first_click_time / last_click_time`，另加一个 `order_id`。**`order_id` 由调用方给、当主键用**：真实形态里订单号本来就是购票系统生成的，用它做幂等最自然——重复提交同一 `order_id` 返回已有记录、不重复计入（否则"用户双击"就会把转化率算掉一半）。
2. **它不鉴权，且这是写下来的决定**：这个接口扮演的是"购票页后端 → 平台"这一跳，两个都是 mock 侧的角色，不碰业务库、只往 `xsl_mock.mock_order` 里落归因字段。真实形态下这一跳的鉴权由购票系统与平台另谈，**不属于我们系统的对外契约**（7.3 里我们的对外契约只有"回传"那一行）。写清楚是为了以后不被人当成"忘了加签名"，也不是现在就给它套一层假 HMAC。
3. **谁下单**：验收脚本扮演购票用户——从 302 的 `Location` 里抓出真实的 `xy_click_id`，再拿它下订单。**不是**改 mock 购票页去自动下单：静态回显页（M2-34）的职责到"回显归因参数"为止，让它产生副作用会牵动 M2-35 那 87 条断言，收益不划算。
4. **对账拆两份**：`scripts/recon-attribution.sh` 是**可反复跑的核对工具**（T+1 也要用它），输出两条差异率 + 一个观测项，按阈值给退出码；`scripts/accept-m3-04.sh` 负责铺数据、调它、并断言"阈值达成 + 坏样本被完整抓出"。不合并成一个脚本的理由：对账是运维动作、验收是证据动作，两者的生命周期不同（同 `bench/reports/` 只增不改那条纪律同源）。
5. **① 传输完整性的窗口必须对齐**（这是最容易算错的地方）：两条数必须来自**同一个已关闭分钟区间**——脚本先确认点击已落进明细（`A-clicks-landed-in-ch` 那一招）、等分钟关闭、手动触发一轮回传，再比「mock 收到的 Σpv」与「明细在同一 `[from,to)` 里的 pv」。拿"现在"对"现在"会得到一个永远不为零的假差异（回传只推已关闭分钟）。
6. **② 归因可 join 要连"能不能抓到坏数据"一起测**：主组订单全部引用真实 `click_id`（这一组要求 ≥98%，实际应当是 100%）；另外**故意注入**几条引用不存在 `click_id` 的订单，断言对账工具报出的缺失条数**恰好等于**注入条数。只测主组的话，"100% 命中"可能只是因为算式两边取了同一个集合——负向样本才是这把键真的在工作的证据。**同一形状也补给了 ①**（收口时加的，计划里原本只有 ② 有负样本）：另起一批"只点击、不回传"的活动，要求差异报 **100.00% / FAIL**，而 ② 在同一批上判 `n/a`——"两侧都没数"与"数丢了一半以上"必须是两个能区分的值，否则最坏的失败形态会伪装成一个无害的 `n/a`。
7. **`click_stat_minute` 与明细的偏差只作为观测项打印**，不进 2% 阈值（6.4 已写）。它的作用是：如果哪天聚合表明显跑偏，这里能看见，而不会污染对账结论。

**M3-04 明确不做**：不做真实购票系统对接、不做订单金额/座位等业务字段（对账用不上）；不给 mock 订单接口加 HMAC（理由见第 2 条）；不把 mock 购票页改成会自动下单（牵动 M2-35 且无对账价值）；不做"转化率看板"（那是 M3 看板三页那格）；不修 `is_bot`/评分层（①②两条都不依赖它，`valid_pv` 的临时口径已在 6.4 钉好）；不做对账结果的持久化归档（`bench/reports/` 只装压测存档，对账输出以脚本运行结果为准，随 ITER 引用）。

**M3-04 已收口（2026-10-11）**：`scripts/accept-m3-04.sh` **78/78**。**主组两条判定式**（20 击 → 明细 → 回传 → 用抓到的 `xy_click_id` 下 15 单）：**① 差异 0.00%**（明细 pv=20 vs 平台 Σpv=20）、**② 命中率 100.00%**（订单 15、命中 15、缺失 0），退出码 0；recon 读到的三个基数与脚本手算的逐个相同，不给窗口时它按明细自推的区间与脚本推的一致。**负向样本两组**：② 侧注入 3 条引用不存在 `click_id` 的订单 → 报出 `missing=3 / join=FAIL / 退出码 1`，`--keep-bad` 时退出码回 0 而数字照报，主组同时刻不受影响；① 侧**本轮自己补的一组**（"只点击、不回传"的另一批活动）→ `detail_pv=5 / platform_pv=0 / 差异 100.00% / transport=FAIL`，且 ② 判 `n/a`——**"没数"与"丢数"是两个不同的值**，不然真丢数会伪装成无害的 `n/a`。阈值旋钮被证明是活的：`--max-diff 0` 只红 ①、`--min-join 101` 只红 ②、放宽回 5%/50% 就 PASS。订单侧：15 单入账、重发 3 笔被吸收（`times_submitted=2`、行数不变）、坏输入 2 笔 400 且不留半行。**回归里抓出两处真问题**（都不在产品代码）：`accept-m3-03` 的 P0 把前置写成"库里恰好 2 张表"而被新表撞红（改成按表名要），以及活体测试用写死的自定义短码导致"上一轮被打断就再也跑不回来"（`@BeforeEach` 先扫本类自己的码）——详见 ITER-M3 §4。回归：`make verify` **156/0/0/0**、`accept-m3-03` 37/37、`accept-m3-02` 62/62、`m1-04` 7、`m1-06` 33、`m1-07` 15、`m1-09` 16、`m1-10` 19、`m2-13` 19、`m2-32` 19、`m2-35` 87、`make demo` 全通。

**M3-05…M3-08 已收口（2026-10-11）**：`scripts/accept-m3-05.sh` **55/55** —— 五条只读接口的每个字段与脚本**独立实现**的一份直查算式逐个相等（**0 容差，69 个字段**），DESIGN 第十章那句"看板三页面数据与 ClickHouse 直查一致"由此变成可重跑的东西。铺的数据是**形状已知**的：两条不同渠道的链接（一条真微信 UA、一条默认 UA）+ 一条 `rate_limit_per_minute=5` 的链接点 8 次，**让 `blocked_pv=3` 第一次以非零值出现在接口返回里**（此前所有实测它都是 0，"漏斗有第三档"一直只是代码上的事实）。取数代价那个数字也给出了结论：24h/7d 每条 **7–35ms**，30d 每条 **3.0–4.5s**（读 48,853,697 行）⇒ 不动 MV、不加列，**窗口上限压到 8 天**，长窗口交给 `recon-attribution.sh`。接口从四条补成**五条**是做到页面时才发现的（双指标要租户时间序列，与渠道分布不是同一条 `GROUP BY` 轴），加上路由键形态写错——两处 `make verify` 与 curl 全绿、只有真开浏览器才看得见，于是"页面绑定的字段必须真的在响应里"这层形状契约被补进验收。回归：`make verify` **169 测 / 0 失败 / 0 错误 / 0 跳过**、旧验收 11 套全绿（`m3-02` 62、`m3-03` 37、`m3-04` 78、`m2-35` 87、`m1-09` 16 等）、`make demo` 全通。**M3 到这里只剩粗粒度列表里那一格运维演练：8.3 降级演练全表（M3-09）。**

**M3-10 多域名切换已演练通过（2026-10-11，`scripts/drill-domain-switch.sh` 13/13）**：admin 创建链接时按 `status=0 → weight DESC → domain ASC` 从 `xsl_base.domain_pool` 选生效域（30s 缓存，平手必须排死，否则"切到了哪个域"成了随机事件、演练无法断言），把某个域置 `status=2` 就是一次切域：**31 秒后**新链接换到下一个域（这个界就是缓存 TTL，不是链路慢；要立刻定住域走 `XSL_DOMAIN_POOL=false`），而**存量码在旧域仍能跳转**。字典库读不到就退回 `base-domain` 并计 `xsl_domain_pool_total{result=fallback}`，连接超时压到 1.5s——Hikari 默认 30s 会把"读一次域名"变成把创建请求拖住 30 秒（活体测试跑出 30.3s 才暴露）。

**M3-05…M3-08 的落地口径（看板三页，写代码之前定下来；DESIGN 1.2/3.2/3.4/6.4/7.1/7.2/第十章同步回写）**——这一格是 M3 剩下的最后一块，也是仓库里第一次出现前端。开工前把三件事说死：数据从哪来、谁能看到、三页各自到底长什么样。

1. **交付切四步**（M3-05 后端接口 → M3-06 工程与挂载 → M3-07 三页 → M3-08 收口）。切开的理由：接口形状定了之后页面才有东西可渲染，而"接口对但页面错"必须能被单独抓到——所以 M3-07 的验收要求用真实浏览器读页面上的数字。
2. **看板的数据面只走 `/api/v1/stats/**`**。这条前缀 nginx 现在就直接反代到 consumer、**不经网关 HMAC**（DESIGN 7.1 的既有事实，不是本期新增的口子），所以浏览器里不放任何密钥——那把 api_key 本身就是 HMAC 的 secret，进浏览器等于把它公开。代价**照实写进 DESIGN 第十章风险表**：只读聚合数字对任何能访问入口的人都是开放的。
3. **"链接列表"这一页列的是"有点击的短码"，不是管理面台账**。字段全部来自 ClickHouse 按 `short_code` 聚合（短码、活动、渠道、推广者、pv/uv/有效/被拦、最近点击时间）。`origin_url`、`status`、`expire_time` 这些管理面字段**不在本期**：拿它们就要求 HMAC，而第 2 条已经决定浏览器不持密钥。页面标题直接写"点击统计列表"，不让主办方误以为这是台账。
4. **租户 id 放在 URL 路径里**（`/api/v1/stats/tenants/{tenantId}/links`），不放在请求头里。两个理由：验收脚本能直接 curl 逐字段比对；以及把"谁在看谁的数据"摆在明面上——换成头就容易被误读成"有租户隔离"，而它并不是。**症状写清楚**：改路径里的数字就能看到别的主办方的聚合数，这是第 2 条那条边界的直接体现，不是新漏洞，也不在本期修。
5. **不做"看板专用 token"**。一把只能挡"随便翻"的凭据，进了浏览器仍然是明文，加它只会让人以为隔离已经做了。真正的隔离等 DESIGN 9.4 欠着的 key/secret 分离一起做（解除条件写在 ITER-M3 §6）。
6. **转化那一档本期不做**。ClickHouse 里一列订单数据都没有（订单在 MySQL `xsl_mock.mock_order`，只有 mock 自己的列表接口和对账脚本读它），所以漏斗画 **渠道 → 点击 →（有效 / 被频控拦下）** 三档，转化那一格留空并标注"未接入"。为什么不用"跨源现读"糊上去：6.4 定的是"看板查询统一走 ClickHouse"，一侧走 MySQL 就会带来两个不同的延迟与时钟，页面上的转化率会自己跳动——那种数字比空着更糟。
7. **取数从明细 `click_event FINAL` 起步，由实测数字决定要不要换聚合表**（本项目第四次"由数字决定"，前三次是 M3-01 的跳数索引、M3-03 的 `valid_pv` 列、M3-02 的 batch 大小）。换不了聚合表的硬理由是：`click_stat_minute` **没有"被频控拦下"这一档**（列只有 `pv/uv_state/bot_pv/wx_pv/douyin_pv/browser_pv`），而漏斗必须有它。没测过的部分是**窗口大小**：M3-03 实测的是 10 分钟窗口 5ms，看板默认要给 24h、还要能拉 7d——所以 M3-05 的验收必须实测这两个窗口的 read_rows/耗时并写进 ITER §3。换聚合表的代价同时写明：要重建 MV 加列、动老卷，且 `bot_pv` 那一档仍是恒 0。**实测结论（2026-10-11，数字见 ITER-M3 §3）**：24h 与 7d 档每条 10–35ms（直查同形态读 6,841 行 / 575 KB / 10–22ms）⇒ **不换聚合表、不加列、不动 MV**；但 30d 档读 48,853,697 行、单条 3.0–4.5s，一页两三句就是十几秒 ⇒ 收口时把**窗口上限压到 8 天**（`xsl.stats.max-window-days:8`，超了 400），长窗口交给 `recon-attribution.sh` 那条离线路。
8. **UV 用 `uniqExact` 精确值**。6.4 原文把"聚合表那条 `uniqState` 近似串"留给看板，但第 7 条决定走明细 ⇒ 看板拿到的是精确去重，附录 A 风险 #5 那句"±0.81% 误差"本期不适用于看板页面（DESIGN 要改注，别让读者以为页面上的 UV 是估的）。
9. **前端依赖收敛：Vue 3 + Vite + ECharts，不引 Element Plus，也不引 TypeScript 与 Pinia**。三页的实质是"一张表 + 两张图 + 几个卡片"，Element Plus 是为"管理后台全家桶"准备的，而 DESIGN 144 那行自己就写着"不做管理后台全家桶"；三页各自取数、没有跨页共享状态，Pinia 用不上；TS 在这个体量下多出来的是配置面而不是安全性。这条要回改 DESIGN 1.2/3.2 的技术栈行。
10. **部署形态不是新容器**。`sl-console-web/dist` 由既有 nginx 静态托管，另加一个 `listen 8081` 的 server block 做同源反代（`/` → dist + `try_files ... /index.html`、`/api/v1/stats/` → consumer:8040），浏览器只跟一个 origin 说话所以不需要 CORS。为什么是 8081 而不是 80：80 端口上 `xy*.test` 的 `/` 已经被 jump 占了（短码就在根路径），看板抢不到前缀。**`dist/` 不入库**，`make web` 现场构建；nginx 挂一个不存在的目录会得到空站而且报错在容器日志里（与 `tls` 那条同款坑），所以 `make up` 的前置要检查 dist 存在并直接提示 `make web`。
11. **接口是五条不是四条**（M3-07 做页面时补的）：双指标页要的是**租户维度的时间序列**，与渠道漏斗的 `GROUP BY` 轴不同，借渠道接口的结果就等于让页面显示「这个窗口里没有点击」而后端一切正常。少写这一条的原因很朴素：四条是**从页面倒推**列的，可页面还没写过。**教训：接口清单要在真正接上消费者之后复核一遍**，否则"看起来齐了"的清单会缺在最贵的位置上。

12. **出口判定式**（把 DESIGN 第十章"看板三页面数据与 ClickHouse 直查一致"这句话变成能测的东西）：`accept-m3-05.sh` 铺一批真点击 → 调四条接口 → **每个字段与脚本自己直查 CH 的同一个左闭右开窗口逐个相等，0 容差**（同源同表才允许 0 容差，这个"允许 0 容差"本身就是这件事有意义的证据）→ 再用真实浏览器读页面上渲染的数字核对一遍。空数据窗口要有明确空态。

**看板明确不做**：不做管理后台全家桶（创建/编辑/封禁仍在 API 与脚本里，看板只读）；不做真转化与跨源 join（第 6 条）；不做看板 token 与前端登录页（第 5 条）；不引 Element Plus/TypeScript/Pinia（第 9 条）；不做图表交互全家桶（缩放、下钻到明细行、导出 PNG/CSV）、不做暗色主题、不做移动端像素级适配（能看即可）；不加 Redis/CH 缓存层或看板专用物化视图（先看第 7 条那个实测数字，别预防性优化）；不改 `click_stat_minute` 与 MV 结构（除非第 7 条的实测数字逼着改，届时按 M3-01 的老流程走幂等迁移）。

**M3-09/M3-10 的落地口径（演练两格，写脚本之前定下来；DESIGN 8.3/9.5/第十章同步回写）**——这两格的价值不在"新功能"，而在于把文档里那句「全部可在单机实测」真的兑现成六个能跑的东西，以及把 9.5 那句"完整实现"从愿望变成事实。

1. **演练断言的是行为，不是"容器停了"**：每一行都要有持续流量（后台循环打 `/s/{code}`），在停的那一刻继续打，看的是成功率与错误码分布。只断言"`docker stop` 返回 0"等于什么都没测。
2. **顺序有讲究**：先影响面小的（`kill jump-1` → CH → Kafka → Redis → MySQL），最后做 restore-verify（它新建容器与影子表）。**Redis 停过之后 jump 的订阅要 12–18s 才回来**（M3-02 量过），所以任何依赖广播的断言必须排在"Redis 已恢复且探测到两实例都收到"之后——这是 m3-02 复跑红三项那条教训的直接搬运，不做探测就会拿到假回归。
3. **CH 挂这一行要同时看两件事**：① 五条 stats 接口必须 **503**，看板要显示"数据源不可用"而不是"这个窗口里没有点击"（M3-05 把 400/503 分开就是为这一刻能区分，`StatsBackendException` 那条分支此前从没被测过）；② consumer 写 CH 失败**不提交 offset** ⇒ Kafka 堆积、恢复后 lag 归零且事件总数不少（这是 M2-21 那个 `wait_for_async_insert=1` 决定的现证）。
4. **MySQL 挂这一行量的是"哪些还能用"**：跳转（热码走缓存）仍 302、`POST /api/v1/links` 必须失败**且失败得可见**（4xx/5xx，不能静默返回一个没落库的链接）、`stats` 仍可读（ClickHouse 独立）。DESIGN 3.3 那句"管理面熔断、数据面存活"就靠这三条断言撑着。
5. **Kafka 挂这一行走 WAL**：停期间点击照常 302，事件落 jump 本地 WAL；恢复后重放，断言明细行数 == 发出的点击数（不重不丢），恢复耗时取"lag 归零"那一刻。
6. **恢复时间一律记数**，不写"很快"：每条打印 `stop → 达标` 的秒数并落进存档。
7. **备份与恢复：`make backup` 落 `deploy/backup/<ts>/`（gitignore，不进仓库）；`make restore-verify` 是"读回验证"，不是"把生产卷洗一遍"**——MySQL 侧起一次性容器（独立卷、不映射端口、`--rm`）把 dump source 进去核对表数与关键行数；CH 侧 `BACKUP TABLE … TO File(…)` 再 `RESTORE … AS xsl.click_event_vfy` 比 count 后 DROP 影子表。两边都不碰现有数据（这是破坏性最小的能测法）。每日 cron 的"第二磁盘"是运维动作，脚本只保证一件事：**备份文件真能读回来**。
8. **`domain_pool` 怎么读要先验证**：ShardingSphere 那层能不能直接 `SELECT … FROM xsl_base.domain_pool`（限定名跨库）先试一把；不行就照 sl-mock 的现成做法给 admin 配一条**独立 plain JDBC 数据源**只读 `xsl_base`（不经分片）。选域只在**创建链接时**读（带一个短 TTL 缓存），不给跳转路径加任何往返。
9. **选域规则要确定**：seed 里三个域 `weight` 都是 100，没有平手规则就等于随机选，"切了域"这件事不可断言。规则写死 `status=0` → `weight DESC` → `domain ASC`，并在演练里断言两次选择结果相同。
10. **被封禁的旧域不下线**：`status=2` 的语义是"不再用它生成新短链"，**存量码在旧域必须仍能跳转**——否则一次切换就把已发出去的宣传物料全作废。nginx 三个 `server_name` 都指向同一 upstream，"封禁"绝不等于从 nginx 里删域，这条要单独断言。

**这两格明确不做**：不接真实 Grafana webhook 与自动告警判定（演练用一条 `UPDATE` 模拟触发，"点击率断崖 + 渠道巡检"留在 9.5 推演）；不做备案、养域、新域两周灰度那套运营策略；不做异地备份（"第二磁盘"就是本机另一目录）；不给 `backup` 装 cron（调度是运维的事，脚本只保证能读回来）；不做域名池自动扩缩与自动回切；不改 nginx 的多域拓扑（三域等价服务是 M1-10 就定下来的事实）。

**P2**：裂变批量签发、模型化反作弊、开源化（CI Badge、seed 数据脱敏、license）。

---

## 三、工程约定（跨里程碑生效）

- 版本钉选：Boot 3.3.x、ShardingSphere-JDBC 5.5.x（Boot3 兼容需开工首日验证，风险高则降为"双库手工路由 + 文档推演"，4.1 设计价值不受损）、Guava 33（Bloom）、Vavr 不引入。
- 测试分层（m1 收口后按现实回写）：sl-common 属性测试（jqwik）；服务层活体测试**直连本机 compose 中间件**（MySQL 3307 / Redis 6380 / Kafka 9094 / CH 8123，端口不可达则 `assumeTrue` 跳过）——没有引入 Testcontainers，因为 compose 栈本身就是被测拓扑，再起一套容器等于测两个环境；
  任务级 E2E 用 bash 脚本，落点 **`scripts/accept-m1-NN.sh`**（编号=本计划任务号，可被文档与 ITER 直接引用），而不是原定的 `bench/e2e/`——`bench/` 只装压测脚本与 `reports/` 存档，两类凭证的时效性不同（验收随契约演进重跑、压测存档只增不改），混在一层会互相污染命名。`make demo` 即 M1-12 产物。
- **测试类命名约定**（后缀即语义，新增测试沿用）：`*Test` 纯单元（不碰外部依赖）；`*PropertyTest` jqwik 属性测试；`*LiveTest` 活体依赖（不可达自动跳过）；`*IntegrationTest` 活体 + Spring 上下文；`*VerificationTest` 一次性验证型（分片路由这类"跑一遍拿证据"的用例）。存量用例里曾有的一处不符（`ShortCodePoolServiceTest` 实际是活体用例却没带 `Live` 后缀）**已于 M2-15 改名收口**为 `ShortCodePoolServiceLiveTest`——当时不是一次性全仓改名，而是等真正要动这个文件的迭代顺路做，并同步 `ITER-M1` 的凭证引用（改名会让历史引用断链，所以引用处留了原名注记）。规则照旧：**命名例外不做专项清理，下次改动该文件时顺路消除并在当期 ITER 记一行**。
- Commit 纪律：每任务一 squash 提交，附验收命令输出；里程碑 tag；`bench/reports/` 只增不改。
- **回归纪律（M1-12 收口补，跨里程碑生效）**：凡改公共契约（跳转 Location、ClickEvent 字段、`route_json` 结构）的任务，收口前必须重跑受影响链路的全部旧验收脚本，并把复跑结果写进当期 ITER 的实测表。起因：M1-09 给 jump 接归因拼接后没重跑 M1-07，那条"Location 逐字符等于 origin_url"当场失效却仍以绿记录在册，直到 M1-10 容器化整套复跑才暴露（ITER-M1 §5）。
- **关键问题沉淀（工作方式，不是交付物）**：每个里程碑收口时，从当期 ITER §4/§5 的流水里把"符合门槛"的条目提升到 `docs/PROBLEMS_AND_SOLUTIONS.md`——门槛是"根因非显然 / 影响架构与契约 / 会复发 / 看起来成功但结论是错的"四类，笔误与一次性环境抖动不提升。**该文件是本机维护的复盘笔记，已写进 .gitignore，不入库也不推送**（内容形态偏个人叙述，不适合作为公开仓库的一部分）；仓库内承担"为什么这么设计、当时怎么错"职责的可交付凭证仍是 ITER §4/§5。
- **迭代说明文档（硬性要求）**：每个里程碑收口必须产出一份 `docs/iterations/ITER-<里程碑>.md`（模板 `ITER-TEMPLATE.md`），内容含：本期做了什么/任务完成对照表（含砍掉与顺延项及原因）/实测数字与 `bench/` 凭证链接/对 DESIGN 的偏差与回写/下一期待办。**里程碑中途发生范围或设计变更时，先增改当期迭代文档再动代码**；文档与 tag 一起提交，缺一不算收口。
- **凭据纪律（m1 收口补，跨里程碑生效）**：仓库内不得出现任何可用凭据的明文。开发栈口令一律放 `deploy/compose/.env`（gitignore，`make devenv` 生成），compose 用 `${VAR:?}` 强制注入、不给默认值；脚本经 `scripts/lib-devenv.sh` 取值，活体测试读环境变量（缺则跳过，绝不静默回落成别的口令）。中间件账号用幂等脚本在运行时建（`make ch-init`），不要写进只能落明文的配置文件（原 ClickHouse 账号 XML `deploy/compose/init/clickhouse/users.d/xsl-app-user.xml` 已删除，仓库里不留痕）。**例外**：`xy-key-alice-001` 这类 mock 租户 api_key 是设计内的演示密钥（DESIGN 9.4），不是环境凭据，留在 seed 里；等 M2 落地 key/secret 分离与轮换后再一并参数化。
- 当前仓库尚非 git 库：M1-00 第一步即 `git init`（属计划内动作，执行前不再另行确认）。
