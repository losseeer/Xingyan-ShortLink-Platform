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
| 其余 M2 项（Cuckoo、route_rule 双轨、UA 引擎、中间页、评分、配额、Sentinel） | ✅ M2 计划内 | 未开始 | 进入前按 M1 格式细化任务表 |

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
| 频控 Lua 基线（8.1-A 最后一项未测） | ≥3 万次/s（目标未写连接数，见 §4） | 同一份脚本、`-P 1` 不流水线：**单连接 27,803/s**（avg 0.034ms、p99 0.095ms，比目标低 7%）、**10 连接 166,852/s**、**50 连接 211,894/s**（超 5.6–7.1 倍）。按"能否撑住跳转"判定：**达标**（jump 端到端上限 ~13.8k QPS 由两实例分摊） | `bench/reports/2026-10-03-1652-m2-14-ratelimit-lua.md` |
| 频控上线后跳转不退化 | P99<50ms @ ≥2000 QPS 维持 | ✅ 仍达标：40s 档复跑 4,536／5,389／5,958 QPS 时 P99 = **5.36／20.32／20.61ms**；jump 自测均值 0.124–0.247ms → **0.328–0.937ms**（每请求多一次 EVAL + 阈值判定），绝对值仍 <1ms、尾延迟构成未变 | `bench/reports/2026-10-03-1656-m2-00-jump-loadcurve.md`（同日 20s 档首跑见 `-1653-`，其 c=4 的 p99 50.79ms 是采样噪声，见 §4） |
| 配置变更后的旧验收复跑 | 动了 nginx/jump/compose 三处配置，不得回归既有链路 | ✅ **0 回归**（2026-10-03 14:38，同一台机器、改后重启的栈上）：`accept-m1-07.sh` **15/15**（302/404/403/410 语义 + 冷码回源二查命中缓存 db 计数 2.0→2.0）、`accept-m1-10.sh` **19/19**（三域同一 Location、双实例承载 32/32、滚动重启窗口 5,632 击全 302／**0×5xx**、管理面宕机不影响跳转、未登记 Host 444） | 脚本输出即凭证；改动面＝`nginx.conf` 的 `log_format`（新增 `rt/uct/urt`）、`sl-jump/application.yml`（开启 HTTP 分位数）、compose 的 jump `mem_limit` |

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

## 6. 下期待办与风险

- **防刷主线已收口（2026-10-03）**：M2-10…M2-15 全部完成，五套旧验收 + `accept-m2-13` 在新 build 上全绿（§3 末两行），两处 M1 遗留改名做掉。下一项在两者之间选：**M2-01 事件链批量化**（把跳转 QPS 上限从 ~14k 抬到 ≥20k，M2-00 的归因直接指向它），或 M2 粗列表里的 **route_rule 双轨 + UA 路由引擎 + 中间页**（动态路由那一半，出口标准"UA 重放脚本全绿"）。
- **行为评分（DESIGN 5.3 第 3 层）留了明确的接口**：本期已把 `risk_score` 从恒 0 变成可写（超频贡献 +25），`is_bot` 仍恒 0；评分规则表落地时，只需在 `Direct302Resolver` 的频控之后插一层打分并把 `score ≥ 40 → is_bot=1 + 验证页` 判掉，事件与看板口径不用回头再改。
- **频控的两个已知边界（不在本期解，先记账）**：① 固定 60s 窗口在边界处最坏放行约 2× 阈值 → 真滑动要做就得连 Redis Cluster 的 hash tag 一起改（DESIGN 5.3 第 2 层已写明理由）；② 阈值只有"全局 + 链接级"两层，租户级覆盖与配额（DESIGN 5.3 第 1 层"按租户可配"、6.1 三级配额）是后面的独立任务。
- **M2-01 事件链批量化（由 M2-00 的归因引出）**：ClickHouse 从"每批 JSONEachRow 直插"改为可控批量（攒批 + `async_insert`/`wait_for_async_insert=0` 口径待定）+ consumer 拉取节奏与 `max.poll.records` 调参 + jump 侧 Kafka producer 批参数（linger/batch/缓冲）。目标：全链路 QPS 上限从 ~14k 抬到 ≥20k，且 P99@目标负载不退化。属于设计变更，先按 M1 格式细化任务表再动代码。
- **对外数字要重跑**：宿主处于内存饱和时得到的 4.5k–13.8k QPS 只能当"条件下界"用；正式引用前在一台只跑本栈的宿主机上复跑 loadcurve（**≥40s 一档**，见 §4）+ 三轮 wrk，并把 `env_snapshot` 一并存档。频控基线同理——它是在有负载干扰的下午测的，单连接那一档离 3 万只差 7%，安静宿主上大概会翻过线，但要用安静宿主的数。
- 签发 TPS 的复测需要预签名或 JMeter 方案（今天的 396–541 TPS 是 python 客户端上限，不是服务端结论）。
