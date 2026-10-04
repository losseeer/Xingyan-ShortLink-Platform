# 迭代说明：M2（进行中）

**日期**：2026-10-01 开工　**tag**：待定（m2，仅本地）　**周期**：起止统计中（计划见 DEVELOPMENT_PLAN M2 段）

## 1. 本期目标回顾

动态路由与防刷（DEVELOPMENT_PLAN M2 粗粒度列表）。开工顺序调整过一次：**先做 M2-00（M1-11 压测暴露的治理项）**，再进防刷主线，理由是治理结论会改变后面所有基准数字的可信度。

## 2. 任务完成对照

| 任务 | 计划 | 实际状态 | 说明（砍掉/顺延/新增的原因） |
|---|---|---|---|
| M2-00 资源预算重排 + 跳转 P99 治理 | ➕ 新增（来自 ITER-M1 §6） | ✅ 完成（2026-10-03） | 起点是 M1-11 的四个悬案：200 连接 P99 52.6–74.2ms、jump RSS 顶在 502–509MiB/512MiB、ClickHouse 2GiB 下批量插入撞 Code 241、签发 TPS 被压测客户端限住。收口结论：①「P99 未达标」是**判定负载用错**，在目标负载上达标（§3 首行）；② 尾巴归因到**事件链抢占 CPU**，GC 假设已证伪；③ jump `mem_limit` 512m→768m 作稳定性决定；④ 测量工具与纪律修了五处（§4）|
| M2-01 事件链批量化（CH 批量写 + producer/consumer 调参） | ➕ 新增（由 M2-00 归因引出） | **进行中**（2026-10-03 开工，细化为 M2-20…M2-24） | 细表在 [DEVELOPMENT_PLAN](../DEVELOPMENT_PLAN.md)「二-A」。现状三处限制：topic **1 分区**（消费并行度上限=1）、每批 500 行一次**同步** HTTP POST、producer 无 linger/batch 调参。目标把 chain-live 上限从 ~14k 抬到 ≥20k 且 P99@目标负载不退化。**一次只动一个杠杆并单独存档**（M2-00 的教训） |
| 防刷主线（频控 Lua + `access_limit` 原子扣减 + 对账） | ✅ M2 计划内 | **✅ 收口（2026-10-03）** | M2-10…M2-15 全部完成，细表在 [DEVELOPMENT_PLAN](../DEVELOPMENT_PLAN.md)「二-A」。顺序是用户定的：M2-00 收口后直接开这条，M2-01 事件链批量化靠后 |
| M2-10 口径先于代码（任务表 + DESIGN 回写） | ✅ 计划内（细化时新增） | ✅ 完成（2026-10-03，`9abec2e`） | 四处口径修正先落 DESIGN v2.3.0：频控提到扣减之前、本期做固定窗口而非"假滑动"、IP 只信 `X-Real-IP`、对账落管理面且快照独立成列 |
| M2-11 频控 Lua + 429 + 降级 | ✅ 计划内 | ✅ 完成（2026-10-03，`c775781`） | `risk/RateLimiter`（单脚本 INCR+首次 EXPIRE）+ `risk/ClientIdentity`（IP 口径统一）；命中 429 + `Retry-After`、不扣配额、事件仍入库并记 `risk_score=25`；Redis 挂 → 进程内计数不退服 |
| M2-12 access_limit 对账与冷重建 | ✅ 计划内 | ✅ 完成（2026-10-03，`4aa2d9c`） | `link_route.access_used/_at` 快照列（幂等 ALTER，老卷 `make db-init` 即补）+ sl-admin 每 60s 对账 + jump 冷重建按「上限−已用−缓冲」播种 |
| M2-13 端到端验收脚本 | ✅ 计划内 | ✅ 完成（17/17） | `scripts/accept-m2-13.sh` A–H 段（含伪造 XFF 不可绕、丢键后不超放）；旧脚本清理补 `sl:rl:*`，打流类 fixture 显式给阈值 |
| M2-14 频控 Lua 基线 + 跳转不退化 | ✅ 计划内 | ✅ 完成（2026-10-03） | DESIGN 8.1-A 最后一项"未测"清零：单连接 27,803/s、10 连接 166,852/s、50 连接 211,894/s；跳转侧 P99@目标负载复跑 5.36–20.61ms 仍达标 |
| M2-15 契约回归 + 顺路改名 + ITER 回填 | ✅ 计划内 | ✅ 完成（2026-10-03） | 五套旧验收在新 build 上全绿（§3 末行）；两处 M1 遗留改名做掉（`sharding-jump.yaml`→`sharding.yaml`、`ShortCodePoolServiceTest`→`ShortCodePoolServiceLiveTest`，含 `application.yml`/活体测试/`accept-m1-07.sh` 注释与 ITER-M1、DEVELOPMENT_PLAN 的引用同步） |
| M2-22 分区与消费并行度 | ✅ 计划内 | ✅ 完成（2026-10-03） | topic 1→4 分区（幂等逻辑进 `deploy/compose/init/kafka/ensure-topic.sh`）、consumer `concurrency` 走 compose 插值便于调档。追赶 30,563 → 41,223 events/s（并发 1→4），但 **2→4 只涨 1.3%** ⇒ 瓶颈已移到 ClickHouse 侧；`event_id` 幂等与 0 丢失在 4 分区下复验通过 |
| M2-22 中途引入并已修的回归 | — | ✅ 已修（`aaf5a42`） | `--alter --partitions` 在已达标时退出 1 → kafka-init 失败 → `service_completed_successfully` 卡死 consumer；连带让三档测量跑成空数（详见 §4） |
| M2-23 复采与判定 | ✅ 计划内 | ✅ 完成（2026-10-03），**判定：部分达标** | chain-live 平顶 12,185 → **18,193 QPS（+49%）**，但**没到 ≥20k 的目标（差 9%）**，照实记未达；事件链 CPU 合计 2.17 → **0.92 核**（CH 1.32→0.43、kafka 0.60→0.38、consumer 0.25→0.11），VM 内总占用 10.4 → 5.3 核 ⇒ 上限归属改变；目标负载档延迟三次独立复采 P99 1.53／2.08／2.56ms 一致且全曲线单调 |
| M2-24 设计回写与收口 | ✅ 计划内 | ✅ 完成（2026-10-03） | DESIGN 1.3 延迟行、8.1-A 吞吐/投递行与第 4 条"上限归属"改写（那条结论当时成立、被本次改动改变，按时间线写清）；6.3 补批量语义与两条不变量（`wait_for_async_insert=1`、码内有序）；回归见 §3 末行 |
| 其余 M2 项（Cuckoo、route_rule 双轨、UA 引擎、中间页、评分、配额、Sentinel） | ✅ M2 计划内 | 未开始 | 进入前按 M1 格式细化任务表 |
| 动态路由主线（route_rule 双轨 + UA 引擎 + 中间页） | ✅ M2 计划内 | **进行中**（2026-10-03 开工，细化为 M2-30…M2-36） | 细表在 [DEVELOPMENT_PLAN](../DEVELOPMENT_PLAN.md)「二-B」。摸底先把三处"文档说做了、代码里没有"记进 §4：`xsl.jump.mode` 只是注释、`route_rule` Java 零引用、outbox 只有写方没有同步方 |
| M2-30 口径先于代码（六条实现口径 + 任务表） | ✅ 计划内（细化时新增） | ✅ 完成（2026-10-03，`e583728`） | DESIGN 6.2 增"实现口径"六条（特征表自维护、未知不匹配、真开关、`wx_urllink` 走 mock provider、中间页由 jump 返 HTML、mock 购票页 nginx 静态）；DEVELOPMENT_PLAN 新增「二-B」任务表 |
| M2-31 route_rule 双轨：校验器 + 编译器 + 规则 API | ✅ 计划内 | ✅ 完成（2026-10-04） | `sl-common` 加 `RouteRule/UaContainer/UaDevice`，`RouteConfig` 加 `rules`；`sl-admin` 加 `RouteRuleService`（校验 + 按 priority 编译）与 `PUT /links/{code}/rules`，规则与 `short_link` 同事务写 `route_rule`；DDL 补 `scheme_url/fallback_url`、`target_url` 改可空、`uk_code_priority`。jump 仍只读 `link_route`（气分离）。验证见 §3「M2-31 双轨」行 |
| M2-32 UA/容器特征引擎 | ✅ 计划内 | ✅ 完成（2026-10-04） | `sl-common` 加 `UaFeature/UaClassifier`（容器与设备两次独立判定、首个子串命中即定类、配错的表在构造期抛异常）；特征表落在 `sl-jump/application.yml` 的 `xsl.jump.ua`（代码里不留默认表）；jump 把分类结果写进 `ClickEvent.device_type/os`（此前恒为空串）。**顺带把 M2-31 用的容器 key 从 `micromessenger` 改成 `wechat`**，让规则条件、事件取值、CH MV 字面量三处共用一套词汇表（§4）。验证见 §3 两行 |
| M2-33 一级决策 + 中间页 + 真回退开关 | ✅ 计划内 | ✅ 完成（2026-10-04） | 决策链抽成 `JumpChain`（抽象），`Direct302Resolver` / `DynamicRouteResolver` 两个 `JumpResolver` 实现由 `xsl.jump.mode` 条件装配选一（默认 `dynamic`）；四类 target → `h5`=302、`app_scheme`/`wx_urllink`/`intermediate`=200 中间页（`Cache-Control: no-store`）；`UrlLinkProvider` + `MockUrlLinkProvider`。**过程中补掉 compose 没透传 `XSL_JUMP_MODE` 这条假承诺**，并修了"数据面按数组顺序而不是 priority 取首条"的真 bug（§4）。验证见 §3 两行 |
| M2-34 mock 购票页（nginx 静态回显） | ✅ 计划内 | ✅ 完成（2026-10-04） | 新增 `nginx/html/mock-ticket.html` + `conf.d/30-mock-page.conf`：`mock.ticketsales.test` 在 :80 与 :443 都给出回显页（query 与 `#fragment` 由页内 JS 读，服务端回显 fragment 做不到）；本地自签证书由 `scripts/gen-mock-tls.sh` 幂等生成到 `deploy/compose/tls/`（不入库，`make tls` 已挂成 `up` 的前置）。`make demo` 新增 4b 段，跟着 302 真打一次终点并断言 200 |
| M2-35 UA 重放测试集（**M2 出口标准**） | ✅ 计划内 | ✅ 完成（2026-10-04） | `scripts/accept-m2-35.sh` **87/87**：15 条真实 UA 逐条断 target_type/响应形态/中间页里的 scheme·urlLink·引导地址/no-store，再叠跨维度 priority 排序（B）、全不命中落 origin（C）、`device_type/os` 入库（D）、跟着中间页打到 mock 购票页（E）、`XSL_JUMP_MODE=direct302` 的回退与复位（F，含容器 `printenv` 断言） |

## 3. 实测数字与凭证

| 指标 | 目标（DESIGN 1.3 / 8.1-A） | 实测 | 凭证 |
|---|---|---|---|
| 跳转 P99 **在目标负载下**（判定口径见 §4） | P50<10ms、P99<50ms @ ≥2000 QPS | **✅ 达标**：c=4 点 5,002 QPS（P50 0.646ms、P99 21.08ms），c=8/16 为 6,439/6,459 QPS（P99 23.76/37.74ms）。**c=32 起 P99 越线（116ms）且与 c=64（103ms）不单调**，这一段与饱和区一起作容量描述、不判 SLO；饱和段 128/200 连接 P99 137.41/310.96ms | `bench/reports/2026-10-03-1414-m2-00-jump-loadcurve.md`（头部含 env_snapshot） |
| 尾延迟归因（三层同时采样） | 说清尾巴在哪一层 | jump 自测处理 **0.717ms**（sum/count 精确均值，本轮 536,352 次服务端处理）对 nginx 侧 **rt p99=54ms／urt p99=54ms**（`urt/rt` 中位数与 p99 比值均 1.00、`uct≈0`）⇒ 时间全在上游应答里，而业务代码只占 0.7ms ⇒ 排队发生在 jump 进程内、handler 之外；线程 blocked=0 排除下游卡住 | `bench/reports/2026-10-03-1350-m2-00-jump-diagnosis-c200.md`（首份同名存档 1331 是脚本卡死留下的空文件，未提交即删除） |
| 容量上限归属 | 谁限制单机跳转 QPS | 全链路在跑 5,002–8,383 QPS；**停掉 consumer（ClickHouse 不再写入）后同一入口 24,525 QPS**。负载中逐容器 CPU：ClickHouse ≈1.9 核、nginx ≈1.4、jump ≈1.3×2、kafka ≈0.6 ⇒ 上限由事件链决定，不是数据面 | `bench/reports/2026-10-03-1418-m2-00-jump-attribution-c64.md`、`bench/reports/2026-10-01-1336-m1-11-kafka.md` |
| 稳定性隐患消除 | 容器不被 OOM-kill | jump `mem_limit` 512m→768m：常驻长期 473–509MiB/512MiB 贴顶；**对 P99 无改善**（GC/内存两条线都已实测排除），提额只解决被 kill 风险 | `deploy/compose/docker-compose.yml` jump 段注释 + `bench/reports/2026-10-03-1414-m2-00-jump-loadcurve.md` |
| 防刷端到端验收（频控 + 配额 + 降级） | 频控拦得住、绕不过、不烧配额、丢了键不超放 | ✅ **17/17**（`scripts/accept-m2-13.sh` A–H）：阈值 3 → 恰好放行 3 次、越界 429 带 `Retry-After`；伪造 `X-Forwarded-For`/`X-Real-IP` 4 次**全 429**（绕不过）；同 IP 换 code、同 code 换 IP 互不影响；被拦请求**不消耗配额**（quota=50、放行 3 → 余数 47）；被拦的击以 `risk_score=25` 落 CH（8 拦 8 放，行数与脚本内计数一致）；删 `sl:cnt` 模拟丢键后只再放 2 次就 410（M1 行为是在这里重新放出 5 次满额）；`docker stop xsl-redis-1` 后冷码仍 302、进程内计数继续生效、`mode=local` 指标增量 13 | 脚本输出即凭证；对账环节实测 `link_route.access_used=3` 与 Redis 权威值一致 |
| 防刷收口全量回归（M2-15） | 动了契约就要重跑旧验收 | ✅ **0 回归**（2026-10-03 17:1x，同一台机器、含频控的新 build）：`make verify` 全仓 **87 测 / 0 失败 / 0 错误 / 0 跳过**；`accept-m1-04` **7/7**、`accept-m1-06` **21/21**（本期给它加了 `rate_limit_per_minute` 契约）、`accept-m1-07` **15/15**、`accept-m1-09` **14/14**（1204 击端到端一致 + WAL 兜底）、`accept-m1-10` **19/19**（滚动重启窗口 5,632 击全 302／0×5xx，频控在被测路径里）、`accept-m2-13` **17/17** | 脚本输出即凭证；契约变更清单＝429 状态码 + `rate_limit_per_minute`（API/DB/route_json 三处）+ ipHash 取值口径 + `risk_score` 语义 |
| M2-01 **改前**基线（M2-20，三份同档） | 建立可对照的"改前"，之后一次只动一个杠杆 | chain-live 平顶 **12,185 QPS**（c=64；c=128 12,035、c=200 11,494 已不再涨）；jump 自测均值随负载 0.387→5.024ms；Kafka 生产 **18,404 events/s**、停 consumer 积压 828,170 后 **28s 追平（29,578 events/s）**、对账 diff=0；负载中逐容器 CPU：**jump 149%+150%、ClickHouse 132%、nginx 117%、kafka 60%、redis 25%、consumer 25%**（合计 ≈10.4 核 vs Docker VM 的 10 核 ⇒ 已顶到 CPU 墙）。与 M1-11 那份（生产 24,544、追赶 33,469）差 15–25%，属环境差不是退化 | `bench/reports/2026-10-03-1839-m2-00-jump-loadcurve.md`、`-1845-m1-11-kafka.md`、`-1847-m2-00-jump-attribution-c64.md` |
| M2-21 写入批量化 A/B（同机同档） | 每 5,000 行的 POST 次数下降 ≥5×、parts 不炸、0 丢失 | 追赶：**29,578 → 37,421 events/s（+26.5%）**（828,170/28s → 785,852/21s）；写入请求：785,852 行只发 **175 次 POST**（均 4,491 行/次），改前按 500 行/批算同量需 ~1,572 次 ⇒ **≈9× 下降**；对账 `produced = ch_delta`（diff=0）、失败 POST=0；CH `system.parts` 10 个 active / 34.27M 行、`system.merges`=0。生产速率 17,463/s vs 基线 18,404/s（−5%，环境噪声）。**归因粒度要照实说**：M2-21 这一格本身定义时就含 consumer 批次、`async_insert`、producer 攒批三处改动，所以 +26.5% 属于"写入侧批量化"这个整体，不是单根杠杆 | 消费者指标 `xsl_consumer_ch_posts_total` / `xsl_consumer_ch_rows_total`（本轮新增）+ 本轮 kafka bench 存档 |
| M2-22 并行度 A/B（同场三档） | 追赶速率随并行度的变化 + 幂等不破 | 并发 **1 → 30,563**、**2 → 40,709（+33%）**、**4 → 41,223 events/s（再 +1.3%）**：收益在两档后基本抹平 ⇒ 剩余瓶颈在 ClickHouse 写入/合并侧，不在 Kafka 消费侧；生产速率同步升到 22,413／24,426／24,734 events/s；三档对账 **丢失率均为 0.0000%**、`make verify` 87 测（含 4 分区下 `event_id` 双投递 FINAL 恰 2）+ `accept-m1-09` 14/14 | `bench/reports/2026-10-03-1946-m1-11-kafka.md`、`-1948-`、`-1950-` |
| M2-24 事件链改动的回归 | 动了链路就要重跑 | ✅ **0 回归**（2026-10-03 20:0x，4 分区 + 批量写入的 build）：`make verify` 全仓 **87 测 / 0 失败 / 0 跳过**（含 4 分区下 `event_id` 双投递 FINAL 恰 2）；`accept-m1-09` **14/14**（1204 击端到端一致 + Kafka pause 走 WAL + 恢复重放 0 丢失）、`accept-m1-10` **19/19**（滚动重启窗口 5,632 击全 302／0×5xx）、`accept-m2-13` **17/17**（频控与配额未受批次改动影响，Redis 停机降级仍成立） | 脚本输出即凭证；三段 kafka 对账 produced=ch_delta（diff=0） |
| M2-23 事件链批量化后的复采 | chain-live 平顶 ≥20k 且 P99@目标负载不退化 | **吞吐未达标（照实记）**：平顶 12,185 → **18,193 QPS（+49%）**，距 ≥20k 还差 9%；c=200 档 17,582 QPS。延迟侧全部达标：c=4 三次独立复采 **8,483 QPS/1.53ms、7,344/2.08、6,697/2.56**，c=8 11,614/2.09、c=16 14,535/4.29，曲线单调（1.53→60.27ms 随并发升）。逐容器 CPU：jump 1.40+1.42、nginx 1.30、**ClickHouse 0.43**、kafka 0.38、redis 0.25、consumer 0.11 ⇒ 事件链从 2.17 核降到 **0.92 核**，VM 总占用 10.4 → 5.3 核 | `bench/reports/2026-10-03-1955-m2-00-jump-loadcurve.md`、`-2000-m2-00-jump-attribution-c64.md`、`-2001-`、`-2003-m2-00-jump-loadcurve.md` |
| 频控 Lua 基线（8.1-A 最后一项未测） |≥3 万次/s（目标未写连接数，见 §4） | 同一份脚本、`-P 1` 不流水线：**单连接 27,803/s**（avg 0.034ms、p99 0.095ms，比目标低 7%）、**10 连接 166,852/s**、**50 连接 211,894/s**（超 5.6–7.1 倍）。按"能否撑住跳转"判定：**达标**（jump 端到端上限 ~13.8k QPS 由两实例分摊） | `bench/reports/2026-10-03-1652-m2-14-ratelimit-lua.md` |
| 频控上线后跳转不退化 | P99<50ms @ ≥2000 QPS 维持 | ✅ 仍达标：40s 档复跑 4,536／5,389／5,958 QPS 时 P99 = **5.36／20.32／20.61ms**；jump 自测均值 0.124–0.247ms → **0.328–0.937ms**（每请求多一次 EVAL + 阈值判定），绝对值仍 <1ms、尾延迟构成未变 | `bench/reports/2026-10-03-1656-m2-00-jump-loadcurve.md`（同日 20s 档首跑见 `-1653-`，其 c=4 的 p99 50.79ms 是采样噪声，见 §4） |
| 配置变更后的旧验收复跑 | 动了 nginx/jump/compose 三处配置，不得回归既有链路 | ✅ **0 回归**（2026-10-03 14:38，同一台机器、改后重启的栈上）：`accept-m1-07.sh` **15/15**（302/404/403/410 语义 + 冷码回源二查命中缓存 db 计数 2.0→2.0）、`accept-m1-10.sh` **19/19**（三域同一 Location、双实例承载 32/32、滚动重启窗口 5,632 击全 302／**0×5xx**、管理面宕机不影响跳转、未登记 Host 444） | 脚本输出即凭证；改动面＝`nginx.conf` 的 `log_format`（新增 `rt/uct/urt`）、`sl-jump/application.yml`（开启 HTTP 分位数）、compose 的 jump `mem_limit` |

| M2-31 双轨落地的验证（本轮无性能数字，凭证是行为断言） | 规则要"存得下、编得出、清不掉" | `LinkServiceIntegrationTest` **15 测全绿**（本期新增 6 条：三规则乱序→有序、PUT 后 version+1 且元数据不变、PATCH 元数据不清空 rules、13 种非法规则逐个 400、跨租户 PUT→403 且零写痕、`app_scheme/wx_urllink` 的 `scheme_url/fallback_url` 双列往返）。`accept-m1-06` 从 21 项扩到 **33 项**（新增第 8 段：HTTP 上 `rules[]` 的 snake_case 绑定、响应/`route_json`/Redis 三处同源同序、`route_rule` 行数、非法规则不占码、空数组清空）；旧验收在**含 rules 的新 build** 上全绿：`make verify` 全仓 **90 测 / 0 失败**，`accept-m1-07` **15/15**、`accept-m1-09` **14/14**、`accept-m1-10` **19/19**、`accept-m2-13` **17/17**（`route_json` 多了 `rules` 键 ⇒ 按回归纪律把 jump 侧链路全部重跑） | 脚本输出即凭证；契约变更清单＝`route_json` 增 `rules`（无规则时为显式 `null`）+ 新增 `PUT /links/{code}/rules` + `POST /links` 受理 `rules[]` + `route_rule` 表首次被 Java 侧读写 |
| M2-32 UA 重放落库（`accept-m2-32.sh`） | ≥12 条真实 UA；CH 里 `device_type/os` 不再为空 | ✅ **19/19**：13 条真实 UA（微信 iPhone/安卓/Windows PC、抖音 iPhone/安卓、手机 QQ、QQ 浏览器、微博双端、支付宝、裸 Safari/Chrome、curl）经 nginx 全 302，事件链落库后**每个 `(device_type, os)` 对恰 1 行**；`device_type='' OR os=''` 的行 **0 个**；真·空 UA（`curl -H 'User-Agent;'`）落 `unknown/unknown` 而不是空串；聚合层 `sum(wx_pv, douyin_pv, browser_pv, pv)` = **3 / 2 / 9 / 14**，证明"规则条件值 = 事件取值 = MV 字面量"这套词汇表真的贯通到看板取数那一层 | `scripts/accept-m2-32.sh` 输出即凭证（fixture 与事件按 code 清理）；单测侧 `make verify` 全仓 **101 测 / 0 失败**（新增 `UaClassifierTest` 7 条语义与"配错即炸"、`UaFeatureTableTest` 3 条**跑 shipped 的 `application.yml`**、`Direct302ResolverTest` 1 条事件字段断言） |
| M2-32 之后跳转在目标负载不退化 | P99<50ms @ ≥2000 QPS 维持 | 同日同档两次 c=4/40s：**5,932 QPS／p99 17.42ms** 与 **5,194 QPS／p99 6.16ms**，jump 自测均值 **0.245ms（237,451 次）**／**0.288ms（207,805 次）** ⇒ 达标判定成立。但**两次 p99 差 2.8 倍，按 §4 的规则 p99 只作佐证、不作结论**；与昨日（2026-10-03 复采）0.193/0.215ms 的均值差 **不能归因给分类器**——跨天绝对值不可比，且今天两次跑的非 302=0、runnable/waiting 线程 36/30 vs 昨日 22/10 说明环境本身就不同。要判"分类器加了多少开销"得在同一天做 A/B（回退开关本身就能当对照组，M2-33 之后有真开关时再做） | `bench/reports/2026-10-04-1058-m2-00-jump-loadcurve.md`、`-1059-m2-00-jump-loadcurve.md`（头部含宿主负载 loadavg 7.52／内存 142M unused、容器内存与 CH 合并数） |
| M2-33 一级决策与回退开关（活体，手工验证） | 四类 target 各出对的响应形态；改一个 env 能退回纯 302 | 同一份规则（`wechat→app_scheme`、`douyin→wx_urllink`、`default→intermediate`）在部署栈上实测：**微信 iPhone = 200 + `Cache-Control: no-store`**，页面里是 `'xyapp://open/order?id=9'` 与拼了归因的兜底 H5；**抖音 = 200**，页面自动跳向 mock 串 `https://wxaurl.cn/xsl-mock/8a302edb`；**Windows Chrome = 200** 引导页（"本页不会自动跳转"）且按钮 href 带 `notice?channel_id=ch-m233`。然后 `XSL_JUMP_MODE=direct302 make up`：同样两击都变 **302、body=0B、无 cache-control 头**，容器里 `printenv XSL_JUMP_MODE=direct302`；复位 `make up` 后 env 回到 `dynamic`。**这一条的价值在 compose 透传之后才成立**（见 §4：透传前"改环境变量就能回退"在部署形态下是假的）。正式断言集落在 M2-35 | 本轮为手工序列（临时脚本 `/tmp`，未入库）；M2-35 把它写成 `accept-m2-35.sh` 的开关段后本行以脚本输出为准 |
| M2-33 之后的全量回归（dynamic 成默认形态） | 新默认值不得影响既有链路 | ✅ **0 回归**（2026-10-04 11:2x–11:4x，dynamic 模式的 build）：`make verify` 全仓 **125 测 / 0 失败 / 0 跳过**（sl-jump 30 → 54：`DynamicRouteResolverTest` 14 条覆盖四类 target + priority 与数组顺序无关 + geo 恒不匹配 + time_window 含首尾 + 命中但目标残缺的跳过、`IntermediatePageTest` 6 条含 `</script>` 注入尝试、`JumpControllerTest` 3 条钉 no-store、direct 侧 1 条"有规则也不出页面"）；六套旧验收全绿：`accept-m1-06` **33/33**、`accept-m1-07` **15/15**（无规则链接的 Location 与 M1 逐字符同形）、`accept-m1-09` **14/14**、`accept-m1-10` **19/19**、`accept-m2-13` **17/17**、`accept-m2-32` **19/19** | 脚本输出即凭证；契约变更清单＝`JumpDecision` 增 200 HTML 形态（响应头多 `Cache-Control: no-store`）+ `xsl.jump.mode` 从注释变成条件装配（默认 `direct302` → **`dynamic`**）+ compose 透传 `XSL_JUMP_MODE`/`XSL_JUMP_URLLINK_PROVIDER` + 新指标 `xsl_jump_route_target_total{kind}`、`xsl_jump_route_rule_unusable_total{type}` |
| M2-34 mock 购票页与 302 最后一里 | `curl --resolve` 能看到回显；`make demo` 的 302 终点从死链变成可看点 | curl 侧（4 项）：`http://mock.ticketsales.test/show/7?utm_source=sms` **200 / 5,588B**；`https://…/dl?channel_id=ch-x#seatB` **200**，协商到 **TLSv1.3**；同一 443 上未登记 Host `evil.example` → **空响应（444 掐断）**，域名白名单语义在 TLS 侧也成立；`/healthz` 200。端到端：短链 302 的 Location 是 `https://mock.ticketsales.test/show/m234?channel_id=…&xy_click_id=c365…`，跟着打完得到 **200**。回显逻辑用内置浏览器跑同一份 HTML 实测：`xy_click_id`/`channel_id`/`campaign_id`/`promoter_id`/`utm_source`/`utm_medium` 逐格填出值、非归因参数 `seat=B7` 落到"其它参数"、fragment 显示解码后的"站票"。**为什么换成本地静态服务器打开**：浏览器不认 `mock.ticketsales.test`（`.test` 是保留 TLD），而我不动用户机器的 `/etc/hosts`——所以浏览器这步验的是页面回显，nginx 的 http/443/白名单由 curl 覆盖。**未验证项**：座位按钮的点击交互（内置浏览器没有可附加视口，pointer 操作被拒），如实记为未跑；`make demo` 4b 段则把"跟到终点"固化成命令输出（终点 200、期望事件数 11 = ClickHouse 可见 11） | 加 443 与 mock server 之后六套旧验收仍全绿：`accept-m1-06` 33/33、`m1-07` 15/15、`m1-09` 14/14、**`m1-10` 19/19**（含"未登记 Host 444"那条，现在 TLS 侧也保持同一语义）、`m2-13` 17/17、`m2-32` 19/19。`scripts/demo.sh` 4b 段输出即凭证；curl/浏览器为手工序列，M2-35 会把终点可达性并入 UA 重放脚本 |
| M2-35 UA 重放测试集（M2 出口标准） | ≥12 条真实 UA 逐条断言命中与响应形态 + 开关验证；全绿即达成 M2 出口标准 | ✅ **87/87**（`scripts/accept-m2-35.sh`，一条命令跑完）。构成：A 段 15 条 UA（6 条规则覆盖四类 target，微信=iPhone/安卓/PC 三形态、抖音双端、QQ 双端、微博双端、支付宝、裸 Safari/Chrome、鸿蒙、curl），每条断 状态码 + `Cache-Control: no-store` + 页内标记（`xyapp://open/order?id=9`、`wxaurl.cn/xsl-mock/`、`购票前请确认票种规则`）+ 落地地址带 `xy_click_id`；B 段跨维度排序（Windows 里的 QQ 走容器规则 `/qq` 而不是设备规则 `/pc`）；C 段无匹配落 `origin_url`；D 段 17 次点击后 CH 里 `(device_type,os)` 逐对对上（重复对按 2 计）且空值 0；E 段从中间页 href 打到 mock 购票页 200、两次点击的 click_id 不同；F 段切 `XSL_JUMP_MODE=direct302` 后同样三击全 302、body 0B、无 cache-control，容器内 `printenv` 确为 `direct302`，复位后仍回 200。同一天再跑六套旧验收全绿（33/15/14/19/17/19）。**边界如实写出**：`click_id` 不落 CH（`click_event` 没有这一列，consumer 也没写 `trace_id`），所以"页面里的 click_id 与事件里的那个是同一个"这条现在无法断言，只能断到"页面带 id、每次不同、终点可达"——补齐它需要给明细表加列，属 M3 端到端对账的前置 | 脚本输出即凭证；F 段会重建 jump 容器（脚本自带复位与 `FLIPPED` 兜底），所以必须排在最后 |

> 口径纪律：仅此处出现且可点达的实测值可进入简历/分享；目标值不得冒充实测。

## 4. 与设计的偏差及回写

| 偏差点 | 原因 | 处理（改代码回 DESIGN / 改 DESIGN 记录现状，注明小节） |
|---|---|---|
| **"跳转 P99 未达标"是一个口径错误，不是性能缺陷** | M1-11 在 200 连接（≈10× 目标负载）判 P99<50ms，把"SLO 判定"和"容量上限"混成了同一件事；实际在目标负载 5,002 QPS 上 P99 只有 21ms | DESIGN 1.3 的延迟行显式写明「判定负载＝目标负载 ≥2000 QPS」；8.1-A 增第 3 条说明容量上限归属。基准工具改为输出并发曲线（`bench/run-jump-loadcurve.sh`），报告里同时给达标负载段与饱和段 |
| M1-11 的存档报告里 "jump CPU 0.07%" 是空载采样，指认不了瓶颈 | `docker stats` 只在 `wrk` 结束后采一次；容器 CPU 是瞬时量，结束后必然归零 | 新增 `bench/run-jump-attribution.sh`：负载进行中每 3s 采样取均值，逐容器排序。旧存档不删（只增不改），其缺陷在本表与本节说明 |
| 测量环境本身是变量：宿主 16G 被 Docker VM(8.2G)+IDE+浏览器+微信占满（剩 ~100MiB、compressor 6G），loadavg 一度 20+（10 核） | 同参数曲线两次跑差 2–4 倍；跨天比较 M1-11 与今天的绝对值会得出"性能退化"的假结论 | 每份报告头部新增 `env_snapshot`（loadavg／容器内存合计／ClickHouse 合并与 mutation 数）；规则写进 `bench/README`：**绝对值不跨天直接比，只比同档条件**；正式对外数字需在安静宿主上复跑 |
| 测量工具三连坑（都是"数字不对但不报错"） | ① `nginx access.log` 是指向 `/dev/stdout` 的软链接，`wc -l`/`tail -n +N` 永远读不完（脚本挂死两次）；② Prometheus 文本里指标名与 `{` 之间无空格，`$1=="name"` 永不成立 → 服务端耗时读成 0 次／均值 0ms；③ `docker stats --format` 的字段名写成 `MemoryUsage`（应为 `MemUsage`）时不报错只输出空 → 内存合计恒 0；另外 Go 模板里 `\t` 不会被解释成制表符、`cmd \| python3 - <<PY` 会被 here-doc 抢走 stdin | 日志一律 `docker logs --since`；取值用 `index($1,"name{")==1` 或 `$NF`；解析逻辑抽成 `bench/parse-*.py` 两个文件（不再用行内 here-doc/awk）；`bench/lib-bench.sh` 注释里逐条记坑。这类"取不到值但不报错"的模式已归入本机维护的复盘笔记（不入库，见 ITER-M1 §4 前言），仓库侧的处理是把它落成 `bench/README` 的硬性口径 |
| 容器重启后的第一个压测点被冷启动污染 | JIT 预热 + Caffeine 冷启 + 上一轮写入引发的 ClickHouse 合并同时落在首点：实测 c=4 首点 1,016 QPS／p99 379ms，比 c=200 还差 | `run-jump-loadcurve.sh` 内置 12s 预热（结果丢弃）+ 等 `system.merges` 归零再采样；预热与排空都写进报告 |
| **回填文档时自查出两处"数字对不上存档"**（本轮收口前发现，未流出到 commit） | ① DESIGN 1.3 与 ITER §3 写了 `P50 5.62ms`——任何存档里都没有这个数（写的时候凭印象，没回表核对）；② 写"16–32 连接段 P99 23.8–37.7ms 同样达标"，而 1414 曲线里 c=32 实为 **116.02ms**，把越线点算进了达标段；③ 根因也值得记：曲线首点 p50 单元格塞着整行摘要，因为 `646.00us` 不匹配只认 `m?s` 的 sed，取值失配时脚本原样落盘而不报错 | 文档一律以存档为准重写：c=4 取 0.646ms（摘要串里可读回）、达标段止于 c=16、c≥32 归入容量描述并注明与 c=64 不单调（宿主饱和噪声）；工具侧 `bench/parse-wrk.py` 单位统一归一成 ms、`run-jump-loadcurve.sh` 改按字段名取值（不再用贪婪 `.*`）。**已归档报告按"只增不改"保留原样**，其缺陷写在本行。核对动作固化为：文档里每个数出现前先 `grep` 到 `bench/reports/` 的对应行 |
| **频控 Lua 的目标「≥3 万次/s」没法判达标** | 目标漏写了判定条件：同一份脚本 `-P 1` 单连接测得 27,803/s（差目标 7%），10 连接 166,852/s（超 5.6 倍）——差 6 倍全靠那个没写出来的条件。与 M2-00 在跳转延迟行上犯的错同构（那条漏写"判定负载"，这条漏写"判定并发"） | 8.1-A 该行改写为"三档同报 + 按能否撑住跳转判定"，并在限制说明新增第 6 条把这条一般化：**目标必须自带判定条件**；`bench/README` 口径约定加第 9 条 |
| **并发曲线 20s 档位读到的 p99 不可信** | 同日同参数两次跑：20s 档 c=4 得 p99 50.79ms，40s 档 c=4 得 5.36ms——45ms 的差全是分布尾部样本不足 + 上一档残留，却被当成"频控上线后延迟退化"的证据 | 判延迟一律 ≥40s 档；`-1653`（20s）存档保留不改（只增不改），结论以 `-1656`（40s）为准；`bench/README` 口径第 10 条 |
| **重建 jump 容器后全站 502，nginx 是元凶** | 数据面 upstream 是静态 `upstream{server jump-N:8020}`（keepalive 需要稳定 DNS 名），nginx 只在启动/reload 时解析一次；`make up` 换容器后 IP 变了，nginx 还在打旧地址（error log：connect() failed 111 到 172.18.0.7/0.8，而实际是 0.6/0.10）。控制面因用 `resolver + 变量 proxy_pass` 反而没这问题 | `make up` 末尾补 `nginx -s reload`；这是 M1 就埋下的运维陷阱，之前 `docker restart`（IP 不变）没暴露。记进 ITER 而不是只改 Makefile——下次换机器部署要知道有这一手 |
| **"Redis 挂 → 跳转不跌零"在冷码上是假的** | `RouteRepository.load()` 的 Redis **读**侧没有兜底（只有写回侧有 try/catch），M1 期间靠 30s 的 L1 侥幸挡着；而且 Lettuce 默认命令超时 60s/连接超时 10s，故障时每个请求要等十几秒——"降级"实际是"卡死"，accept-m2-13 的 G 段第一次跑就是全 000 | 读侧失败按"未命中"走 DB 回源；jump 的 Redis 超时压到 500ms（正常路径用不到，只有故障才付这次等待）。DESIGN 8.3 的那一行现在才真的成立 |
| **我自己引入的缺陷：新链接首击就被扣掉重建缓冲** | 冷重建把"键不存在"当成"计数器丢了"，但还有第二种情况——**这条链接从没被点过**。结果 `quota=50` 的链接点 3 次后余数只剩 35（accept-m2-13 的 D1 抓到；如果只看单测会全绿，因为单测里没有"全新链接"这个初始态） | 用 `access_used_at IS NULL` 区分两种"键不存在"（对账只在 Redis 键存在时回写，它是 NULL 就说明没有历史要保护）；补回归测 `neverReconciledLinkSeedsFullQuota`。同时给缓冲加"剩余额度 1/4"封顶，否则 `access_limit=5` 这类小配额链接在尾段重建会被直接打死（把"少放"做成"拒服"） |
| 频控脚本从 Java 内联字符串挪到 `resources/lua/*.lua` | 8.1-A 的基线要用 `redis-benchmark` 跑同一个脚本；内联就得抄一份，抄的那份会悄悄漂移 | 抽文件，jump 与 bench 读同一份（连注释一起送，sha 才对得上）。坑记录：Spring Data Redis 3.3 已无 `ResourceScriptSource`/`setScriptCharset`，`setLocation` 也不给字符集——脚本注释里有中文，必须自己按 UTF-8 读成字符串再构造，否则"测的即跑的"这句话在字节层面不成立 |
| **验收脚本自己有个假失败**：G/H 段的"丢键后收口"偶发多放一次 | 第一版把"Redis 停机演练"排在"对账与冷重建"之前。停机恢复的瞬间 jump 会有一两次 Redis 调用失败并走 fail-open（DESIGN 8.3 的正确行为），那次点击既不写 `sl:cnt` 也不扣减，把后面的配额计数污染成 3 次放行。定性时先对照了 `sl-jump/sharding.yaml` 与 `sl-admin/sharding.yaml` 的 `link_route` 分片轴——**怕的是真路由不一致**（admin 写一个分片、jump 读另一个），那会是严重 bug；查清两边都是 `short_code` + `code-hash-mod`，排除 | 故障演练挪到最后（现 G=对账与冷重建、H=停机），并在脚本注释里写明"停机必须放最后"的原因；改后连跑两次 17/17 稳定。教训：**断言失败先定性再动手**，flake 的正确处理不是加重试，而是搞清楚它为什么会出现 |
| **M2-14 写下的"p99 至少要 40s 一档"是过度修正** | M2-20 复采改前基线时用同一台机器、同样 40s 档跑 c=4：16:56 得 p99 **5.36ms**，18:39 得 **49.26ms**，差 9 倍；整条曲线还跨档不单调（c=16 的 109ms 高于 c=64 的 29ms）。说明时长只是必要条件，不是充分条件——真正的决定因素是环境：`env_snapshot` 记着宿主剩 146MiB 空闲、compressor 7.5G，10 核 Docker VM 在负载中被数据面+事件链吃到 ≈10.4 核 ⇒ 尾部由外部抢占决定 | `bench/README` 第 10 条改写为"够长**且**可复现"：延迟类结论必须同档至少两次一致才引用；A/B 比较优先用对噪声稳健的量（QPS 平顶值、`sum/count` 精确服务端均值、CH parts/merges、单位数据请求次数、追赶速率）。M2-01 的判定（M2-23）按这套量出，p99 只在目标负载档当佐证 |
| **consumer 的 `/actuator/prometheus` 一直是 404** | 它的 yml 早就写了 `exposure.include: …prometheus`，但 pom 没引 `micrometer-registry-prometheus` —— 端点根本不存在。也就是说 DESIGN 8.5 承诺的"consumer 侧观测"此前只能从 Kafka lag 和 CH 计数侧面取 | 补依赖（否则 M2-21 新加的写入计数器就是没人能看的死代码）。补完 `xsl_consumer_ch_posts_total{result}` / `xsl_consumer_ch_rows_total` / `xsl_consumer_ch_post_seconds` 才可抓，本轮"POST 次数下降 ≈9×"的证据正来自它 |
| **ClickHouse `system.query_log` 在这套栈里查不到写入记录** | 想用它独立佐证"每次 INSERT 带多少行"，结果 `SYSTEM FLUSH LOGS` 后近 10 分钟仍 0 行；顺带踩了两个列名坑（`table`→`tables`、`written_rows` 含内部哨兵值会让 `avg()` 溢出成 inf） | 不把 query_log 当证据源，改用**客户端计数器 + `system.parts`**（175 次 POST 写 78.6 万行、active parts 只有 10 个、merges 0）；结论：以后要 CH 侧的每插入行数统计，得先确认 query_log 的 TTL/采样配置，别默认它有数 |
| **活体用例被数据量拖死（不是被改动改坏）** | `ClickPipelineLiveTest` 用 `auto.offset.reset=earliest` 从 topic 起点消费来找自己刚发的 3 条消息。事件积累到几百万条后 20s 内爬不到位点，表现为 `expected 3 but was 0`——17:1x 那次 verify 还是绿的，中间只跑了两轮压测 | 改成"先发、从 `RecordMetadata` 拿分区与位点，再 `assign + seek` 到那个位点消费"，与 topic 既有体量无关，且 M2-22 加分区后仍然正确；同时关掉该消费者的 auto commit（不留组位点）。**断言本身没有放松**（仍要求恰 3 条、FINAL 恰 2 行） |
| **我把"幂等扩容"写错了，症状却是静默的**：`kafka-topics --alter --partitions 4` 在 topic 已经是 4 分区时以 `InvalidPartitionsException` **退出 1**，不是空操作 | 我在 M2-22 把它直接串在 `--create --if-not-exists` 后面当"只增不减"，注释还写着幂等。因为 kafka-init 是 consumer 的 `service_completed_successfully` 前置条件，它一失败 consumer 就永远停在 Created —— 而 `make up` 当时**返回 0**，jump/nginx 全 healthy，只有事件链悄悄死了。第一次部署（1→4 真扩容）成功，所以坏的是"之后每一次 up" | 逻辑收进 `deploy/compose/init/kafka/ensure-topic.sh`：describe 重试到 broker 就绪 → 只有实测分区数 < 期望才 alter → 最后核对达标；已达标正常退 0，真失败才非 0（不糊 `\|\| true`，否则这个前置条件就失去意义）。连续两次 `make up` 验过 Exited(0) + consumer healthy |
| **同一批教训的第二条：测量脚本不区分"被我停掉"和"根本没起来"** | `run-kafka-bench.sh` 第一段本来就要 stop consumer，所以"从一开始就没起来"在数据上与"被本测停掉"完全同形：三档并行度跑出追赶 605s 超时、追赶吞吐 320／744／1167 events/s、CH 零新增、丢失率 100%——**每一格看着都像结论**，其实是空跑（那三份存档未提交即删除） | bench 加前置断言：consumer 容器 `State.Running != true` 或 `:8040/actuator/health` 不可达就直接 FATAL 退出，不进测量循环。原则与 M2-00 那条同源：能"看起来成功"的失败最贵，得给它一个显式断言 |
| **"p99 跨轮不可复现"被证明是症状而不是测量缺陷** | M2-20 复采时同一档两次相差 9 倍（5.36ms vs 49.26ms），当时归因成"宿主噪声 + 时长不够"。M2-21/22 把事件链 CPU 从 2.17 核压到 0.92 核、VM 占用从 10.4 降到 5.3 核之后，同一批测量恢复单调且三档互差 <1ms——**包括 loadavg 11.19（全场最忙）那轮仍只有 2.56ms**，而改前 loadavg 5.84 却测出 49.26ms。所以真正的变量是"被测系统自己被 CPU 墙卡住"，不是宿主背景噪声 | 结论按时间线写进 DESIGN 1.3/8.1-A 而不是覆盖：改前的数字保留、改后的数字并列，并注明"为什么当时会误读"。`bench/README` 第 10 条保留"同档两次一致才引用"（它仍然有效），但补一句：分位不可复现首先应怀疑**被测系统自身饱和**，而不是先去加长采样 |

动态路由主线开工前的摸底（2026-10-03）先记三笔，性质都是"文档里的承诺没有对应实现"：

| 偏差点 | 原因 | 处理 |
|---|---|---|
| **DESIGN 第十章承诺的"纯 302 回退开关"是假的**：`xsl.jump.mode` 只出现在 yml 注释里，没有任何条件装配，`JumpResolver` 从头到尾只有一个实现 | 配置项写出来那天起就没被用来分支；文档把"设计上有开关"当成了"已实现" | M2-33 用 `@ConditionalOnProperty` 做成真装配（`direct302` / `dynamic` 各一个 resolver），并在 `accept-m2-35.sh` 里验"改一个 env 就能退回纯 302"。规矩：凡"我们支持 X"的说法，收口要能指到一个可跑的验证动作 |
| **`route_rule` 有表、有分片配置、设计里有示例 JSON，但 Java 侧零引用** | M1-02 按 DESIGN 4.1 全量建表，双轨的"行式真源→编译 route_json"那一半属 M2，一直没接 | M2-31 补校验器/编译器/规则 API，让 `route_json.rules` 成为真的编译产物；jump 继续只读 `link_route`（它的分片配置里没有 route_rule，这是刻意的气分离） |
| **outbox 只有写方没有读方**（`LINK_CREATED`/`LINK_UPDATED` 落表后 status 恒为 0） | 同步器排在 M3，但表先建好了，读者容易以为事件通道已在使用 | 本轮不扩范围（沿用现路径直写 route_json），只在 DEVELOPMENT_PLAN 二-B 与本行写明它是 M3 的活儿 |

M2-31 实现期间的口径收窄与自查（2026-10-04），性质分两类：**故意比设计窄**（前两条）与**我自己写错、被测试或工具抓到**（后五条）：

| 偏差点 | 原因 | 处理 |
|---|---|---|
| **算子按维度收窄，`gt` 不受理**（DESIGN 4.1 的 `route_rule.condition_op` 注释里写着 `eq/in/gt/between/any`） | `ua_container/device/geo_province` 都是枚举、`time_window` 是区间，"大于"对它们没有可比语义；受理了不报错，只会让人以为能配出"号段之后生效"这类规则 | `RouteRuleService.ALLOWED_OPS` 按维度给白名单（枚举类 `eq\|in`、`time_window` 只 `between`、`default` 只 `any`），表注释与 DESIGN 6.2「双轨的落地口径」同步；`app_scheme` 的 `scheme_url` 另外禁 `http(s)`——填 https 就等于 h5 规则，会把中间页的降级判定变成死代码 |
| **一条规则只有一个条件维度**，DESIGN 6.2 示例里"geo_province=上海 **且** time 在档期内"的组合条件实现不了 | 行式模型是通用 `(type, op, value)` 三元组（4.1 就这么定的），给每条规则加一组维度列，校验器和编译器的复杂度会翻倍，而本期唯一会用到的组合（geo+time）里 geo 恒为未知 | 示例改成实际形状并在 6.2 写明"同条 AND 未实现，要表达就拆成不同 priority 的两条"；`geo_province` 本身在离线地理库接入前恒不匹配（M2-30 已定为能力边界） |
| **`RouteRule.isDefault()` 被 Jackson 当成属性**，快照里凭空多出 `"default": false`，且键序与库里不同 | 记录类之外的 `isXxx()` 布尔方法在 Jackson 眼里就是 getter；第一版想加 `@JsonIgnore`，但 `sl-common` 是**零运行时依赖**模块（只有 test scope 的 junit/jqwik），为一个注解引 jackson-annotations 不值 | 改名 `unconditional()`（不带 `is/get` 前缀就不被识别），并把"为什么不能叫 isDefault"写进方法注释。测试侧同时把"缓存 vs `link_route`"从字符串比较改成**树比较**——MySQL 的 JSON 列会重排键序，字符串比较得到的"不一致"是假的 |
| **规则校验原本写在短码分配之后**（我第一版的顺序） | 非法规则请求会先 `SETNX sl:code:{code}` 并租用池码，随后才 400——同一个自定义码第二次合法请求变成 409 `SHORT_CODE_TAKEN`，等于用一个语法错误占住别人的码 | `validateAndCompile` 挪到 `allocateCode` 之前；`accept-m1-06` 第 8 段与集成用例各钉一次（`code_tenant_index` 里查不到那个码） |
| **`PATCH /links/{code}` 会把规则静默清空**（同一类"重编译快照时漏带字段"） | `update()` 用 `short_link` 行重建 `RouteConfig`，规则不在那些列里——`rules` 直接变 null。症状是"改了个过期时间，动态路由全量退化成 302"，且没有任何报错 | `update()` 先 `ruleService.load()` 把既有规则带进新快照；回归用例 `patchMetadataDoesNotClearRules` + 验收第 8 段各断言一次。同类风险留给 M2-33：**任何重建快照的路径都必须带全字段**，这条要作为实现约束记住 |
| `route_rule.id` 第一版用 `(tenant, code.hashCode(), priority)` 拼算术 | `String.hashCode()` 只有 32 位，两个不同短码撞哈希的概率在几十万级就不可忽略；一旦撞上有主键冲突，表现是 500 而原因藏在哈希里，查起来毫无线索 | 换 FNV-1a 64 位哈希并清符号位；仍保持**确定性**（同 `(tenant, code, priority)` 重编译得到同一主键，"整体替换"重试不会留下两套行），只是把冲突概率从 32 位量级降到 64 位量级 |
| **改 `RouteConfig` 后 `mvn test-compile` 是绿的，clean 之后才暴露 jump 两处旧构造** | Maven 增量编译按源文件时间戳判断，`sl-jump` 的测试源码没动 → 整模块跳过编译，跨模块的 record 签名变更被完全掩盖 | 凡改 `sl-common` 的公共 record，收口前至少 `mvn clean test-compile` 一次（本轮已并入 `make verify` 前的固定动作）。这类"编译通过但其实没编"的坑与 §4 里"取不到值但不报错"是同一族 |

M2-32 的词汇表与特征表（2026-10-04），一处是我 M2-31 自己埋的雷：

| 偏差点 | 原因 | 处理 |
|---|---|---|
| **容器 key 用了 UA 串 `micromessenger`，而 CH 的 MV 字面量是 `'wechat'`/`'douyin'`** | M2-31 定 `UaContainer` 时我按"UA 里长什么样"取的名，没去查统计侧已经在用什么串。照这个 key 写事件，`countIf(device_type='wechat') AS wx_pv` 会**静默恒为 0**——不报错、不失败，只是看板上的微信渠道没数 | 全链路改成业务名词汇表：`wechat/douyin/qq/weibo/alipay/unknown`（枚举、DESIGN 6.2 示例、集成用例、`accept-m1-06` 第 8 段同步），UA 串降级成 `tokens`（会变的事实，不是标识）。并补 `accept-m2-32.sh` 的 C 段把"规则条件值＝事件取值＝MV 字面量"这条贯通断言化 |
| `click_event.device_type` 装的是**容器**、`os` 装的是**设备大类**，列名与语义不完全对得上 | M1 建表时 `device_type` 就是按"分容器 PV"用的（`click_stat_minute` 注释、M1-11 存档 seed 都按 `'wechat'/'douyin'/'browser'` 在跑） | **不改列名**：改名要动 CH 迁移，且历史行的分类会全部失效，让存档失去可比性（这是本项目更看重的那条纪律）。把取值词汇表写进 DESIGN 4.2 并在此留痕，避免下一个人以为 `device_type` 是 mobile/pc |
| **"特征表从配置加载"这句话有边界，不说清就会变成假承诺** | 类别（wechat/douyin/…）同时被三处引用：`route_rule` 条件校验、事件取值、CH 字面量。把它配置化＝让这三处各配一份，然后互相错开。真正需要月度更新的其实是**特征串**（某容器改了 UA 标识） | 拆成两层并写进 6.2：`tokens` 配置级（改 `application.yml`）、类别代码级（动枚举）。同时**代码里不留默认表**——留了就会出现"改了配置没生效，因为跑的是代码里那份"，而这句话在启动日志里看不出来 |
| 表写错（key 不在枚举、token 为空、同 key 出现两次、整表为空）如果只打日志，症状是"某条路由规则永远不命中" | 这类故障要等到看板上"微信流量为 0"才被发现，而那时已经分不清是配置、UA 还是路由的问题 | 全部在 `UaClassifier` 构造期抛 `IllegalArgumentException` ⇒ jump 起不来（配置是系统边界，值得付这一次启动失败）。`UaClassifierTest` 把五种错法各钉一条断言 |
| **自维护表的覆盖代价第一次被量化**：鸿蒙 NEXT 的 UA（`Phone; OpenHarmony 5.0 … ArkWeb`）既不是 android 也不是 iphone/windows | 表里只有 5 容器 + 3 设备，通用库能识别的类别我们只覆盖了一半 | 如实钉成断言（`UaFeatureTableTest` 的 `OPENHARMONY_MOBILE → unknown/unknown`），并写进 DESIGN 6.2 的"代价"那句。行为后果是可接受的：规则侧未识别走 default，不会误伤 |
| `accept-m2-32.sh` 的样本集要求**每个 `(容器, 设备)` 对唯一**，否则"逐条 count==1"不成立 | curl（`curl/8.4.0`）与真·空 UA 都判 `unknown/unknown`，同一对出现两次就没法用 count==1 断言分类正确 | 空 UA 那条改用 `user_agent=''` 定位（顺带把"M1 历史行的空串"与"本轮的 unknown"区分开），13 条常规样本两两不重对。这个约束写进脚本头注释，M2-35 扩样本时要保持 |

M2-33 的一级决策与开关（2026-10-04），四条里有两条是"看起来已经实现、其实没有"：

| 偏差点 | 原因 | 处理 |
|---|---|---|
| **数据面把"数组顺序"当成了"priority 顺序"**：我故意按 20→5 的顺序写进快照，结果 `/late`（priority 20）赢了 | `chooseTarget` 直接遍历 `rc.rulesOrEmpty()`，而"编译产物已按 priority 升序"只是管理面的产出习惯，**读取方没有任何东西保证它成立**——M3 的 outbox 补偿、手工修数据、校验器上线前的旧行都可能乱序，且顺序错了不报错，只会让一条规则永远轮不到 | 遍历时按 priority 排序（规则数 ≤20，代价相对 JSON 解析可忽略）；用例改名 `firstMatchingPriorityWinsNotArrayOrder` 钉住。写进 DESIGN 附录 A：语义由字段决定，不由数组位置决定 |
| **"中间页 HTML 本身可缓存 CDN"与"每次点击带 `xy_click_id`"是互相矛盾的**（DESIGN 6.2 初稿两条同时存在） | 那句话是写缓存收益时加上去的，没检查页面内容里带着逐击数据；照做会把 A 的 click_id 发给 B，而且归因作废是静默的（看板上数字照常增长） | 改成 **`Cache-Control: no-store`**，6.2 开头新增那条"修正一处初稿的自相矛盾"，`JumpControllerTest` 直接断言响应头。可缓存的只有页面引用的静态资源 |
| **回退开关在部署形态下是假的**：装配做成了 `@ConditionalOnProperty`、`application.yml` 也写了 `${XSL_JUMP_MODE:dynamic}`，但 compose 的两个 jump 服务**没有透传这个变量** | 于是 `XSL_JUMP_MODE=direct302 make up` 只改宿主机 shell，容器里永远看到默认值——"改一个环境变量就退回去"只在宿主机 `java -jar` 时成立。M2-30 说"必须做成真装配"，我只做了进程内那一半 | compose 两个 jump 服务补 `XSL_JUMP_MODE`/`XSL_JUMP_URLLINK_PROVIDER` 透传（`${VAR:-default}`），并活体验证：dynamic 三形态 200 + no-store → 切 direct302 后同样两击全 302、body 0B → 复位。教训与"能指到一个可跑动作"同源，但这次多一层：**开关的可操作性要看部署形态，不是看代码里的注解** |
| **中间页天然有两个 H5 地址，而归因拼接是按"一次跳转一个 URL"设计的**：兜底 H5 与引导地址各拼一次就会出现同一点击两个 `xy_click_id` | `AttributionParamMerger.merge()` 每次都生成新 id（这是 M1-08 的属性测试钉过的契约），而 `ClickEvent` 只记其中一个，页面上另一个就成了对不上账的孤串 | 链路只对"主落地地址"拼一次，页面里其它 H5 引用一律复用这条已拼好的串（`Target.landingForAttribution()` 定义谁是主）；`scheme://` 不拼（App deeplink 归因是另一条链路，本期不做，写进 6.2）。顺带把 UA 分类从 `buildEvent` 上移到 `resolve`——决策与事件共用一份 `Profile`，跳转路径不扫第二遍 UA |
| `scheme_url` 的形状校验放过引号与尖括号（`scheme://` + 任意 1–512 字符），而它要原样进 `<script>` | "管理面校验过"挡不住注入：校验的是**形状**（协议、长度），不是内容安全；落地地址有 URL-encode 兜着，scheme 没有第二道 | `IntermediatePage` 按落点语境各转义一次（`href` 走 HTML、脚本走 JS 字面量并把 `<`／`>` 转成 `\x3c`／`\x3e`），用例拿 `xyapp://x';alert(1);//</script><b>` 钉 |
| **DESIGN 附录 A 与 8.1-A 有两处 markdown 表格"渲染时丢列"**：附录 A 表头声明 2 列，而 v2.2.1 起的行都是 3 列；8.1-A 的"跳转吞吐"行有 5 格而表头是 4 列 | GFM 的处理方式是**多出的单元格直接不渲染**——于是附录 A 里最有价值的"处理"那列、8.1-A 里那条数字的**存档凭证**在渲染后的文档里根本不存在。数字与结论都还在，就是点不到出处，这正好是本项目最忌的失效形态，而且它藏在渲染环节而不是文本里，只 grep 内容永远发现不了 | 附录 A 在第一个三列行之前另起一个三列表头（并把新增的 v2.8.0 行按时间顺序放到 v2.7.0 之后）；8.1-A 那一行的存档列表并回"实测"格。顺带补上 `short_code_pool.status` 少写一格（说明串落到了"类型"列）。收口动作加一条：**文档里的表格要检查"格数与表头一致"**，本轮用一个 20 行的竖线计数脚本做的，DESIGN/ITER 现在 0 处不一致 |

M2-34 的"最后一里"（2026-10-04），三条都关于**承诺与可达性之间的距离**：

| 偏差点 | 原因 | 处理 |
|---|---|---|
| 计划里写"nginx 静态回显 query + **fragment**"，而 fragment 根本不上行 | `#…` 只存在于浏览器端，HTTP 请求里没有它。照字面实现 nginx 配置，得到的是一个永远回显不出 fragment 的页面，而且没人报错 | 回显改由页内 JS 读 `location.search` / `location.hash`，页面里那一格直接把这个限制写成给用户看的一句话；DESIGN 6.2 补一条口径说明"服务端回显 fragment 是做不到的事" |
| "302 终点变成可看点"在只有 :80 的栈里做不到 | 终点是 `https://mock.ticketsales.test/…`（9.2 强制 https，不为了演示省事把它降成 http），而 nginx 只监听 80、`.test` 又不是能解析的域。于是"可看"这句话需要三件事同时成立：443 监听 + 本地自签证书 + 宿主 hosts 一条记录 | 新增 `scripts/gen-mock-tls.sh`（幂等，SAN 覆盖 mock 与三个短链域）+ `make tls` 挂成 `up` 的前置 + `30-mock-page.conf` 的 80/443 双块（未登记 Host 在 443 上仍 444）。证书只落 `deploy/compose/tls/`，已进 .gitignore。`make demo` 加 4b 段跟着 302 真打一次，把这句话变成一个可跑动作 |
| **`up` 的前置不是洁癖**：目录不存在时 Docker 会替我们 `mkdir` 一个空 `tls/`，nginx 随即以 `cannot load certificate` 起不来 | 报错出现在容器日志里，`make up` 的输出只有 "nginx unhealthy"，中间没有任何一行提到证书 | `up: tls` 依赖 + compose 注释写明为什么必须前置。与 kafka-init 那次是同族问题：**失败发生在别人看得见的地方之外** |
| 第一次访问回显页得到 404，而 nginx 日志只说 404 不说找的是哪个路径 | 我把 conf 里的 `root` 写成 `/etc/nginx/html/mock`，而挂载点是 `/etc/nginx/html`（多写一层） | `docker exec xsl-nginx-1 ls /etc/nginx/html` 一眼定位。顺手记一条：**配置类 404 先核对挂载根与 root 的对应关系**，别先怀疑 try_files |
| nginx 两处注释引用的 "DESIGN 10.2" 是个**不存在的小节**（M1-10 就写错了） | 域名白名单的语义实际写在 9.5；而 grep 文档内容永远查不出"指向不存在的锚点"这类错 | 两处一并改成 9.5，并在注释里保留"原注释写的 10.2 是不存在的小节"这句话，免得又被改回去 |

M2-35 把出口标准变成脚本时踩到并记下的（2026-10-04）：

| 偏差点 | 原因 | 处理 |
|---|---|---|
| 验收脚本第一次跑出来是 **87 项里 36 红**，而根因只有一行：`rules-put-200 expect=200 actual=400` | 我给 `in` 编了一个词汇表外的条件值（`qq,facebook`）——校验器按设计 400 拒了，于是规则一条没存进快照，15 条 UA 全部落回 `origin_url`，症状却是"动态路由整个不工作" | 先读那一行 400 的响应体（`unknown ua_container: facebook`）再动别的。脚本里把 `in` 换成 `qq,weibo` 并删掉单独的 weibo 规则，同时把 weibo 样本的期望换成 `/qq`，让"in 列表第二个值也算命中"变成一条**断言**而不是巧合。附带结论：校验器确实挡住了"看起来能用、其实永远匹配不上"的值 |
| **"页面里的 click_id 与事件里的 click_id 是同一个"这条断不出来** | `click_event` 明细表没有 `xy_click_id` 列，consumer 的 `toRow()` 也没写 `trace_id`（DESIGN 4.2 的字段表里同样没有这一列）——所以"点击 ↔ 订单"在 CH 里目前**没有可对键** | M2-35 只断到"页面带 id、两次点击 id 不同、跟着能打到 mock 购票页"，并把缺口写进 §3 与 §6：补齐它要先给明细加列或把 trace_id 落库，**先改契约再写脚本**（属 M3 端到端对账那格） |
| 开关段（F）要重建 jump 容器，排在前面会污染后面的断言 | 与 M2-13 的 Redis 停机演练同构：切换/故障动作一发生，Redis 与 L1/L2 缓存就进入刚恢复的不稳定窗口 | 固定排在最后，并让脚本自带复位（`FLIPPED` 标记 + EXIT trap 里兜底 `make up`），失败中断也不会把栈留在 `direct302` |

## 5. 本期决策记录

- **M2-00 先做诊断再动配置**：M1-11 只证明了"不是 GC"。尾巴到底在 jump 进程内、nginx 排队、还是同机压测客户端争抢 CPU，决定治理手段完全不同；因此第一步是给链路加**可归因的观测量**（nginx 侧 `$upstream_connect_time`/`$upstream_response_time` 与 `$request_time` 分解、jump 侧 HTTP 服务端延迟分布），拿一轮数据定位，再谈调参。
- **默认配置的口径以"复现成本"为准**：任何为跑分而临时改的东西（内存上限、GC、worker 数）必须在文档里同时留下"改之前/之后"两组存档，否则 M1-10/M1-11 的验收数字失去可比性。
- **M2-00 只做治理与归因，不做性能优化**：测出"上限由 ClickHouse 写入/合并决定"之后就停手——把它做成事件链批量化是 M2-01/M2-02 的设计变更（涉及 CH 批量语义、Kafka producer 参数、consumer 拉取节奏），需要按 M1 的格式先细化任务再动代码；在 M2-00 里顺手调 CH 参数会让这一半天的基线全部作废。
- **jump 内存提到 768m 是稳定性决定，不是性能决定**：GC 对照实验已证明它对 P99 无改善（M1-11 变体 57.9/57.6ms vs 默认 52.6/57.6ms），提额的理由是常驻 473–509MiB 长期贴顶带来的 OOM-kill 风险。两类理由分写在 DESIGN 3.3/ITER，不混为一谈。
- **SLO 判定负载写进指标本身，而不是写在脚注里**：`P99<50ms` 单独出现是无意义的（任何阈值在足够大的负载下都会被破）；DESIGN 1.3 的延迟行现在把「判定负载＝目标负载 ≥2000 QPS」写在目标列内，饱和段另列为容量描述。以后新增延迟类指标同此格式。
- **收口前做一次"数字回表"**：文档里每个实测数字，落笔前先确认它能在 `bench/reports/` 的某一行里点到；本轮就是靠这一步抓出两个错数（§4 末行）。这条已写进 `bench/README` 口径约定第 8 条，属于以后每期的收口动作，不是本次一次性补救。

防刷主线（M2-10 起）的四个前置决定，全部先回写 DESIGN 再动代码：

- **频控放在配额扣减之前，而不是之后**：DESIGN 5.2 原本把"有效性检查（含 `access_limit` 扣减）"排在"频控与风控"前面。按那个顺序，攻击者只要对一条设了配额的链接高频刷，就能在数秒内把配额烧光——限流反而成了打别人的"配额自杀开关"。顺序调换后，被频控拦下的请求**不消耗配额**（M2-13 的验收脚本要专门断言这一点）。
- **本期做固定 60s 窗口，不假装是滑动窗口**：初稿写的是"滑动"。真做加权滑动要一次读两个槽位键 → 多键 Lua 在 Redis Cluster 下要求两键同槽，与 8.1-B 对扩展路径的承诺（"键设计已预留无跨槽操作约束"）冲突；而 jump 是 ×2 实例，把上一槽计数缓存到进程里会因轮询各看到一半而系统性低估。代价如实写在 5.3：窗口边界最坏放行约 2× 阈值。要做真滑动，连同 hash tag 一起作为后续一轮改动。
- **客户端 IP 只信 nginx 覆盖写入的 `X-Real-IP`**：现有 `clientIp()` 取 `X-Forwarded-For` 首元素，而 XFF 首元素由客户端自控——拿它做频控维度等于可以随意绕过，拿它做 `ip_hash` 则刷量数据不可信。改为 `X-Real-IP`（nginx `$remote_addr` 强制覆盖）→ XFF 末段 → `remoteAddr`，频控键与 ClickEvent 的 `ip_hash` 共用同一实现，避免两处口径漂移。
- **对账落管理面 sl-admin，快照列放 `link_route`**：Redis 是权威，但键丢了就按"满额度"重放等于超放，所以必须有 MySQL 快照。快照不放 `short_link`（按 `tenant_id` 分片，jump 的 ShardingSphere 配置里根本没这张表）而放 `link_route`（按 `short_code` 分片，与 jump 同轴、且它本来就读）；回写任务由 admin 的 `@Scheduled` 做，jump 继续遵守"数据面只写 Redis、不写业务表"。快照单独成列、不进 `route_json`，否则每分钟一次的写会反复打穿 L1/L2 缓存与 `version` 语义。

实现期间再定三件（都不是"顺手就能写"的那类，理由得留着）：

- **命中先返回 429 + `Retry-After`，不做"安全验证页"**：中间页/验证页是 M2 的另一项（HTML + Scheme 降级 JS），这一层要证明的是"拦得住、绕不过、不烧配额"三件事可证伪；429 与 DESIGN 5.3 第 1 层（网关限流）同口径，将来验证页落地时只换响应体，判定链不动。
- **被拦的击仍发事件，但只记 `risk_score=25`、不标 `is_bot`**：`is_bot` 的判定权在第 3 层评分（`score ≥ 40` 才成立），本层越权标注会在评分层还没上线时就污染双指标看板；而"完全不记"会让看板只看到流量凭空消失、看不到被拦掉的那部分。折中是记录事实（超频贡献分）、不下结论。
- **对账里"少放"不允许滑成"拒服"**：重建播种是 `上限 − 已用 − 缓冲`，缓冲再被剩余额度的 1/4 封顶——否则 `access_limit=5` 这类小配额链接在尾段一次丢键就被永久打死，那是可用性事故不是风控。方向仍是"宁可少放不可超放"。

动态路由主线的取舍（M2-30 定，全部先写进 DESIGN 6.2 再动代码）：

- **不引第三方 UA 解析库，特征表自己维护且从配置加载**。本项目要区分的只有 5 个容器 + 2 类设备 + 未知，通用库买到的是"显得权威"而不是更准；DESIGN 说的"月度更新"在单机的等价物就是改配置。代价如实写：覆盖面比通用库窄 —— 兜底是"未识别一律落 default"，新容器最多不走高级规则，不会被误伤。
- **能力边界要显式承认，不用假实现糊过去**：`geo_province` 在没有离线地理库之前**恒为未知**（该条件恒不匹配，与 IDC/代理库是同一件事，留给评分那格）；`wx_urllink` **不接真实微信开放接口**（个人项目没有凭据，也不该把外部 API 放进单机验收路径），只留 `UrlLinkProvider` 接口 + 本地 mock 实现。这两条如果偷偷"做个能跑的版本"，看板和演示就会开始讲不存在的能力。
- **中间页由 jump 直接返回 200 HTML，不新增服务、不走 nginx 静态**：302 之后服务端没有 HTML 控制权，降级判断只能写在服务端给的这一页里 —— 与"5.2 禁用 301"是同一个取舍的两面。
- **mock 购票页做 nginx 静态回显，不建 Java 模块**：它只需要回显 query/fragment 供 UA 重放断言和浏览器演示；假订单接口与归因回传是 M3 那一格。

双轨落地时（M2-31）再定三件：

- **数据面只读编译产物，永不回查 `route_rule`**：jump 的分片配置里没有这张表，这不是省事，而是把"缓存与库里不一致"从运行时自愈问题变成可判定的实现 bug——不一致时数据面只会用旧快照（有 TTL 与对账收口），不会时好时坏。代价是规则改动的生效路径必须唯一（`PUT → 行 + 重编译 → link_route → Redis`），任何旁路写 `route_rule` 的代码都不许长出来。
- **`PUT /links/{code}/rules` 整体替换，不做逐条 PATCH**：规则集合是有顺序语义的整体（priority 冲突、default 唯一、条件重复检测都要看全集），逐条合并的结果没人能预测，包括写它的人。清空用空数组而不是 DELETE，少一个动词就少一类歧义。
- **快照里"无规则"写成显式 `"rules": null`**：沿用 5.2/6.2 既有的"null 字段显式序列化、跳转侧按 key 读取"口径，不给同一个快照两种形状；数据面用 `rulesOrEmpty()` 收口，老快照（M1 写的、压根没有这个键）与新快照走同一条路径——这也是 `accept-m1-07` 不改一行就能在全新 build 上 15/15 的原因。

UA 引擎落地时（M2-32）另外定三件：

- **一套词汇表贯通三处，而不是三处各留一套**：`route_rule` 的容器条件值、`click_event.device_type`、`click_stat_minute_mv` 的 `countIf(...)` 字面量必须是同一个字符串。为此我改掉了 M2-31 刚定的 `micromessenger`——代价是两个文件的小改动，收益是"规则命中微信"与"统计里的微信 PV"从此不可能各说各话。UA 里长什么样（`MicroMessenger/8.0`）是**会变的事实**，把它当标识就是把事实当命名。
- **分类只在事件构造处做一次**（`buildEvent` 里 `classify()` 一次给出容器 + 设备），M2-33 的规则匹配复用同一个 `Profile`，不在跳转路径上扫第二遍 UA。跳转路径现在是被测出的新瓶颈，任何"顺手在两个地方各算一次"的写法都会直接打在 CPU 上。
- **`unknown`、空 UA、空串是三件事**：认不出＝`unknown`（列写了但没匹配上）；没带 UA 靠 `user_agent=''` 查出来；而 `device_type=''` 从此只代表"M1 写过的那批历史行"。看板对这三者的处理不同（前两种是流量，最后一种是数据质量），所以不能都塌成空串——`accept-m2-32.sh` 的 B 段就是钉这个的。

一级决策落地时（M2-33）另外定四件：

- **开关选的是 `JumpResolver` 实现，链路只有一份**：`JumpChain` 抽象基类持有"存在性 → status/expire → 频控 → 配额 → UA 分类 → 归因拼接 → 事件 → 响应形态"整条序列，两个子类只实现 `chooseTarget`。当初摆在面前的是三种写法：两份完整 resolver（链路必然漂移）、一个 resolver + 可切换的策略 bean（文档承诺的"各对应一个实现"落空）、抽象基类（本次选的）。选第三种是因为 DESIGN 第十章那句话的主语就是"实现"，而 M2-00/11/12 修出来的顺序是这份代码里最贵的一部分。
- **`unknown` 可以被 `eq` 显式命中**，与 `default` 各有用途：前者是"只给认不出的流量看引导页"，后者是"所有落空都兜住"。把 unknown 屏蔽掉看似安全，实际会让"新容器一律走兜底"这种需求没法表达。
- **`scheme://` 不带归因参数**：App deeplink 的点击回执需要 SDK 配合，是另一条链路。在这里硬拼上去，"归因已贯通"这句话就变成假的——宁可中间页的兜底 H5 带全参数，scheme 保持原样。
- **mock 的 URL Link 用确定性串**（`https://wxaurl.cn/xsl-mock/{sha1(path|落地地址) 前 8 位}`）：同一规则 + 同一落地地址永远同一个链接，UA 重放脚本敢断言；而落地地址带着 `xy_click_id`，所以"每次点击的 link 不同"这条也顺带可验。真实 provider 换上时只需要换一个 bean。

M2 出口标准落地时（M2-35）定一条：

- **出口标准必须是一条能一把跑完的命令**，而不是"逐条手工看"。`scripts/accept-m2-35.sh` 的 87 项里同时覆盖了 M2-32 的取数口径、M2-33 的四类 target 与开关、M2-34 的最后一公里；任何一环退化都会红在**具体样本名**上（`A-wechat-windows-pc-scheme`、`F-douyin-android-no-cache-control`），所以断言粒度按"样本 × 属性"而不是按"接口"。

## 6. 下期待办与风险

- **防刷主线已收口（2026-10-03）**：M2-10…M2-15 全部完成，五套旧验收 + `accept-m2-13` 在新 build 上全绿（§3 末两行），两处 M1 遗留改名做掉。当时留给用户选的两项里，**M2-01 事件链批量化已收口**（见下面第四条），**动态路由主线进行中**：M2-30（口径）、M2-31（route_rule 双轨）、M2-32（UA/容器特征引擎）、M2-33（一级决策 + 中间页 + 真开关）、M2-34（mock 购票页 + 443 自签）、M2-35（UA 重放测试集 **87/87**，**M2 出口标准达成**）均完成于 2026-10-04，只剩 **M2-36 收口**（DESIGN 复核、README 演示章节、ITER 定稿与 tag）。
- **M2-36 收口要做的四件事**：① README 演示章节补 `make tls` 与 hosts 那条前提（不然"终点可看"在读者机器上不成立）；② DESIGN 8.5 观测清单补 `xsl_jump_route_target_total{kind}` 与 `xsl_jump_route_rule_unusable_total{type}`；③ 六套旧验收 + `accept-m2-32/35` 在最终 build 上一次性复跑，条数写进 §3；④ 本机复盘笔记（`docs/PROBLEMS_AND_SOLUTIONS.md`，不入库）从 §4/§5 提升符合门槛的条目。
- **M3 端到端对账的前置缺口（本轮发现）**：`click_event` 没有 `xy_click_id` 列、consumer 也不写 `trace_id`，所以"点击 ↔ 订单"目前没有可对键。做"订单回流 + 端到端对账 <2%"之前，先决定给明细加列还是把 trace_id 落库，**先改契约再写脚本**，并同步 `accept-m2-32/35` 的取数口径。
- **本轮遗留的未验证项**：mock 购票页座位按钮的点击交互（内置浏览器拿不到可附加视口，pointer 操作被拒），`accept-m2-35.sh` 只断回显与终点状态。要么真实浏览器手测一次并在 §3 记一行，要么在 README 里明说"只演示参数回显"。
- **M2-31 给 M2-33 留下的两条硬约束**：① `route_json` 的重编译入口只许一个（`RouteRuleService.validateAndCompile`），任何"顺手 new 一个 RouteConfig"的路径都必须带全字段——本轮的"PATCH 清空规则"就是这么种出来的；② 快照里 `rules` 已就位，一级决策（priority 升序首条生效）与 `xsl.jump.mode` 真开关落地时**不要**新增数据面对 `route_rule` 的读取。
- **行为评分（DESIGN 5.3 第 3 层）留了明确的接口**：本期已把 `risk_score` 从恒 0 变成可写（超频贡献 +25），`is_bot` 仍恒 0；评分规则表落地时，只需在 `Direct302Resolver` 的频控之后插一层打分并把 `score ≥ 40 → is_bot=1 + 验证页` 判掉，事件与看板口径不用回头再改。
- **频控的两个已知边界（不在本期解，先记账）**：① 固定 60s 窗口在边界处最坏放行约 2× 阈值 → 真滑动要做就得连 Redis Cluster 的 hash tag 一起改（DESIGN 5.3 第 2 层已写明理由）；② 阈值只有"全局 + 链接级"两层，租户级覆盖与配额（DESIGN 5.3 第 1 层"按租户可配"、6.1 三级配额）是后面的独立任务。
- **M2-01 事件链批量化已收口（2026-10-03）**：`async_insert(wait=1)` + 5000 行/批 + producer 攒批 + 4 分区并行，chain-live 平顶 12,185 → **18,193 QPS（+49%）**、追赶 29,578 → 41,223 events/s、事件链 CPU 2.17 → 0.92 核，丢失率全程 0.0000%。**目标 ≥20k 未达成（差 9%）**，且瓶颈已换人：数据面自己（jump ≈1.4 核×2 + nginx ≈1.3 核）成了新的大头，事件链不再是限制。
- **下一根杠杆（若继续抬上限，按性价比排序）**：① 跳转路径现在每请求两次 Redis 往返（频控 `sl:rl:*` + 配额 `sl:cnt:*`），可以合成一个 Lua 脚本一次往返——这是 M2-11 为了职责分离留下的债，收益直接打在数据面 CPU 上；② nginx worker/连接与 keepalive 参数（M2-00 只做了观测没调过）；③ 再往下才是 CH。做 ① 时注意：合并后一次脚本失败会同时丢掉频控与配额，降级路径要分别可观测。
- **对外数字要重跑**：宿主内存饱和（可用 116–334MiB、compressor ~7.3G）下得到的 6.7k–18.2k QPS 只能当"条件下界"用；正式引用前在一台只跑本栈的宿主机上复跑 loadcurve（**≥40s 一档、同档两次一致**，见 §4）+ 三轮 wrk，并把 `env_snapshot` 一并存档。频控基线同理——单连接那一档离 3 万只差 7%，安静宿主上大概会翻过线，但要用安静宿主的数。
- 签发 TPS 的复测需要预签名或 JMeter 方案（今天的 396–541 TPS 是 python 客户端上限，不是服务端结论）。
