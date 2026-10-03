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
outbox 同步器 + `version` 强校验通道｜归因回传 mock 宣发平台（batch_id + 校验和幂等）｜**Vue 3 看板**：链接列表 / 渠道漏斗 / 双指标三页（Element Plus + ECharts，同源反代）｜8.3 降级演练全表（逐条 docker stop 存档）｜多域名切换演练｜端到端对账脚本 <2%。
出口标准 + tag `m2`/`m3` 同 M1-12 模式。

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
