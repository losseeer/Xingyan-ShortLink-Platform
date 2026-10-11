# 星演宣发短链平台设计文档

**项目名称**：星演宣发短链平台（Xingyan ShortLink Platform）

**版本**：v2.14.0（v2.0 修订自初稿，v2.1 单机环境收敛，v2.1.1 修正单机/单实例混同，v2.2 确定 Vue 看板与 monorepo 代码组织，v2.2.1 随 m1 收口在 1.3 增"实测"列，v2.2.2 回填 M1-11 压测实测值含未达标项，v2.2.3 由 M2-00 修正延迟 SLO 的判定负载并回填归因，v2.3.0 防刷主线：频控第 2 层落地口径与 access_limit 对账落点，v2.4.0 M2-01 事件链批量化并改写容量上限归属，v2.5.0 动态路由引擎的实现口径（特征表自维护、能力边界、真回退开关），v2.6.0 M2-31 route_rule 双轨落地：`route_json.rules` 实际形状与六条落地口径，v2.7.0 M2-32 UA 特征表落地形状与 `device_type/os` 取值词汇表，v2.8.0 M2-33 一级决策口径（匹配语义、四类 target、中间页不可缓存、真开关的装配层），v2.9.0 M2-34 mock 购票页的回显方式与 443/自签证书前提，v2.10.0 M3-01 对账键 `click_id` 进明细表，v2.11.0 M3-02 outbox 同步器落地：状态机、version 条件写（"强校验通道"的具体形态）、`xsl:route:evict` 广播与 `/internal/**` 的可达性，v2.12.0 M3-03 归因回传落地：发送端与事实源、`batch_id` 内容寻址、接收端幂等与 checksum，并补上 `<2%` 的判定式与 `valid_pv/bot_pv` 的临时口径，v2.13.0 M3-04 端到端对账落地：假订单接口与 `order_id` 幂等、对账拆成核对工具与验收脚本两份、窗口对齐与负向样本，v2.13.1 M3-04 收口回填：① 也配负样本（"只点击不回传"必须报 100.00% 而不是 `n/a`）、② 的窗口按订单自带的 `first_click_time` 而非 `create_time`（两把时钟）、核对工具的机器可读行改成一字段一值、8.5 的订单计数补 `rejected_validation`；v2.14.0 M3-05 看板定口径：只读 stats 四条接口的实际契约与可达性边界（浏览器不持密钥、聚合数字对入口开放这条如实写进 9.1 风险表）、取数走明细 `FINAL` 并由 24h/7d 实测决定是否换聚合表、`blocked_pv` 定义、本期不做真转化、前端依赖收敛（去 Element Plus/TS/Pinia）、console-web 的部署形态改成 nginx 静态 + 8081 同源反代；修订记录见附录 A）

**项目性质**：个人独立项目（非企业合作项目）。全部组件按常规个人 PC（16G 内存、Docker Compose 单机）部署设计；原企业级能力（分片、多可用区、跨团队契约）保留为**设计推演**，实现上做单机化收敛并全部可本地验证（见 3.3、8.1）。

**定位**：面向演出票务场景的宣发追踪与流量调度中间层（模拟），提供跨平台链接分发、渠道归因、动态路由与安全核验能力。

---

## 一、概述

### 1.1 业务背景与痛点

星演票务作为综合类现场娱乐票务平台，业务覆盖演唱会、话剧、体育赛事。宣发链条存在三类核心痛点：

1. **跨平台引流断层**。宣发需触达抖音、微信、微博、小红书等渠道，各平台对链接形态限制不同。用户从"看到演出信息"到"完成购票"需要复制、切换 App、搜索，路径过长导致意向用户在跳转环节大量流失。
2. **渠道归因黑盒**。主办方多渠道同时投放时，无法量化"哪个渠道带来多少点击、多少购票转化"，宣发预算分配依赖经验而非数据。
3. **安全与风控压力**。票务黄牛问题严重，短链接入口若缺乏防刷机制，会成为脚本批量探测票务信息的通道；同时宣发数据若不含反作弊标记，渠道看板会被刷量数据污染。

### 1.2 项目定位与目标

**一句话定位**：星演宣发体系的"流量入口 + 数据触角"，承载宣发物料与购票页之间的短链生成、跳转、追踪与安全控制。

**本期目标**：

| 目标 | 度量 |
|---|---|
| 缩短跳转路径 | 跳转链路端到端 P99 < 1s（不含目标页加载），服务端处理 P99 < 50ms |
| 渠道归因可量化 | 归因参数透传覆盖率 ≥ 98%，ClickEvent 端到端可见延迟 < 30s |
| 抗刷 | 高风险流量识别并降级，渠道看板区分"有效点击/疑似刷量" |
| 规模化 | 架构可扩展至 500+ 演出项目、峰值 5 万 QPS（水平扩展推演见 8.1-B）；个人 PC 单机基线：跳转 ≥ 2000 QPS、全链路本地可承载（8.1-A） |

**非目标（明确不做）**：

- ❌ 不做星演购票主链路（选座、下单、支付、库存）。
- ❌ **本期不做电子票入场核验**（初稿场景四）。理由：核验是强一致、不可降级的环节，与本系统"尽力而为的流量层"定位冲突，短链故障直接影响入场。该场景移到扩展规划（第十二章），且届时必须配套离线兜底才可上线。
- ❌ 不做通用埋点平台，仅覆盖短链点击域内的行为数据。

### 1.3 成功指标（SLO）

以下为**设计目标值**，以个人 PC 单机压测（wrk/JMeter，见 8.1-A）回填实测数据；延迟类指标在 loopback 环境达标，吞吐类以单机基线为准，企业级数字标注为水平扩展推演。**简历/分享中引用的所有数字必须来自本节实测记录**（commit 内保存压测输出），不得以目标值冒充实测。初稿中以"已达成"口吻出现的数字统一收敛至此（见附录 A）。

"实测"列只登记**已存档、可重跑复现**的数字：验收脚本输出进 `scripts/accept-m1-*.sh`，压测输出进 `bench/reports/`（M1-11 于 2026-10-01 补跑，前置是本机 Docker VM 内存提到 8G）。未达标项照实写未达标并附瓶颈分析，**不为凑齐一列而引用目标值**。

| 指标 | 目标 | 实测（单机：`m1` 收口验收 + M1-11 压测补跑） | 凭证 |
|---|---|---|---|
| 跳转接口可用性 | 99.9%（单机连续 72h 压测无 5xx） | 滚动重启窗口 3760 击 **0 次 5xx**；M1-11 三轮共 **3,614,754 击，非 302 响应 0 次**（服务侧计数交叉验证）；**72h 连续压测未跑** | `scripts/accept-m1-10.sh` C/D 段、`bench/reports/2026-10-01-1325-m1-11-jump.md` |
| 跳转服务端延迟 | P50 < 10ms，P99 < 50ms（**判定负载＝目标负载 ≥2000 QPS**，M2-00 明确此口径） | **达标**：c=4 点 5,002 QPS（约 2.5× 目标）P50 0.646ms／P99 21.08ms，c=8／c=16（6,439／6,459 QPS）P99 23.76／37.74ms，三点均在阈值内；P50 直到 c=64（6.92ms）仍未破 10ms。**c=32 起 P99 越线**（116ms），且与 c=64（103ms）不单调——该段同时受本机宿主饱和影响，属容量描述而非 SLO 违例，对外引用前需在安静宿主复跑。归因：jump 自测处理 0.12–1.62ms、nginx `urt≈rt`、线程 blocked=0／runnable≈23 ⇒ CPU 排队而非业务代码慢；GC 假设已由对照实验证伪（G1+768M 无改善）。**M2-11 频控进路径后同日复跑**（40s 档）：4,536／5,389／5,958 QPS 时 P99 = **5.36／20.32／20.61ms 仍达标**，jump 自测均值由 0.124–0.247ms 升到 0.328–0.937ms（每请求多一次 Redis EVAL + 阈值判定），绝对值仍 <1ms、尾延迟构成未变；`bench/reports/2026-10-03-1656-m2-00-jump-loadcurve.md`。**M2-21/22 事件链批量化后再复采**：c=4 三档独立测量 P99 = **1.53／2.08／2.56ms**（QPS 8,483／7,344／6,697），c=8/16 = 2.09/4.29ms，全曲线恢复单调；jump 自测均值回落到 0.163–0.215ms（CPU 不再饱和，频控那两次 Redis 往返的等待被消化掉）。值得注意的是**最忙的那轮 loadavg 11.19 仍只 2.56ms**，而改前 loadavg 5.84 却测到 49.26ms ⇒ 之前"p99 跨轮不可复现"本身就是 CPU 饱和的症状，不是测量方法缺陷（见 8.1-A 第 4 条与 ITER-M2 §4）；`bench/reports/2026-10-03-1955-…`、`-2001-`、`-2003-` | `bench/reports/2026-10-03-1414-m2-00-jump-loadcurve.md`、`bench/reports/2026-10-03-1418-m2-00-jump-attribution-c64.md`、`bench/reports/2026-10-01-1348-m1-11-jump-g1.md` |
| 短码生成接口延迟 | P99 < 20ms，可用性 99.9% | **未达标**：16 并发 ×2000 次实测 p50 36.5 / p95 61.4 / **p99 91.6ms**，0 失败、2000 码全唯一；工具口径=python 逐请求 HMAC（含 nginx+gateway 两跳与客户端自身开销） | `bench/reports/2026-10-01-1355-m1-11-create.md` |
| 跳转链路缓存命中率 | > 99.5% | **100.000%**（三轮分层增量 local 1,421,632 / redis 5,652 / **db=0** / miss=0：热码全程未回源） | `bench/reports/2026-10-01-1325-m1-11-jump.md`、`scripts/accept-m1-07.sh` |
| ClickEvent 丢失率 | < 0.01%（本地重试队列兜底后） | **0 / 1,104,470**（M1-11 停 consumer 打 45s 造积压，放开后 33s 追平，生产数=CH 落库数差值 0）；另有 M1-09 的 0/1204 含 Kafka pause 走 WAL 的演练 | `bench/reports/2026-10-01-1336-m1-11-kafka.md`、`scripts/accept-m1-09.sh` |
| 归因数据看板延迟 | 分钟级（≤ 5min） | 正常链路 **2s**（1003 条批量可见）；极端积压下 **33s** 追平 110 万条（consumer 追赶吞吐 33,469 events/s） | `scripts/accept-m1-09.sh`、`bench/reports/2026-10-01-1336-m1-11-kafka.md` |

---

## 二、业务场景

### 2.1 场景一：跨平台一键直达购票页

主办方在抖音、微博、微信等渠道统一使用短链接作为购票入口。用户点击后系统按环境选择跳转目标：

- 微信内 → 中间页唤起星演小程序购票页，失败降级 H5；
- 抖音内 → 中间页尝试 URL Scheme 唤起星演 App，失败降级 H5；
- 普通浏览器 → 302 直达 H5 购票页。

> **技术边界说明（修正初稿）**：服务端 302 一次响应只能给出一个 Location，**无法感知"唤起失败"**。因此"尝试唤起 + 失败降级"必须由前端中间页完成（Scheme 唤起后 setTimeout 内 `visibilitychange` 未触发则跳 H5），微信小程序场景优先使用 URL Link / URL Scheme 由服务端生成（微信开放能力接口），而非裸 `weixin://` 拼接。服务端动态路由只负责"选定第一个目标 + 决定是否需要中间页"。详见 6.2。

核心指标：跳达到位率（用户最终停留在购票页比例）、平均跳转层级数。

### 2.2 场景二：渠道归因与宣发效果量化

本系统对星演宣发体系最具增量价值的场景。每个宣发渠道（博主 A、抖音信息流、粉丝群 B、线下海报 C）使用独立短链接，链接绑定 `channel_id / campaign_id / promoter_id`。用户点击后：

1. 参数随跳转写入目标 URL query string；
2. 星演购票页接收后存入会话上下文，订单创建时写入归因字段；
3. 点击数据经 Kafka → ClickHouse 聚合后回传宣发数据平台，与订单数据 Join 生成"渠道 → 点击 → 转化"漏斗。

主办方据此获得"抖音渠道 12 万点击 / 转化率 3.2%；博主 A 8000 点击 / 6.1%"级别的决策数据。

**依赖约定**：归因闭环依赖目标购票页配合（接收并透传归因参数、落库订单归因字段），字段契约见附录 B。个人环境自建 **mock 购票页 + mock 宣发平台**等价实现（见第十一章风险 #1）。

### 2.3 场景三：粉丝裂变活动的追踪底座

为裂变活动（如"最热现场加热"）的每个参与用户生成专属短链（支持自定义短码，如 `xy.lk/jie-userA`），统计该链接的点击量与带来的新客。配套有效期与访问次数上限，防止被机器人刷量。

> 自定义短码需通过敏感词审核与保留字校验（见 9.3）。

### 2.4 场景四（降级为扩展方向）：电子票短链入口

初稿将"电子票核验安全入口"列为核心场景，本版**移出本期范围**（理由见 1.2 非目标）。作为扩展方向保留，且要求：核验链路必须支持服务端不可用时的离线兜底（如主链路 App 内离线二维码），短链入口仅作增强而非唯一路径。

---

## 三、总体架构

### 3.1 分层架构

```
┌─────────────────────────────────────────────────────────────┐
│                       接入层                                 │
│   Spring Cloud Gateway                                      │
│   路由 / 多维度限流(Sentinel) / 内部API鉴权 / 访问日志        │
│   多域名接入（域名池，抗封禁轮换，见 9.5）                    │
├─────────────────────────────────────────────────────────────┤
│                      应用服务层                              │
│  ┌───────────┐ ┌───────────┐ ┌───────────┐ ┌───────────┐   │
│  │ 短链生成   │ │ 跳转服务   │ │ 动态路由   │ │ 归因服务   │   │
│  │ Service    │ │ Service    │ │ Service    │ │ Service    │   │
│  └───────────┘ └───────────┘ └───────────┘ └───────────┘   │
│  ┌───────────┐ ┌───────────┐ ┌───────────┐                │
│  │ 统计服务   │ │ 风控防刷   │ │ 租户/配额   │                │
│  │ Service    │ │ Service    │ │ Service    │                │
│  └───────────┘ └───────────┘ └───────────┘                │
│  （同一 Spring Boot 应用逻辑模块起步；跳转服务独立部署，        │
│    与管理面隔离，见 3.3）                                    │
├─────────────────────────────────────────────────────────────┤
│                        数据层                                │
│  Redis Cluster   路由缓存 / 短码池 / 频控计数 / 布隆·Cuckoo   │
│  MySQL           链接元数据·租户·规则 (ShardingSphere)        │
│  Kafka           ClickEvent 异步管道 (at-least-once)        │
│  ClickHouse      点击明细 + 分钟级聚合，看板查询               │
├─────────────────────────────────────────────────────────────┤
│                       外部集成                               │
│  宣发数据平台(归因回传) │ 星演小程序/App(跳转目标)             │
│  微信开放能力(URL Link) │ 短信/推送平台 │ IP 地理库/代理库     │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 技术选型

| 组件 | 方案 | 理由 |
|---|---|---|
| 语言/框架 | JDK 17 + Spring Boot 3 + Spring Cloud | 与 `nageoffer/shortlink` 生态一致，降低学习与招聘成本 |
| 注册/配置中心 | Nacos | 星演微服务体系标准件 |
| 缓存 | Redis Cluster | 跳转读密集（读写比约 100:1），Redis 承担 99%+ 读流量 |
| 消息队列 | Kafka | ClickEvent 异步解耦跳转与统计；峰值缓冲开票瞬间流量 |
| 分库分表 | ShardingSphere | 按 4.1 的分片策略水平扩展 |
| 限流熔断 | Sentinel | 体系标配；网关按 IP/短码/租户多维度限流 |
| OLAP | ClickHouse | MergeTree 时序聚合，支撑渠道看板 |
| 链路追踪 | SkyWalking / OpenTelemetry | 星演体系标配（新增，初稿缺失） |
| 前端看板 | Vue 3 + Vite + ECharts | 主办方轻量看板 `console-web`：链接列表、渠道漏斗、有效/疑似刷量双指标（v2.2 决定，不做管理后台全家桶）。**v2.14.0 收敛：不引 Element Plus**——三页的实质是"一张表 + 两张图 + 几个卡片"，而组件库是为全家桶准备的（就是本行自己写的"不做全家桶"）；同理不引 TypeScript 与 Pinia（三页各自取数、无跨页共享状态）。见 DEVELOPMENT_PLAN「M3-05…M3-08 的落地口径」第 9 条 |

### 3.3 单机部署拓扑（Docker Compose，常规个人 PC）

逻辑上仍坚持**数据面与管理面分离**：跳转服务（`/s/{code}`）独立进程部署、不依赖管理面写路径，管理面宕机不影响存量链接跳转——该原则在单机下依然成立，且可用 `docker stop` 直接验证，是核心架构表达点。

> **单机 ≠ 单实例**：宿主机只有一台，但实例数由资源预算决定、与机器数无关。因此**数据面默认就是多实例**（jump ×2 + Nginx upstream），否则无状态设计、负载均衡、滚动重启不中断、Pub/Sub 跨实例缓存失效广播（8.4）等核心设计点全部退化为纸面声明——这些恰是本项目的价值所在，单机环境一个都不该省。中间件默认单实例的原因则是诚实的：同宿主主从共享同一块盘和同一电源，容灾收益为零、只剩内存开销；复制/故障转移的**语义**演示放入可选 profile。

`docker compose up -d` 一键拉起，宿主机建议 16G 内存（macOS Apple Silicon / Windows+WSL2 均可）：

| 容器 | 内存上限 | 说明 |
|---|---|---|
| MySQL 8 | 1G | 单实例；分片以同实例两库 `xsl_00/xsl_01` 演示（见 4.1） |
| Redis 7 | 512M | 单实例；Cluster 能力以单机 + Lua 等价实现，键设计预留 hash tag 兼容跨槽约束 |
| Nacos standalone | 1G | 内嵌 derby；资源紧张时切「本地配置文件模式」降级（配置中心仅作注册发现） |
| Kafka（KRaft，免 ZK） | 1G | 单 broker，topic 1 分区起步（扩容路径见 8.1-B） |
| ClickHouse | 2G | 单节点单副本 |
| gateway | 768M | -Xmx512m |
| app（管理面） | 768M | -Xmx512m |
| **jump（数据面）×2** | 2×768M | `--scale jump=2`，Nginx upstream 轮询；验证无状态、滚动重启（逐个 kill 观察零中断）、Pub/Sub 多实例失效收敛。上限由 2×512M 提到 2×768M：压测中常驻 RSS 顶在 473–509MiB/512MiB，留的是 **OOM-kill 余量**而非性能（GC/内存两条线均已实测排除，见 8.1-A 第 1 条与 ITER-M2 §5） |
| mock 宣发平台（sl-mock） | 256M | M3-03 起是真模块：接收归因回传（HMAC 验签 + checksum + `batch_id` 幂等）与假订单接口，MySQL 独立库 `xsl_mock`；端口 8050 只在 compose 内网与 localhost 可达，**不挂 nginx 对外路由**（它是"外部平台"的等价物）。mock 购票页仍是 nginx 静态回显，不占容器（6.2） |
| console-web（Vue 看板） | 0（不占独立容器） | v2.14.0 落地形态确定：**不是新容器**，而是既有 nginx 静态托管 `sl-console-web/dist` + 同源反代。新开一个 `listen 8081` 的 server block（同 `server_name xy1.test`）：`/` → dist（`try_files … /index.html`）、`/api/v1/stats/` → consumer:8040，浏览器只跟一个 origin 说话所以不需要 CORS。**反代的是 consumer 的只读统计而不是管理面 API**（原计划写的是"同源反代管理面 API"，那句在 M3-05 定口径时改掉了，理由见 7.2）。**为什么是 8081 不是 80**：80 端口上 `xy*.test` 的 `/` 已经被 jump 占了（短码就在根路径），看板抢不到前缀。原计划的 128M 是"独立容器 + 内存预算"的写法，实际形态里没有那个进程，就不占那份预算（内存预算表按现实改，见 DEVELOPMENT_PLAN 二-C 第 10 条） |
| Sentinel dashboard | 512M | 可选 profile，仅观察用 |
| Prometheus + Grafana | 1G | 可选 profile（`--profile observability`） |
| Redis 主从×3 + Sentinel | +1G | 可选 profile（`--profile redis-ha`）：单机上完整演示异步复制 + 自动故障转移，客户端 Sentinel 配置切主不感知 |

全容器默认合计 ≈ 10G（可选项另计），16G 宿主留安全余量；8G 机器走「极简模式」：去 Kafka/ClickHouse/Nacos，事件改 Redis Stream、统计落 MySQL 聚合表、配置走本地文件——核心链路完整保留，**jump 仍保持 ×2**（见 8.1-C）。所有中间件仅监听 localhost，无公网暴露面。

### 3.4 代码组织：单 monorepo ≠ 单体（v2.2 决定）

仓库组织与架构粒度是**正交的两个维度**：是否"微服务"由进程边界决定（独立部署、独立扩缩、网络通信），与代码放几个 git 仓库无关。本设计的形态：

- **仓库维度：单 monorepo**（Maven 多模块 + 一个前端模块；当前 reactor 是 6 个 Java 模块，`sl-console-web` 见下图标注）：

```
Xingyan-ShortLink-Platform/          # 实际仓库名（下图按 m1 收口时的真实布局，M3-03 时 sl-mock 已成模块）
├── sl-common/          # DTO、错误码、归因拼接、短码与雪花生成、准入校验、HMAC 签名、路由缓存契约
├── sl-gateway/         # Spring Cloud Gateway：HMAC 鉴权、时间戳窗口、nonce 防重放
├── sl-admin/           # 管理面：签发、短码池、准入白名单、outbox 补偿、缓存回写与失效广播
├── sl-jump/            # 数据面：纯 302 跳转 + 事件投递（Kafka/WAL），无状态 ×2 实例
├── sl-consumer/        # Kafka → ClickHouse 批量幂等写入 + 统计查询 + 归因回传（M3-03）
├── sl-mock/            # 扮演外部世界（M3-03 起进 reactor）：接收归因回传 + 假订单接口（M3-04），独立库 xsl_mock
├── sl-console-web/     # Vue 3 主办方看板：三页只读视图（M3-06 交付；不在 Maven reactor 里，构建走 `make web` = npm ci + vite build，产物 dist/ 不入库）
├── deploy/
│   ├── compose/        # docker-compose.yml、nginx 配置、MySQL/ClickHouse 初始化、overrides/、.env（不入库）
│   ├── docker/         # 应用镜像 Dockerfile（宿主 jar + JRE 一层）
│   └── maven/          # 构建用 settings 镜像配置
├── scripts/            # 一任务一验收脚本 accept-m1-NN.sh + build-images/demo/init-ch-user/rotate-mysql-password + lib-devenv
├── bench/              # 压测脚本、wrk lua、lib-bench.sh + reports/ 存档（1.3 口径凭证）
└── docs/               # DESIGN.md、DEVELOPMENT_PLAN.md、iterations/ITER-<M>.md + 模板
```

> 命名口径：模块目录 = Maven `artifactId` = Java 包末段（`sl-jump` ↔ `com.xingyan.shortlink.jump`），一一对应；
> 验收脚本按任务编号命名（`scripts/accept-m1-07.sh` ↔ 计划表 M1-07），压测产物按
> `YYYY-MM-DD-HHMM-m1-<任务>-<对象>[-<变体>].md` 命名，两者都保证"看名字就知道对应哪条验收项"。


- **部署维度：进程已拆分**（gateway / admin / jump×2 / consumer 各自独立进程与伸缩），即 **monorepo + 细粒度部署（分布式单体）**——不是传统单体，也不引入服务网格、每服务独立库等全套微服务装具。Nacos 仅做配置下发与注册发现。
- 单人开发下多仓只有版本联动摩擦、没有收益；模块间依赖方向由 Maven 强制（`sl-jump` 不得依赖 `sl-admin`），这条约束比仓库数量更能防架构腐化。面试口径："按微服务粒度部署、按 monorepo 管理"是 `nageoffer/shortlink`（单仓多服务）的同款形态。

---

## 四、数据模型设计

### 4.1 分片策略（修正初稿关键缺陷）

**初稿缺陷**：`short_link` 按 `tenant_id` 分片，但跳转查询按 `short_code` → 每次跳转需广播所有分片，读路径不可接受。

**修正方案：读写路径拆表 + 全局索引。**

| 表 | 分片键 | 服务路径 | 说明 |
|---|---|---|---|
| `link_route`（跳转路由表） | `short_code` 哈希 | 数据面跳转 | 只存跳转必需字段，`SELECT ... WHERE short_code=?` 精确路由单分片 |
| `short_link`（链接元数据表） | `tenant_id` 哈希 | 管理面 CRUD/看板 | 状态、配额、审计所需全字段 |
| `code_tenant_index`（全局索引表） | `short_code` 哈希 | 管理面反查 | `short_code → tenant_id`，供管理接口鉴权与两表关联 |

跳转链路平时只走 Redis，`link_route` 是回源表；两表写入通过本地事务表（outbox）+ 消息保证最终一致（见 8.4）。

> **个人环境落地**：读写拆表与分片键设计作为架构能力保留，实际以单 MySQL 实例建 `xsl_00`/`xsl_01` 两库、ShardingSphere 按 `short_code`/`tenant_id` 哈希路由（表结构完全一致），验证路由正确性（断点观察实际命中库）即可，无需真实 256 分片。

### 4.2 核心表结构

**short_link（链接元数据，MySQL，按 tenant_id 分片）**

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint | 雪花 ID |
| short_code | varchar(12) | 短码，唯一索引（配合全局索引表） |
| origin_url | varchar(2048) | 原始长链接（入库前通过安全校验，见 9.2） |
| tenant_id | bigint | 租户 ID（= 演出项目/主办方） |
| channel_id | varchar(64) | 渠道标识 |
| campaign_id | varchar(64) | 宣发活动 ID |
| promoter_id | varchar(64) | 推广者/博主 ID |
| redirect_type | tinyint | 1-302 2-中间页(动态降级) ；**不支持 301**，理由见 5.2 |
| expire_time | datetime | 过期时间，NULL 为永久 |
| access_limit | int | 最大访问次数，NULL 不限 |
| status | tinyint | 0-正常 1-停用 2-封禁 3-待审核 |
| review_remark | varchar(256) | 审核备注 |
| create_time / update_time | datetime | |

**link_route（跳转路由表，MySQL，按 short_code 分片）**

| 字段 | 类型 | 说明 |
|---|---|---|
| short_code | varchar(12) | 主键 |
| route_json | json | 序列化路由配置：origin_url + 规则引用 + 有效期/次数上限 + status |
| version | bigint | 配置版本号，缓存一致性用（见 8.4） |
| update_time | datetime | |

**short_code_pool（预生成短码池，MySQL 登记 + Redis 分发）**

| 字段 | 类型 | 说明 |
|---|---|---|
| short_code | varchar(12) | 主键 |
| namespace | int | 池分片号 |
| status | tinyint | 0-空闲 1-已租用 2-已消耗 |
| lease_owner / lease_time | | 租约追踪，支持超时回收 |

**route_rule（动态路由规则，MySQL，随 short_link 同分片）**

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint | 主键 |
| short_code | varchar(12) | 短码 |
| priority | int | 数字小者优先 |
| condition_type | varchar(32) | ua_container / device / geo_province / time_window |
| condition_op | varchar(16) | eq / in / gt / between（新增，条件需比较符） |
| condition_value | varchar(128) | 条件值 |
| target_url | varchar(2048) | 目标 URL |
| target_type | varchar(16) | h5 / app_scheme / wx_urllink / intermediate |

**tenant（租户表，不分片）**：`tenant_id, name, type(主办方/项目/内部), quota_links, quota_qps, status, api_key, api_key_status`。

**audit_log（操作审计，按 tenant_id 分片）**：谁在何时创建/停用/改路由/换域名，票务场景合规追溯必需（新增）。

**domain_pool（域名字典表，新增）**：`domain, status(可用/被举报/封禁), weight, switch_time`，支撑 9.5 抗封禁轮换。

**outbox（本地事务表，随 short_link 同分片键，v2.1.1 补入——4.1/8.4 一直引用但此前漏建条目）**：`id, aggregate_type, aggregate_id(=short_code), event_type, payload_json, status(待投递/已投递/失败), retry_count, create_time`；异步任务据此同步 `link_route.route_json` 与缓存失效。v2.11.0 起这个"异步任务"有了具体形态：sl-admin 的 `OutboxSyncService`（定时 + `POST /internal/outbox/replay` 手动触发同一轮逻辑），落地口径见 8.4。

> 规则存储双轨澄清：`route_rule`（行式，管理面唯一真源，支持逐条校验/冲突检查）在保存时编译为 `link_route.route_json`（文档式，数据面唯一读取源）；两者不一致时以重编译为准，outbox 事件驱动。

**ClickHouse 表**

`click_event`（明细，MergeTree，按 `toYYYYMM(click_time)` 分区、`click_time` 排序键）：

| 字段 | 类型 | 说明 |
|---|---|---|
| event_id | String | 幂等键（Kafka 重复消费去重，ReplacingMergeTree） |
| short_code | String | |
| click_id | String | **对账键**（M3-01 补）：值就是跳转 URL 上的 `xy_click_id`，也是订单回传时带回的那个串，所以"这笔订单来自哪一次点击"能按它 join。空串有两类含义且不可区分地共存：① M3-01 之前的历史行（那时有这个值从没进过消息体，不回填）；② 被频控拦下的点击（压根没有跳转目标，也就没有 URL 上的 click_id）——要区分二者看 `risk_score`（后者恒为 25） |
| click_time | DateTime | |
| ip_hash | String | 加盐 SHA-256，盐每日轮换（PIPL，见 9.6）。IP 取 nginx 覆盖写入的 `X-Real-IP`，不用客户端可伪造的 `X-Forwarded-For` 首元素（口径同 5.3 第 2 层，v2.3.0） |
| user_agent / referer | String | |
| device_type / os | String | 服务端 UA 解析（自维护特征表，见 6.2）。**取值词汇表**：`device_type` = 容器 key（`wechat`/`douyin`/`qq`/`weibo`/`alipay`/`unknown`），与 `route_rule` 的 `ua_container` 条件值**同一个字符串**；`os` = 设备大类 key（`ios`/`android`/`desktop`/`unknown`），与 `device` 条件值同源。列名与语义不完全对得上（`device_type` 装的是容器）是 M1 建表时就定下的事实——`click_stat_minute_mv` 里的 `countIf(device_type='wechat')` 与 M1-11 存档数据都按这个字面量在跑，改名要动 CH 迁移并让历史行的分类失效，所以本轮只把取值钉死、不改列名 |
| province / city | String | IP 地理库离线解析，不落明文 IP |
| channel_id / campaign_id / promoter_id | String | 冗余，聚合免 Join |
| utm_params | String(JSON) | |
| risk_score | UInt8 | 0-100 |
| is_bot | UInt8 | 风控判定标记 |

`click_stat_minute`（聚合，SummingMergeTree，`ORDER BY (tenant_id, campaign_id, channel_id, minute)`）：PV、UV（`uniqState` 近似）、bot_pv、分容器（微信/抖音/浏览器）PV。

### 4.3 Redis 键设计（新增，初稿散落）

| 键 | 内容 | TTL |
|---|---|---|
| `sl:r:{code}` | 路由配置 JSON（link_route.route_json） | 24h + rand(0,2h) 随机偏移防雪崩 |
| `sl:r:nx:{code}` | 空值标记（防穿透，布隆误判兜底） | 5min |
| `sl:pool:{ns}` | List，预生成短码池分片 | 常驻 |
| `sl:cnt:{code}` | 剩余可访问次数（Lua 原子扣减） | 常驻，与 DB 定期对账 |
| `sl:rl:{code}:{iphash}:{slot}` | 窗口频控计数（60s 固定槽；`slot` = epoch 分钟。实现口径见 5.3 第 2 层） | 60s |
| `sl:bloom` | Cuckoo Filter（支持删除，见 5.2 步骤 1） | 常驻 |

---

## 五、核心流程设计

### 5.1 短链接生成流程

```
主办方/运营 → POST /api/v1/links（origin_url + channel_id + campaign_id + 可选自定义短码）
    ▼
生成服务：
    1. 鉴权：API Key → 租户身份；校验状态与配额（quota_links / 速率）
    2. origin_url 安全校验：https 强制、域名白名单/审核（见 9.2）
    3. 短码分配：
       - 自定义短码 → 保留字 + 敏感词 + 唯一性校验（SETNX）
       - 系统短码 → 从 Redis 池 `sl:pool:{ns}` LPOP 批量租用（每批 1000）
         池水位 < 20% 时后台异步补充（多实例用分布式锁防重复补充）
    4. 事务写入：short_link + link_route + code_tenant_index + outbox 消息
    5. 建立/失效 Redis 缓存；布隆过滤器 add
    6. 返回短链 https://{当前主域}/{code}
```

**短码方案**：Base62 编码，系统短码使用**不可预测混淆**（雪花 ID → XOR 随机掩码 → Base62，或池预生成随机码），防止枚举遍历（见 9.3）。7 位短码空间 62⁷ ≈ 3.5 万亿，容量无忧；真实约束是池的 Redis 内存（1 亿码约 5GB，按 namespace 分片）。

### 5.2 跳转流程（数据面核心）

```
用户点击 https://xy.lk/{code} → Gateway（IP 维度限流）
    ▼
跳转服务：
    1. Cuckoo Filter 检查短码是否存在
       - 不存在 → 302 统一 404 落地页；结束
         （误判存在 → 走缓存/DB，由 `sl:r:nx:` 空值缓存兜底；
           Cuckoo 支持删除，短码封禁/下线可移除，修正初稿布隆过滤器无法删除问题）
    2. 路由配置查询：Caffeine 本地缓存 → Redis `sl:r:{code}` → link_route 回源
       （回源命中写回 Redis，TTL 带随机偏移；未命中写空值标记）
    3. 有效性检查（只读、不消耗配额的部分）：status / expire_time
    4. 频控与风控（见 5.3；命中 → 429 + Retry-After，不发送真实跳转，也**不扣配额**，
       但事件仍记录并计入 risk_score）
       —— v2.3.0 把频控提到配额扣减之前：原序（先扣 access_limit 再频控）会让攻击者
       用高频请求在数秒内烧光一条链接的配额，把限流变成"配额自杀开关"。
       仍排在存在性/状态检查之后：否则对随机码的探测会给每个猜测值都建一个 60s 频控键，
       攻击成本从"被拦下"变成"替你涨内存"。
    5. 配额扣减：access_limit（Lua 原子扣减 `sl:cnt:{code}`，超限 → 410 或自定义落地页）
    6. 动态路由匹配（见 6.2）：
       - 命中简单规则（目标为 H5/浏览器直达）→ 步骤 8
       - 命中需客户端降级的目标（小程序/App Scheme）→ 返回中间页 HTML
    7. ClickEvent 投递 Kafka：异步发送 + 失败落本地磁盘重试队列（新增，见 6.3/8.3）
    8. 返回 302 Location = target_url + 归因参数
```

**为何只用 302、禁用 301（新增说明）**：301 被浏览器/CDN 长期缓存后，后续点击不再经过服务端，点击统计与防刷全部失效，且链接失效后无法服务端回收（缓存不受控）。牺牲少量重复点击流量换取数据完整性，是本场景的正确取舍。

**归因参数透传**：目标 URL query 追加 `channel_id / campaign_id / promoter_id / utm_* / xy_click_id`（点击级 ID，供订单精确 Join）。拼接规则：目标 URL 已有 query 用 `&` 续接；所有参数值 URL-encode；**只允许追加白名单参数，禁止覆盖目标 URL 原有参数**（防参数注入）。

### 5.3 防刷风控流程（四层过滤）

1. **网关层**：Sentinel 对 `/s/{code}` 按 IP 限流，默认 60 次/分钟；按租户可配。超限返回 429。
2. **Redis 窗口频控**：`(short_code, ip_hash)` 维度 Lua 原子 `INCR + 首次 EXPIRE`（单脚本执行，消除 inc/expire 竞态）。
   - **口径澄清（v2.3.0）**：这一层实现的是**固定 60s 窗口计数**（键里带槽位），不是严格滑动窗口——初稿写"滑动"名不副实，现按实现改口径。代价是窗口边界最坏可放行约 2× 阈值；不做成滑动有两个具体理由：① 加权滑动要同时读"当前槽 + 上一槽"，多键 Lua 在 Redis Cluster 下要求两键同槽，与 8.1-B「键设计已预留无跨槽操作约束」的扩展承诺冲突（要做滑动必须同时给键加 hash tag，属另一轮改动）；② jump 是 ×2 实例，上一槽计数若走进程内缓存会因轮询被劈成两半而低估，若多读一次 Redis 则热路径上每次跳转多一个往返。
   - **IP 取值**：以 nginx **覆盖写入**的 `X-Real-IP`（= `$remote_addr`）为准，其次才取 `X-Forwarded-For` 的**末段**（nginx `$proxy_add_x_forwarded_for` 追加的那一跳），最后回落 `remoteAddr`。客户端自带的 XFF 首元素可任意伪造，不能作为频控维度（同一口径也用于 4.2 的 `ip_hash`）。
   - 命中后：返回 429 + `Retry-After`（到本槽结束的秒数），不发 302、不扣 `access_limit`（见 5.2 步骤 3）；被拦击**仍发 ClickEvent** 并置 `risk_score += 25`（本层贡献分），`is_bot` 由第 3 层评分判定后写，本层不越权标注。
   - 降级：Redis 不可用时退化为进程内窗口计数（见 8.3），并以 `xsl_jump_rate_limit_total{mode="local"}` 与正常路径区分（实现里的标签是 `result=allow|block|error` + `mode=redis|local`，不是把降级塞进 result；本句此前写的是 `{result="degraded"}`，属文档与现实不符，M2-36 按实现改正）。
3. **行为评分（同步、轻量）**：无/异常 Referer + UA 异常（无 Mobile 标志、Headless 特征）+20；命中 IDC/代理 IP 库 +30；超频阈值 +25。**score ≥ 40 → 返回验证页/安全落地页**而非真实跳转，事件仍记录并标记 `is_bot=1`。IP 库每日离线更新（新增：初稿未提更新机制，IDC 段失效则第三层形同虚设）。
4. **事后审计（异步）**：Consumer 侧模型化二次判定（如按 UA 分布突变、点击-下单时间差异常聚类），修正 `is_bot`，看板输出"有效点击 / 疑似刷量"双指标。

> 评分规则以可配置规则表落地（复用 route_rule 的条件模型思路），避免硬编码；MVP 只做第 1、2、3 层 + 第四层标记透传，模型判定放 P2。
>
> 实施进度（v2.3.0）：**第 2 层已落地**（M2-11：固定 60s 窗口 + 429 + 进程内降级）；第 1 层的 Sentinel 网关限流与第 3 层的行为评分规则表仍是独立任务（见 DEVELOPMENT_PLAN M2 粗列表），第 4 层的模型化二次判定按原计划放 P2。自本期起 `click_event.risk_score` 不再恒 0（超频贡献 +25 开始写入），`is_bot` 仍恒 0 直到评分层交付——看板的双指标口径（8.5、M3）要按这两列的真实语义取数。

---

## 六、关键模块设计

### 6.1 多租户与数据隔离

- 租户模型：`tenant_id` = 演出项目/主办方；主办方下辖多个项目共享配额。
- 隔离实现：JWT/API Key 携带租户身份 → MyBatis 拦截器自动注入 `tenant_id` 条件（含 JOIN 语句校验，防遗漏）；分片键选择见 4.1。
- 越权防护：所有按 `short_code` 的管理接口先经 `code_tenant_index` 反查归属并比对请求者身份。
- 配额：链接总数、生成速率、访问次数上限（防单租户挤占）三级配额，Redis 计数 + 定时对账。

### 6.2 动态路由引擎

**修正一处初稿的自相矛盾（M2-33）**：客户端二级降级那节原话说中间页"本身可缓存 CDN"。中间页里带着**这一次点击**的 `xy_click_id` 与归因参数（附录 B 的闭环要求购票页能拿到它们），一旦可缓存就会把 A 用户的 click_id 发给 B 用户——归因数据当场作废，且是静默作废。所以口径改为：**中间页一律 `Cache-Control: no-store`**，可缓存的只是它引用的静态资源（图标/样式）；CDN 那层收益本来就是"少一次 HTML 传输"，而我们付出的代价是归因链路，不划算。

**两级路由模型（修正初稿"302 完成唤起降级"的不可行描述）**：

- **服务端一级决策**：跳转服务读取编译进 `route_json.rules` 的**快照**（已按 priority 升序，首条匹配生效），不回查 `route_rule`；条件维度：UA 容器（wechat/douyin/qq）、device、geo_province、time_window。`route_rule` 是管理面的**行式真源**（逐条可校验、可查冲突），二者是"源→编译产物"的关系（见下"双轨"）。
- **客户端二级降级**：一级决策目标为 `app_scheme` 或小程序时，返回**中间页**（HTML，**不可缓存**，理由见本节开头那条修正）：
  - 微信容器 + 目标小程序：服务端调用微信开放接口生成 **URL Link / Scheme**（带 30 天有效期控制），中间页直接跳转；失败展示引导。
  - 抖音等容器 + App：中间页 JS `location.href = xingyan://...`，`setTimeout(2.5s)` 内未触发 `visibilitychange/pagehide` 则降级 `location.href = h5_url`；iOS 优先 **Universal Link**（无失败态、更可靠）。

`route_json.rules` 的实际形状（M2-31 落地形状；一行 = 一个条件维度 + 一个目标）：

```json
{"origin_url":"https://m.xingyan.com/item/123","version":3,"rules":[
  {"priority":10,"condition_type":"ua_container","condition_op":"eq","condition_value":"wechat",
   "target_type":"wx_urllink","target_url":"https://m.xingyan.com/item/123","scheme_url":"pages/ticket/detail?itemId=123","fallback_url":null},
  {"priority":20,"condition_type":"ua_container","condition_op":"eq","condition_value":"douyin",
   "target_type":"app_scheme","target_url":null,"scheme_url":"xingyan://item/detail?id=123","fallback_url":"https://m.xingyan.com/item/123"},
  {"priority":30,"condition_type":"time_window","condition_op":"between","condition_value":"[\"2026-09-20\",\"2026-09-30\"]",
   "target_type":"h5","target_url":"https://m.xingyan.com/shanghai/pre-sale","scheme_url":null,"fallback_url":null},
  {"priority":99,"condition_type":"default","condition_op":"any","condition_value":null,
   "target_type":"h5","target_url":"https://m.xingyan.com/item/123","scheme_url":null,"fallback_url":null}
]}
```

UA 解析库维护已知容器特征表（月度更新），未识别 UA 一律落到 default 规则（H5），保证新环境不误伤。

**实现口径（M2-30 写定，避免实现时各自发挥）**：

- **特征表自维护、可配置，不引第三方 UA 库**。单机个人项目里 yauaa/ua-parser 这类库带来的是"看起来权威"而不是"更准"：本项目只需要区分 5 个容器 + 2 类设备 + 未知。DESIGN 说的"月度更新"在单机的等价物就是**改配置不改代码**（特征表从 `application.yml` 加载）。代价如实写明：覆盖面窄于通用库，但"未识别一律 default"保证新容器只是不走高级规则，不会被打死。
- **未知事实一律不匹配**（不是"匹配失败就报错"）：`geo_province` 在没有离线地理库之前恒为未知、`time_window` 只信服务端时钟，因此这两类条件在能力接入前永远走 default。这是能力边界而不是缺陷，写清楚免得被当成已实现。
- **`xsl.jump.mode` 必须是真开关**：`direct302`（回退形态）与 `dynamic` 各对应一个 `JumpResolver` 实现，由条件装配选择。此前这个配置只存在于注释里，等于"DESIGN 第十章承诺的回退开关"没有落地——一个不能通过改环境变量退回去的架构主张，是不成立的。
- **`wx_urllink` 是接口位 + 本地 mock provider，不接真实微信开放接口**：个人项目没有公众号凭据，也不该把外部 API 写进单机验收路径。`UrlLinkProvider` 接口留好（真实实现只要换一个 bean），本期实现返回可断言的本地 mock 链接。
- **中间页由 jump 返回 200 HTML**，不新增服务：302 之后没有 HTML 控制权，所以降级判断只能在服务端给出的这一页里做——这也是"301 被禁用"（5.2）的同一取舍的另一面。响应头是 **`Cache-Control: no-store`**（M2-33 改的，理由见本节开头：页里带着本次点击的 `xy_click_id`，缓存过一次就把 A 的 click_id 发给 B）。
- **mock 购票页落地为 nginx 静态回显页**（`mock.ticketsales.test`），不建 Java 模块：它只需要回显 query/fragment 供 UA 重放断言与演示看效果；假订单接口与归因回传属 M3。
- **mock 购票页的回显 mechanics 与"可看点"的前提（M2-34）**：fragment **不会随请求上行**（浏览器只把 `#…` 留在本地），所以 nginx 侧回显 fragment 是做不到的事——页面用一小段 JS 读 `location.search` 与 `location.hash` 自己渲染。而 302 的终点是 `https://mock.ticketsales.test/…`（9.2 强制 https，不为了让演示省事就把它降级成 http），要真能在浏览器里点开需要两件事：宿主 `hosts` 里把 `mock.ticketsales.test` 指到 `127.0.0.1`，以及 nginx 在 443 上用**本地自签证书**应答（`make tls` 幂等生成到 `deploy/compose/tls/`，不入库；浏览器会警告，手动继续即可——这是本地演示，不是"证书已受信"的宣称）。缺了这两步，演示里看到的仍然是打不开，而那句"302 终点可看"就成了假承诺。

- **UA 特征表的落地形状（M2-32）**：特征表放在 `sl-jump` 的 `application.yml`（`xsl.jump.ua.containers[] = {key, tokens[]}` 与 `devices[]`，代码里不留第二份默认表），按声明顺序对 UA 小写串做**首个子串命中即定类**，全不中 → `unknown`；容器与设备是**两次独立判定**（微信里的 iPhone 同时给出 `wechat` + `ios`）。三条约束：① `key` 就是 `route_rule` 的条件值 **也是** `click_event.device_type/os` 的取值——一套词汇表，避免"规则命中微信但统计里叫别的名字"这种漂移；② `tokens` 才是 UA 里的实际串（微信 UA 里是 `micromessenger`、抖音除 `douyin` 外还要 `aweme`），所以"某容器换了 UA 标识"＝改配置；③ 但**新增一个类别**（看板多一列、规则多一个可选值）必须动 `UaContainer`/`UaDevice` 枚举，因为这三处共用词汇表——配置化它等于让三处各配一份。启动时校验 `key` 必须在枚举里，配错就直接起不来，而不是留一条永远匹配不上的静默规则。

**双轨的落地口径（M2-31）**：
- **写路径只有一条**：`route_rule` 行（与 `short_link` 同一 `tenant_id` 分片轴、同一本地事务）→ 校验器 → 编译器（按 priority 升序）→ `link_route.route_json.rules` + Redis 快照。jump 的 `sharding.yaml` 里不声明 `route_rule`，数据面因此拿不到"回查真源"这条退路——这是 3.4 气分离的延续，也让"缓存与库里不一致"变成可直接判定的实现 bug 而不是运行时自愈问题。
- **一条规则只有一个条件维度**（`(condition_type, condition_op, condition_value)` 三元组 + 一个目标）。初稿示例里"上海 **且** 大促期内"那种同条 AND 组合**未实现**：要表达就写两条不同 priority 的规则近似，或等 `route_rule` 加列。这个收窄写在这里是为了让"通用三元组"不被误读成"已支持组合条件"。
- **算子按维度收窄**：枚举类（ua_container/device/geo_province）只接受 `eq|in`，`time_window` 只接受 `between`（值形如 `["2026-09-20","2026-09-30"]`），`default` 只接受 `any`；表注释里的 `gt` 不受理——这几类条件没有可比大小语义，留着只会让人以为能配出"号段之后生效"。`app_scheme` 的 `scheme_url` 同样禁 `http(s)`（那就是 h5 规则，混淆了中间页的降级判定）。
- **`PUT /links/{code}/rules` 是整体替换**：规则集合是有顺序语义的整体，逐条 patch 的合并语义容易让人配出一套自己也预测不了结果的规则集。空数组即清空；`rules` 为空时快照里写显式 `"rules": null`，与快照其余字段同口径（null 字段一律序列化出来、跳转侧按 key 读，由 `routeJsonContainsExplicitNulls` 用例钉住），数据面按 `rulesOrEmpty()` 收口，M1 写的老快照（没有这个键）因此走同一条路径。
- **两处顺序约束**：规则校验在短码分配**之前**（非法规则不该占用 `sl:code:*` CLAIM 和池码）；`PATCH /links/{code}` 改元数据时必须把既有规则带进新快照重编译，否则"改了个过期时间"会静默清空规则——由 `patchMetadataDoesNotClearRules` 用例与 `accept-m1-06` 第 8 段钉住。
- 版本递增与缓存回写沿用 `PATCH` 那条现路径（version+1 → 写 `link_route` → 回写 Redis）。**跨实例缓存失效仍是 Pub/Sub 缺位的那格（M3）**：本期单机下靠 TTL + 冷重建收口，验收里 jump×2 的一致性以"重编译后两份快照同源"为准。

**一级决策的落地口径（M2-33）**：

- **匹配语义**：按 priority 升序取**首条命中**就出目标，不再往下看。各维度的判定方式是——`ua_container`/`device` 拿 `UaClassifier` 的 key 比 `eq`/`in`（`in` 的值是逗号分隔）；`geo_province` 在没有离线地理库之前**恒不匹配**（不是报错，见上面"未知事实一律不匹配"）；`time_window` 用 `between` 判日期区间、**含首尾**、以服务端时钟为准；`default` 恒真。全部不命中 → 没有目标 → 落 `origin_url`，这不算错误，也与"这条链接压根没规则"同形。
- **`unknown` 是被判出来的值，可以命中 `eq`**："只给认不出的流量展示引导页"是合法需求，所以不屏蔽它；但常规兜底仍应写 `default`（差别在于 `unknown` 只覆盖"扫过但没匹配上"，`default` 覆盖所有落空）。
- **四类 target 落到响应形态**：`h5` → 302 到 `target_url`（缺省则 `origin_url`）；`app_scheme` → 200 中间页，JS `location.href = scheme_url`，2.5s 内没有 `visibilitychange/pagehide` 就跳 `fallback_url`；`wx_urllink` → 200 中间页，服务端先向 `UrlLinkProvider` 取链接后自动跳，取不到就展示 `target_url` 引导；`intermediate` → 200 引导页，**不自动跳**，把落地地址做成按钮（用于"先说明再走"的场景，比如需要提示票种规则的场次）。
- **归因参数拼到每一个 H5 地址上，但不拼进 scheme**：`h5`/`intermediate` 的目标、中间页里的 `fallback_url` 与引导地址都走 5.2 的白名单拼接（同一次点击共用一个 `xy_click_id`，事件里的那个就是它）；`app_scheme` 的 `scheme://…` 原样下发——App deeplink 的归因是另一条链路（要 SDK 配合），本期 mock 不做，硬拼上去只会让"归因已贯通"这句话变成假的。
- **中间页 `Cache-Control: no-store`，且串进 HTML/JS 的每个值都要转义两次**：URL 里带的 `utm_*` 是用户可控输入，拼进 `<script>` 的字符串常量时必须按 JS 字符串转义、拼进 `href` 时按 URL/HTML 转义（9.x 的同一条要求在这里最容易被"值是我们自己校验过的"带过去——校验过形状不等于内容安全）。
- **`xsl.jump.mode` 的开关落在目标决策这一层**，用 `@ConditionalOnProperty` 在 `TargetResolver` 的两个实现之间选（`direct302` / `dynamic`，默认 `dynamic`）；**不是**两个 `JumpResolver`：链路里"频控在配额之前、被拦仍发事件、冷重建播种"这些顺序是 M2-00/M2-11/M2-12 一轮轮修出来的，复制成两份实现等于埋下一份会漂移的副本。可证伪的性质保持不变——`XSL_JUMP_MODE=direct302` 时任何请求都不会返回 200 HTML（M2-35 的开关验证段就断言这一条）。
- **规则为空的链接在 `dynamic` 下与 `direct302` 逐字节同形**：`rules` 空 → `origin_url` → 302，Location 的构造路径完全不变。这是"新默认值不影响老链路"的验证方式，也是 `accept-m1-07` 不改一行仍应 15/15 的依据。
- **`UrlLinkProvider` 本期只有 mock 实现**：返回可断言的稳定串（`https://wxaurl.cn/xsl-mock/{path 的 sha1 前 8 位}`），真实实现（凭据 + 30 天有效期 + 频控重试）不在这期，接口位与降级路径先留好。

### 6.3 归因数据链路

1. **实时写入**：跳转服务 → Kafka topic `shortlink-click`（分区键 `short_code`，同链接事件保序）。发送失败进本地磁盘重试队列（WAL），不阻塞响应。
   M2-22 起分区数 1 → 4：并行消费的单位是分区，1 分区把 consumer 的追赶吞吐硬顶成单线程。
   扩容不改变正确性——producer 按 `short_code` 选分区，**同一链接的事件仍落在同一分区内保持有序**，跨链接之间本来就没有顺序语义；幂等仍由 `event_id` + ReplacingMergeTree 消化（第 2 点）。
   M2-21 起发送侧带攒批（`linger.ms=5`、`batch.size=64KB`、`compression.type=lz4`）：`send()` 是异步的，攒批发生在 sender 线程，**不给跳转加延迟**；请求路径上唯一可能被卡的点仍是缓冲区满时的 `max.block.ms`（保持 1000ms，超时即转 WAL）。
2. **异步聚合**：Consumer 批量拉取（`max.poll` + 手动提交 offset，at-least-once），按 `event_id` 幂等去重后写 ClickHouse `click_event`；分钟级物化到 `click_stat_minute`。
   M2-21/22 的批量口径：`max.poll.records` 500 → 5000、`listener.concurrency` = 分区数（4）、写入请求带 `async_insert=1&wait_for_async_insert=1&busy_timeout_ms=200`。
   **`wait_for_async_insert` 必须是 1**：提交 offset 的前提是"数据已落库"，若让 POST 先返回、服务端后台再刷盘，进程或容器重启后这段数据既不在 CH 也回不来（offset 已提交），1.3 的 0.0000% 丢失率就成了假数字。也就是说这里换的是 **part 数量与请求次数**，不换持久性语义。
3. **归因回传**：每 5 分钟按 `(campaign_id, channel_id, minute)` 聚合推送宣发数据平台 API；宣发平台以 `xy_click_id` 与订单归因字段 Join，生成"渠道→点击→转化"漏斗。回传带 `batch_id + 校验和`，平台侧幂等。
   v2.12.0 落点细化（M3-03）：发送端是 **sl-consumer** 的 `@Scheduled`（`xsl.push.interval-ms` 默认 300000、`lookback-minutes` 默认 3）——放 consumer 而不是 admin 是因为 **ClickHouse 只有 consumer 会读**（6.4 的面分离）。事实源取 **`click_event` 明细表 + `FINAL`** 现算，不取 `click_stat_minute`：聚合表没有 `valid_pv` 列，且它靠 `async_insert` + 后台合并，part 未合并时读它本身就有重复/缺失风险，而回传给外部平台的必须是最权威的那份数（代价是每轮扫一遍窗口明细，**耗时由 M3-03 实测给出，不可接受就换回聚合表并加列**）。一轮按 `tenant_id` 分组，一个租户一个 batch；`batch_id = xsl-{firstMinute}-{lastMinute}-{sha1(规范化 items) 前 10 位}`，`checksum = sha1(规范化 items)` 全串——**id 里带内容 hash 是刻意的**：同一窗口的数会因迟到而变全，纯窗口 id 会把"更全的那一版"当重复丢掉。**失败不重试也不推进水位**（不新增任何持久化游标）：下一轮回看窗口内同批内容算出同一个 `batch_id` 重投，由接收端幂等吸收；**边界照实写：迟到超过回看窗口（默认 3 分钟）的点击不进回传**，转化率因此是偏保守的一侧。

### 6.4 统计服务

- 看板查询统一走 ClickHouse（明细抽样 + 聚合表），管理面 MySQL 不参与统计查询。
- 对外指标：PV/UV、有效点击、疑似刷量、分容器到达率、（Join 订单后）转化率。
- 与宣发数据平台的边界：本系统只负责**点击侧**事实数据与归因参数签发；转化侧口径由宣发平台定义，`xy_click_id` 为唯一对账键。
- **对账键 = `click_id`**（M3-01 落库）：点击明细、跳转 URL、订单回传三处用同一个串，"点击 ↔ 订单"因此可以 join。两条边界写清：M3-01 之前的**历史行是空串**（那个值从来没进过消息体，不回填）；**被频控拦下的点击也是空串**（没有跳转目标就没有 click_id，用 `risk_score=25` 与前者区分）。
- **对外四个量的口径钉死**（v2.12.0，M3-03 回传就用这四个）：`pv = count()`；`uv = uniqExact(ip_hash)`（明细侧用精确去重，聚合表那条 `uniqState` 近似串留给看板）；**`valid_pv = countIf(is_bot = 0 AND risk_score < 25)`**；`bot_pv = countIf(is_bot = 1)`。后两条要说实话：行为评分层（5.3 第 3 层）没做，`is_bot` **恒 0**，所以 `bot_pv` 现在恒为 0、`valid_pv` 实际等于"被放行的点击数"（唯一生效的信号是频控那个 25 分）。这是**能力边界不是链路缺陷**——评分层落地后两个式子一个字都不用改，数值自然变对。回传与看板都从这里取口径，不在别处再算一遍。

  v2.14.0 看板取数口径细化（M3-05）：**四条 stats 接口全部从 `click_event` 明细 `FINAL` 现算，不读 `click_stat_minute`**。三个理由：① 聚合表**没有"被频控拦下"这一档**（它的列是 `pv/uv_state/bot_pv/wx_pv/douyin_pv/browser_pv`），而漏斗必须有第三档；② 聚合表靠 `async_insert` + 后台合并，part 未合并时读它本身有重复/缺失的不确定性，看板要能被人拿去和直查对上；③ M3-03 已经实测过明细窗口很便宜（10 分钟 5ms / 读 6,755 行）。**但那个实测的窗口是 10 分钟，看板默认窗口是 24h、还要能拉 7d——这两个尺寸没测过**，所以 M3-05 的验收必须实测 24h 与 7d 两个窗口的 `read_rows`/耗时并写进 ITER-M3 §3；**如果数字不可接受就换聚合表**，届时代价一并认（重建 MV 加 `blocked_pv` 列、动老卷、`bot_pv` 那一档仍恒 0）。这是本项目第四次"由数字决定而不是先预防性优化"（前三次：M3-01 跳数索引、M3-03 的 `valid_pv` 列、M3-02 的 batch 大小）。
- **看板新增的第五个量**：`blocked_pv = countIf(risk_score >= 25)`（与 `valid_pv` 用的 `< 25` 是同一根门槛的两侧，阈值来源见 5.3 第 3 层那个 25 分）。要说清楚一件事：因为 `is_bot` 现在恒 0，所以 **`pv = valid_pv + blocked_pv` 当前恒成立**，而评分层交付后这个等式就会破（一条 `risk_score>=25` 的行也可能被判 `is_bot=1`）——所以看板的漏斗图**不能画成"两档相加等于总数"**，要按四档各自显示。
- **UV 在本期是精确值**：明细侧走 `uniqExact(ip_hash)`，所以 1.3 与附录 A 风险 #5 那句"±0.81% 误差"**不适用于看板页面**（那条近似串属于聚合表路径，本期没用它）。
- **窗口与粒度**：`from`/`to` 用点击侧墙上时钟（同 4.2 与 `03-mock-schema.sql` 那条约定），**左闭右开**，缺省最近 24h；`granularity` 取 `minute|hour|day`，不给就按区间长度自动（≤2h→分钟、≤7d→小时、更长→天）；响应里回显实际使用的窗口与粒度，验收脚本据此与直查对数。
- **转化那一档本期为空**：ClickHouse 里没有任何订单数据（假订单在 MySQL `xsl_mock.mock_order`），6.4 又定着"看板查询统一走 ClickHouse"，所以跨源现读被否掉——**宁可空着并注明"未接入"**，因为一旦两个源各有延迟与时钟，页面上的"转化率"会自己跳动，那种数字比空着更糟（解除条件见 DEVELOPMENT_PLAN 二-C 第 6 条与 ITER-M3 §6）。
- **端到端对账的判定式**（v2.12.0 补：10 那行出口标准只写了"< 2%"却没写算式，而"目标没写判定条件"这个项目已经踩过两次，ITER-M1/M2 各记过一笔）。两条各自给数字、**不合成一个总分**：
  ① **传输完整性**（验证 CH → 宣发平台这段链路，含迟到与幂等）＝「平台收到的 Σpv 与 ClickHouse 明细同窗口 pv 之差的绝对值」÷「明细 pv」，要求 < 2%；
  ② **归因可 join**（验证 `click_id` 这把键真的能用）＝「订单里能在明细表找到对应 `click_id` 的条数」÷「订单总数」，要求 ≥ 98%。
  基准一律取 **`click_event` 明细 FINAL**；`click_stat_minute` 聚合表与明细的差异**不混进这两个阈值**（async_insert 的未合并 part 会带来已知偏差，混进去就会掩盖真问题），它单独作为观测项记录。
  v2.13.0 补两条执行细节（M3-04）：① 的两侧必须取自**同一个已关闭的 `[from,to)` 分钟区间**——回传只推已关闭分钟（6.3-3），拿"现在"对"现在"会得到一个永远不为零的假差异；所以核对顺序是"点击 → 确认已落进明细 → 等分钟关闭 → 触发一轮回传 → 再比"。② 除了主组（全部引用真实 `click_id`，要求 ≥98%，实际应为 100%）还要**故意注入几条引用不存在 `click_id` 的订单**，断言对账报出的缺失条数恰好等于注入条数——只测主组的话，"100% 命中"可能只是因为两边取了同一个集合，负向样本才是这把键真的在工作的证据。落地成两份脚本：`scripts/recon-attribution.sh`（可反复跑的核对工具，T+1 也用它，按阈值给退出码）与 `scripts/accept-m3-04.sh`（铺数据 + 断言），不合并的理由是同一条工程纪律：对账是运维动作、验收是证据动作，生命周期不同。

收口时（2026-10-11，`accept-m3-04.sh` **78/78**：① 差异 **0.00%**、② 命中率 **100.00%**）补三条执行口径，都是"算错了不会被发现"的那类：① **两条判定式各自都要有负样本**——原计划只给 ② 配（注入引用不存在 `click_id` 的订单，报出的缺失必须恰好等于注入条数），① 也要一组：另起一批"只点击、不回传"的活动，差异必须报 **100.00% 而不是 `n/a`**，因为"两侧都没数"与"数丢光了"如果是同一个值，最坏的失败形态就伪装成了无害的那个；② **② 的窗口按订单自带的 `first_click_time` 切，不按 `create_time`**——前者与 `click_time`/`minute` 同属点击侧墙上时钟，后者是 mock 服务器的 UTC 时钟（见附录 B 与 `03-mock-schema.sql` 的"两把时钟"注释），用错会把整批正确订单算成找不到，报出一个纯假造的 100% 缺失；③ 核对工具的机器可读行（`RESULT …`）**一个字段一个值**，所以窗口写成 ISO 的 `T` 形式，人类可读的说明行才保留空格形式。阈值是给**生产**留的余量：本轮 0.00% 是 mock 闭环的性质（两端同机、时钟同源、幂等同代码），`<2%` 达成不等于链路无损。

---

## 七、接口设计（新增，初稿缺失）

### 7.1 数据面

| 接口 | 方法 | 说明 |
|---|---|---|
| `/s/{code}` | GET | 跳转入口（302 或中间页 HTML）；亦作根路径 `/{code}`，`/s/` 前缀用于网关规则清晰与域名共享时路由隔离 |

响应约定：302（正常跳转）/ 200+HTML（中间页）/ 410（过期超限，自定义落地页）/ 404（不存在）/ 429（限流）/ 200 验证页（风控命中）。

### 7.2 管理面（内部 REST，JWT/API Key 鉴权，前缀 `/api/v1`）

| 接口 | 方法 | 关键参数 |
|---|---|---|
| `POST /links` | 创建 | origin_url*, channel_id*, campaign_id*, short_code(可选), expire_time, access_limit, rules[] |
| `GET /links/{code}` | 详情 | — |
| `PATCH /links/{code}` | 更新 | expire_time / access_limit / status |
| `PUT /links/{code}/rules` | 全量替换路由规则 | rules[]（服务端校验 JSON schema + 目标域名白名单） |
| `GET /stats/links/{code}` | 单链接统计 + 时间序列。**现状只有 `click_count` 一个数**（这行 `from,to,granularity` 从 v2.2 起就是承诺不是事实），M3-05 补成真接口；响应**保留 `click_count` 字段**——`make demo` 与 `accept-m1-09.sh` 都在读它，改名或删掉就是契约破坏 | from,to,granularity=`minute\|hour\|day` |
| `GET /stats/tenants/{id}/links` | **链接列表**（准确说是"有点击的短码列表"，管理面台账字段不在这里，见 7.2） | from,to,limit,offset |
| `GET /stats/tenants/{id}/channels` | **渠道漏斗**：渠道 → 点击 →（有效／被频控拦下）三档 + 容器维度细分 | from,to |
| `GET /stats/campaigns/{id}` | 活动趋势（v2.2 那行"活动漏斗"的兑现；**转化那一档本期为空**，理由见 6.4 末与 DEVELOPMENT_PLAN 二-C 第 6 条） | from,to,granularity |
| `POST /internal/outbox/replay` | 内部：outbox 补偿 | —（运维触发，见下条可达性说明） |
| `POST /internal/attribution/push` | 内部：手动跑一轮归因回传 | —（运维/验收触发，响应里带本轮取数耗时） |

创建/更新接口全部幂等化：支持 `Idempotency-Key` 请求头（Redis 24h），防止主办方重试产生重复链接。

**`/internal/**` 的可达性（v2.11.0，M3-02；v2.12.0 起两个内部接口同此条）**：它们**刻意不在 `/api/` 前缀下**。网关只路由 `/api/**`、nginx 只路由 `/api/**`+`/s/`+`/{code}`，所以这些接口既不经租户 HMAC 也不从对外域名可达，只在 compose 网络内与各服务自己的直连端口上存在——"内部：运维触发"这句话由此变成拓扑事实而不是命名约定。它们跑的都是与定时任务同一份逻辑（outbox 那个带 `?short_code=xxx` 可只补一条链接：扫描按 `id` 升序，大批积压时新事件排在队尾，运维等不起），而幂等性由各自的机制保证——8.4 的 version 条件写让重放只会补齐路由表与缓存、不可能写回旧值；归因回传则因为 `batch_id` 由内容决定，多跑一轮至多被对端判成重复。**因此不再为它们单独造一套凭据。** 回传那个的响应里带 `query_millis`：每轮扫明细到底多贵，就从这里读（M3-03 的实测项）。

**`/api/v1/stats/**` 的可达性与边界（v2.14.0，M3-05 定口径时写下）**：看板是这条前缀的第一个真实消费者，所以"它到底对谁开放"必须先说清楚，而不是等被人问出来。
- **不经网关 HMAC**：nginx 把 `/api/v1/stats/` 直接反代到 consumer（`20-routes.conf`，v2.2 起就是这条路），而网关的签名过滤器只挂在 `/api/` 的管理面路径上。这是**既有事实**，不是本期新开的口子——本期只是第一次有个浏览器界面去用它。
- **因此浏览器里不放任何密钥**：那把 `api_key` 本身就是 HMAC 的 secret（`02-seed-tenant.sql`），进浏览器等于公开它——公开出去丢的是**写权限**（能签创建/改链接的请求）。同理**不做"看板专用 token"**：一把只挡"随便翻"的凭据进了浏览器还是明文，加它只会让人误以为隔离已经做了。真正的租户隔离等 9.4 的 key/secret 分离一起做。
- **代价照实写**：只读聚合数字对任何能访问入口的人是开放的，路径里换个租户号就能看到别的主办方的 `pv/uv/渠道` 分布。所以租户号**放在 URL 路径里**（`/stats/tenants/{id}/…`）而不是请求头——放头里容易被读成"有租户隔离"，放路径里等于把这件事摆在明面上，也让验收脚本能直接 curl 逐字段比对。这一条已进第十章风险表。
- **看板看不到管理面字段**：`origin_url`、`status`、`expire_time` 这些在 `short_link`（管理面、要 HMAC），所以"链接列表"这一页列的是**有点击的短码**及其统计，不是链接台账；页面标题直接写"点击统计列表"，不让主办方误以为那是台账。
- **数据源单一**：四条接口全部只读 ClickHouse（`click_event` 明细 `FINAL`，理由与"为什么不是聚合表"见 6.4 末），MySQL 与 `xsl_mock` 都不参与看板查询。

### 7.3 外部契约

- **星演购票页**：接收 query 中 `xy_click_id / channel_id / campaign_id / promoter_id / utm_*`，会话内保持，订单落库归因字段（字段清单附录 B）。
- **宣发数据平台**：回传 batch JSON `{batch_id, from, to, items:[{campaign_id, channel_id, minute, pv, uv, valid_pv, bot_pv}]}`，签名鉴权。
  v2.12.0 契约细化（M3-03，等价实现见 sl-mock）：① **一个 batch 只属于一个租户**：**信封带 `tenant_id`、`items` 里不带**——`items` 带租户等于给接收方一个可以越权的字段，而信封里那个要由接收端与"这把 key 属于哪个租户"对齐后才收（对不齐 401）；单机 mock 只配一把 key、一个租户，真实形态是每租户一把，这一条边界随 9.4 的 key/secret 分离一并补齐；② `from`/`to` 是分钟对齐的 ISO 串，`items` 按 `(campaign_id, channel_id, minute)` 升序（**顺序是 checksum 的一部分**，乱序会变出另一个 batch_id）；③ `checksum = sha1(规范化 items)`，接收端重算比对，不符直接 **400 拒收且台账不留值**；④ 鉴权复用 9.4 那套 HMAC 四头（`X-Api-Key/X-Timestamp/X-Nonce/X-Signature`，规范化串同 `HmacSigner`），但**接收端只做验签 + 时间戳窗口，不查 nonce 表**——`batch_id` 幂等已经把重复投递消化掉了，再养一份 nonce 状态没有收益；⑤ 同一 `batch_id` 重复到达 → 返回 200 且 `duplicated=true`，数值不重复计入、`times_received+1`；内容变了 → 新 `batch_id` → 按 `(campaign_id, channel_id, minute)` **覆盖更新**（这是迟到数据被吸收的正确姿势）。

---

## 八、非功能设计（新增，初稿缺失）

### 8.1 单机基线、极简模式与容量推演

**A. 个人 PC 实测基线（简历数据的合法来源）**

| 项 | 目标（16G 宿主、Docker 单机） | 测量方式 | 实测（2026-10-01 补跑，存档 `bench/reports/`） |
|---|---|---|---|
| 跳转吞吐 | ≥ 2000 QPS（缓存全命中路径）；**目标负载判定见 1.3 与本节第 4 条** | wrk `-t8 -c200 -d60s`，loopback；上限取并发曲线平顶档 | **达标且余量充足**：目标负载 ≥2000 QPS 早在 c=4 就满足（改前 3,810–5,002 QPS，M2-22 后 c=4 到 **6,697–8,483 QPS**）。chain-live 平顶上限 **12,185 → 18,193 QPS（M2-21/22 批量化，+49%）**，事件链停摆时 24,525 QPS（M1-11 段）作参照。上限目标的 ≥20k **未达成（差 9%）**，剩余瓶颈已移到数据面（见第 4 条）。存档：`bench/reports/2026-10-03-1955-m2-00-jump-loadcurve.md`（改后）、`-1839-`（改前）、`2026-10-01-1336-m1-11-kafka.md` |
| 跳转延迟 | P99 < 50ms | 同上 `--latency`；判定改在**目标负载**上做（M2-00，见 1.3） | **达标（判定负载＝目标负载）**：M2-00 并发曲线在 c=4/8/16（5,002／6,439／6,459 QPS）P99 = **21.08／23.76／37.74ms**（`2026-10-03-1414-m2-00-jump-loadcurve.md`）；c=32 起越线（116ms，饱和段）。M1-11 当年在 200 连接判未达标（52.59／57.64／74.16ms）属口径混用；其并发敏感度曲线仍有效：16/50/100 连接 P99 = 61.03/10.50/**30.71ms**，拐点≈100 连接、该处 19,284 QPS（`2026-10-01-1353-m1-11-jump-sensitivity.md`） |
| Kafka 投递 | ≥ 1 万 events/s 不丢 | 生产者 burst → 查 consumer lag → 追赶清零 | 生产 **24,544 events/s**（M1-11）→ M2-21/22 批量化后 **22,413／24,426／24,734 events/s**（并发 1/2/4 三档）；追赶 **33,469 → 41,223 events/s**（改前同场基线 29,578，+39%；并行度 2→4 只再涨 1.3%，收益已饱和）；对账生产数=CH 落库数，**三档丢失率全部 0.0000%** |
| ClickHouse 聚合 | 千万级明细秒级聚合 | 物化视图 + 基准 SQL 脚本 | 跑在**真实积累**的 16,595,224 行明细上：渠道聚合 37ms、小时×设备 70ms、推广人+uniqExact 78ms、`count() FINAL` 21ms、MV `sum+uniqMerge` 18ms、单码 `FINAL` 18ms → 达标；另测写入 1,059,322 rows/s（1M 行 / 944ms，含 MV 同步聚合） |
| 频控 Lua | ≥ 3 万次/s（**目标原先没写连接数/流水线口径**，故三档同报，见本节第 6 条） | redis-benchmark 自定义脚本：容器内 loopback 跑 jump 运行时加载的同一份 `sl-jump/src/main/resources/lua/rate_limit_window.lua` | 单连接不流水线 **27,803/s**（avg 0.034ms、p99 0.095ms）——比 3 万低 7%；10 连接 **166,852/s**、50 连接 **211,894/s**——超目标 5.6–7.1 倍。结论按"能不能撑住跳转"回答：jump 端到端上限 ~13.8k QPS 由两实例分摊，单连接口径就有 2× 余量，**脚本不是瓶颈**；`bench/reports/2026-10-03-1652-m2-14-ratelimit-lua.md` |
| 短码生成 | ≥ 1000 TPS，无重复码 | JMeter 并发创建 + 唯一性断言 | **未达标**：16 并发 ×2000 次 = **396 TPS**（另一次 541 TPS），p99 91.6ms（目标 <20ms）；**唯一性通过（2000 码 0 重复）**。工具口径=python 逐请求 HMAC（wrk 的 Lua 无 HMAC 原语，如实记录而非谎称 wrk） |

压测注意：压测脚本与服务同机抢 CPU，数字按保守口径报告；ClickEvent 分两段测（先关 consumer 测堆积，再开 consumer 测追赶）；每轮压测输出存档进仓库 `bench/`，作为可追溯凭证。

实测进度（2026-10-01 补跑，2026-10-03 M2-00 修正口径、M2-14 补频控基线）：上表六项**全部有实测存档**——五项达标（跳转吞吐、跳转延迟、Kafka、ClickHouse、频控 Lua），一项未达标（签发，且已证明是压测客户端自身上限）。六条必须一起读的限制说明：

1. **拐点与尾部归因**：200 连接把跳转推过拐点，P99 落入排队时延；已用对照实验证伪"GC 是主因"——`-XX:+UseG1GC -XX:MaxGCPauseMillis=20` + 768M 的变体 P99 为 57.93/57.63ms，与默认 SerialGC 同档（存档 `2026-10-01-1348-m1-11-jump-g1.md`），故 **GC 维持 SerialGC 不改**；jump 的 `mem_limit` 后由 512m 提到 768m，理由是常驻 RSS 长期贴顶带来的 OOM-kill 风险，**不是性能决定**（ITER-M2 §5）。
2. **宿主内存会污染跨天对比**：M2-00 复测时宿主 16G 仅剩约 100MiB 空闲、compressor 约 6G，同参数曲线吞吐比 M1-11 低 2–3 倍——所以报告头部新增了 `env_snapshot`，绝对值不再跨天直接比较。
3. **资源上限是被测口径的一部分**：jump 容器 RSS 实测顶在 502–509MiB/512MiB，ClickHouse 在 2GiB 上限下批量插入 10M 行会撞 `Code: 241 Memory limit exceeded`——这两条都是**如实记录**而非绕过（分块 100k 行/批后写入吞吐测得 1.06M rows/s）。
4. **容量上限的归属：改前测得、改后复测，结论已被这次改动改变（这正是当初归因的价值）**：M2-00 用逐容器 CPU（`bench/run-jump-attribution.sh`）测得负载中 ClickHouse ≈1.9 核、nginx ≈1.4 核、jump ≈1.3 核×2、kafka ≈0.6 核，且停掉 consumer（CH 不再写入）后同一入口从 ~7k 跳到 24,525 QPS ⇒ 当时的上限确实在 ClickHouse 写入/合并。M2-21/22 据此做批量化后复测：ClickHouse 1.32→**0.43 核**、kafka 0.60→0.38、consumer 0.25→0.11，VM 内合计 10.4→**5.3 核**，chain-live 上限 12,185→**18,193 QPS** ⇒ **事件链已不再是瓶颈，上限回到数据面自身（jump ≈1.4 核×2 + nginx ≈1.3 核）**。下一根杠杆因此是数据面（每请求两次 Redis 往返合成一次、nginx worker/连接），不是继续压 CH（见 ITER-M2 §6）。
5. **签发链路需要独立复测**：396–541 TPS 的波动来自客户端（python + 逐请求签名）与服务侧未分离，M2 要换 JMeter/预签名脚本重测后再谈优化结论。
6. **频控 Lua 这一行的目标本来是不完整的**：「≥3 万次/s」没写连接数与是否流水线，而同一份脚本单连接不流水线测得 2.78 万/s（差目标 7%）、10 连接起 16.7 万+/s（超 5.6 倍）——**达不达标完全取决于那个没写出来的条件**。这与 M2-00 在跳转延迟行上犯的错同构（那条漏写"判定负载"，这条漏写"判定并发"），所以三档全部报出来、判定按"能不能撑住跳转"给，并把"目标必须自带判定条件"写进 `bench/README` 的口径约定。

**B. 企业级容量推演（设计能力，不作实测）**

目标形态：日常 5000 万点击/日，开票峰值 ≈ 5 万 QPS（峰值/均值 10）。水平扩展路径中**第一步（jump 多实例 + LB）已在单机默认拓扑实测**（3.3），多机化只是给同一拓扑加宿主；后续：Redis 单机 → Cluster（键设计已预留无跨槽操作约束，见 4.3）；MySQL 两库 → ShardingSphere 扩 N 库（分片算法单机两库已验证，加宿主即扩）；Kafka 1 分区 → 24 分区；ClickHouse 单机 → 2 分片×2 副本。每步只改配置与拓扑不改代码——扩展路径本身就是架构正确性的证明点，且其中可单机证伪的部分全部已证。

**C. 极简模式（8G 内存机器）**

去 Kafka / ClickHouse / Nacos：ClickEvent 改 Redis Stream + 批量落 MySQL 月分表聚合，统计 SQL 直查 MySQL，注册发现退化为 compose 静态地址。核心链路（池方案、跳转、动态路由、频控防刷、多租户）完整保留；恢复为完整栈仅需替换两个 Producer/Repository 实现（接口已按端口-适配器隔离）。

### 8.2 性能设计

- 跳转链路目标见 1.3。关键路径操作只有：本地缓存/Redis GET + Lua 频控 + Kafka 异步 send，全程无同步磁盘 IO、无同步 RPC。
- Caffeine 本地缓存 TTL 30s + `version` 号失效：牺牲 30s 内的配置实时性换取 Redis 故障时的存活能力。

### 8.3 可靠性与降级预案（全部可在单机实测）

| 故障 | 表现 | 预案 | 验证方式 |
|---|---|---|---|
| Redis 挂 | 回源风暴 | Caffeine 兜底 + link_route 限流直读（Sentinel 降级阈值）+ 频控退化为进程内计数 | `docker stop redis` 后跳转成功率不跌零 |
| Kafka 挂 | 事件积压 | 本地 WAL 队列暂存（磁盘 4h 余量），恢复后重放；跳转不受影响 | `docker stop kafka` 演练，恢复后核对事件总数 |
| MySQL 挂 | 新码不可生成、缓存未命中失败 | 管理面熔断；数据面靠 Redis + 本地缓存存活；短码池本地 LRU 只读消费 | `docker stop mysql` 期间持续打跳转 |
| ClickHouse 挂 | 看板停更 | Kafka 堆积不丢，恢复后追赶；不影响跳转 | 同上，看 lag 归零 |
| 应用容器崩溃 | 单点中断 | compose `restart: always` + healthcheck；jump ×2 + LB，与管理进程互不连坐 | `docker kill` 其中一个 jump 实例，压测流量观察零中断与恢复时间 |
| 数据丢失 | — | 每日 cron：mysqldump + ClickHouse BACKUP 至本地第二磁盘/移动介质 | `make restore-verify` 恢复演练脚本 |

原企业级预案（多可用区 GSLB 切流、微信封域域名池轮换）保留为 9.5 的设计推演，机制本身用本地多域名模拟验证。

### 8.4 一致性与幂等（新增）

- **元数据 → 路由表**：`short_link`、`link_route` 同库同分片键不同时，采用事务内写主表 + outbox 表，异步同步路由表 + 失效缓存；`version` 单调递增，缓存回写用 Lua `version 比较` 防旧值覆盖新值。
  v2.11.0 落点细化（M3-02）：同步器是 **sl-admin** 里的 `@Scheduled` 任务（默认 5s 一轮、每轮 ≤500 行、按 `id` 升序；`xsl.outbox-sync.*` 可调），状态机 `0 待投递 → 1 已投递`，抛异常则留在 0 并 `retry_count++`，超阈值（默认 10）置 `2 失败` 并**停止自动重试**——一条坏 payload 不该每轮都吃掉一次批次。"异步同步路由表"落到两条**条件写**上：`UPDATE link_route SET route_json=?, version=? WHERE short_code=? AND version<?`；0 行受影响时要分辨两种情形——这一行**不存在**（补插，并一并补上同批失败的归属索引行 `code_tenant_index`：只补路由会得到一条"跳转能 302、管理面按码查归属却 404"的链接）还是**已有更高 version**（置 1 即"被更新的事件取代"，重试无意义）。缓存这一侧写的是**当时最权威的那一份**：前两种情形用事件里的快照，被取代时用表里那份更高的去修缓存。反过来说就是任何情况下都不拿旧事件覆盖缓存——否则会得到"路由表 v5、缓存 v4"，跳转要等缓存 24h 过期才回到 v5（这条是被活体测试逼出来的，见 ITER-M3 §4）。而**只有真的写动了缓存才发广播**：重放历史积压时绝大多数事件一行都不用改，为它们各发一条通知只会让两个 jump 实例白清一遍本地缓存。由此得到一条要拿去测的性质：**重放整张 outbox 是安全的**，老事件只会把路由表与缓存补齐，不可能把任何一侧写回旧值。
- **缓存失效广播**：配置变更后 Redis Pub/Sub 通知各实例清 Caffeine（接受 30s 收敛窗口，紧急封禁走 `version` 强校验通道立即失效）。jump 默认 ×2 实例，跨实例收敛时间在单机即可实测（改配置 → 两实例分别命中新值的时差）。
  v2.11.0 落点细化（M3-02）：通道 `xsl:route:evict`，载荷就是短码字符串（订阅方只做一件事，不必传 JSON）；发布点在管理面每次写完 `link_route` 与缓存之后（**直写路径与同步器路径都发**），jump×2 各起一个订阅容器、收到即 `invalidate(code)` 清 L1。广播**不进正确性链路**：Redis 重启或订阅瞬断会丢消息，此时由 L1 的 30s TTL 自愈；"立即失效"成立的前提是上面那两条条件写先把 `link_route` 与 L2 改对，广播只负责让两个实例把本地那层扔掉。停机后订阅回来的时间本机量过两次：**停 5s 再起 ≤5s、停 25s 再起约 12–18s**，且两个实例同步恢复（v2.12.0 补，M3-03 回归时发现）——这段窗口里发出的广播确实会丢，所以 `accept-m3-02` 的 A 段现在先做一次"两个实例都收得到探测消息"的前置断言再开始计时，否则那个窗口会伪装成一次产品回归。收敛时间做成 `scripts/accept-m3-02.sh` 的实测项（断言"两实例都在 2s 内命中新值"——只有 30s TTL 的旧实现下这条必红，所以它自带对照）。
- **access_limit 计数**：Redis Lua 扣减为权威，每分钟对账回写 MySQL 快照；Redis 灾难重建以快照 + 保守缓冲恢复（宁可少放不可超放）。
  v2.3.0 落点细化：快照列在 **`link_route`**（`access_used` / `access_used_at`，与 `short_code` 同分片轴，jump 本就读这张表）而不是 `short_link`（按 `tenant_id` 分片，jump 的 ShardingSphere 配置里没有该表）；对账任务放**管理面 sl-admin** 的 `@Scheduled`（它已有 MySQL 写权与 Redis 读，数据面继续保持"只写 Redis、不写业务表"）；jump 只在 `sl:cnt:{code}` 缺失这一条冷路径上读快照并以其为初值 seed，重建公式 `剩余 = access_limit − 快照已用 − 保守缓冲`。快照独立成列、不进 `route_json`，因此不触碰缓存 `version` 语义（否则每分钟一次的写会让 L1/L2 缓存反复失效）。
- **事件幂等**：`event_id`（点击级 UUID）+ ClickHouse ReplacingMergeTree 去重，Kafka at-least-once。

### 8.5 可观测性（新增）

- Metrics：跳转 QPS/延迟分位/缓存命中率/频控拦截数/bot 比例/池水位/outbox 积压/Kafka lag，Prometheus + Grafana（compose 可选 profile，见 3.3）；告警以 cron 脚本查PromQL 阈值等价实现（单机无 Alertmanager 必要）。核心 5 项：跳转错误率、命中率 < 97%、Kafka lag、池水位 < 20%、WAL 积压。
- **数据面现有指标（M2-36 按实现核对，命名即验收口径）**：`xsl_jump_requests_total{outcome}`、`xsl_jump_route_lookup_total{level=local|redis|db|miss}`、`xsl_jump_rate_limit_total{result,mode}`、`xsl_jump_access_counter_total{result=rebuild|degraded_allow}`、`xsl_jump_clickevent_total{result=sent|wal|build_error}`、`xsl_jump_wal_replayed_total`/`xsl_jump_wal_replay_failed_total`/`xsl_jump_wal_backlog_bytes`、`xsl_jump_route_target_total{kind=h5|app_scheme|wx_urllink|intermediate}`（M2-33，一级决策命中分布）、`xsl_jump_route_rule_unusable_total{type}`（命中了但目标残缺/时间窗串形不对——这类"静默跳过"必须可数）、`xsl_jump_route_evict_total`（M3-02，收到多少次缓存失效广播：它是"亚秒收敛"到底有没有在跑的旁证，与 `accept-m3-02` 的时差实测对照看）；管理面 `xsl_quota_reconcile_total`、`xsl_outbox_sync_total{result=applied|inserted|superseded|failed}`、`xsl_outbox_backlog{status=0|2}`（积压行数，即 8.5 开头那句"outbox 积压"指标的具体落点）、`xsl_outbox_backlog_age_seconds`（最老待投递事件的年龄——行数不涨但年龄持续涨，说明同一批事件在反复失败）；缓存这一侧 `xsl_route_cache_write_total{result=written|kept}`（`kept`=被 version 守卫挡下、因而**没有**发广播）与 `xsl_route_evict_total{result=sent|error}`（广播发送成/败，见 8.4 那句"写动了才广播"），归因回传 `xsl_push_total{result=sent|duplicated|rejected|skipped|error}`（一轮一租户一条：2xx 且新计入=sent、幂等吸收=duplicated、对端明说拒收（校验和/签名/身份不符）=rejected、**本机决定不推=skipped**、送不到=error。后两者必须分开：seed 里有 1002/1003 两个租户，把"这个部署不推它"混进 rejected，每轮都会冒假信号、真拒收反而被淹）、`xsl_push_items_total`（推出去的桶行数）、`xsl_push_seconds`（取数 + 投递耗时，M3-03 那个"明细聚合到底多贵"的实测就来自它，另有手动触发接口直接回 `query_millis`）；mock 宣发平台侧 `xsl_mock_batch_total{result=accepted|duplicated|rejected_checksum|rejected_batch_id|rejected_window|rejected_signature|rejected_tenant|rejected_json}`（对端拒收要能分开看是**哪一种**拒收——全挤在一个 rejected 里就失去了定位价值）；mock 订单侧 `xsl_mock_order_total{result=created|duplicated|rejected_validation}`（`duplicated` 就是「同一 `order_id` 重复提交被吸收」——对账分母可不可信看它；`rejected_validation` 是缺 `order_id`/缺 `click_id` 那种 400，单独一个值才能看出"有人在乱投"而不是"链路坏了"，M3-04）；看板这一侧 `xsl_stats_query_total{endpoint}` 与 `xsl_stats_seconds{endpoint,window}`（v2.14.0，M3-05：四条只读接口各自一条，`window` 打 `minute|hour|day` 标签）——6.4 那句"24h/7d 两个窗口到底多贵"的实测就从这里读（配 histogram 桶才有 P95 可谈，单个 Timer 只有均值和最大值）；消费者 `xsl_consumer_ch_posts_total{result}`/`xsl_consumer_ch_rows_total`/`xsl_consumer_ch_post_seconds`。**照 M3-02 那条规矩：这里写下的每一个指标名都要被验收脚本读到过一次**，否则那行只是愿望。
- Tracing：`trace_id` 随事件消息走（内部日志贯通用），**不落 ClickHouse**；对账用的对外键是 `click_id`（= URL 上的 `xy_click_id`），M3-01 起进明细表。刻意只落一列：`trace_id` 没有对外语义，落库等于多养一份没人查的口径。
- 日志：数据面采样日志（1%）+ 风控命中全量；跳转路径**不打印完整 origin_url 与明文 IP**（脱敏）。

---

## 九、安全设计（新增，初稿重大缺口）

### 9.1 威胁模型概览

| 威胁 | 面 | 缓解 |
|---|---|---|
| 开放重定向 / 钓鱼链接借势 | 生成 API | 域名白名单 + 人工审核队列（见 9.2） |
| 短码枚举遍历 | 跳转 | 不可预测短码（9.3）+ 频控风控（5.3） |
| 主办方账号被盗批量发恶意链 | 生成 API | Key 轮换、IP 绑定（可选）、发链速率限制、事后 audit_log 追溯 + 一键冻结域名 |
| 参数注入篡改目标 URL | 跳转拼接 | query 白名单 + URL-encode + 禁覆盖原有参数（5.2） |
| 刷量污染归因 | 数据 | 四层防刷 + 双指标看板（5.3） |
| **看板只读统计无需凭据即可访问**（v2.14.0，M3-05 定口径时写明） | 数据 | 承认这条边界而不是假装它不存在：`/api/v1/stats/**` 不经网关 HMAC（7.2），所以任何能访问入口的人都能读到 `pv/uv/渠道` 这类**聚合数字**。收敛办法是让看板接口**只出聚合值、不出 `origin_url`/状态/过期这些管理面字段**，也不放任何密钥进浏览器（那把 api_key 就是 HMAC secret，泄露丢的是写权限）。真正的租户隔离等 9.4 的 key/secret 分离一起做，**本期刻意不做"看板专用 token"**——一把进浏览器就还是明文，加它只会让人误以为隔离已经做了 |
| 域名被平台封禁 | 运营 | 域名池 + 监控切换（9.5） |

### 9.2 链接准入

- `origin_url` 强制 https（星演域内目标），或命中主办方备案域名白名单；白名单外进入人工审核（status=3）。
- 校验在**创建时**执行并绑定域名：跳转时不再动态解析 URL，杜绝跳转时 SSRF/重定向链漂移。
- 路由规则中的 target_url 同样受白名单约束。

### 9.3 短码安全

- 系统短码：池内码由 CSPRNG 生成（非顺序），杜绝雪花 ID 直接 Base62 的可预测性（修正初稿隐含问题）。
- 自定义短码：保留字表（`api/admin/s/health/...`）+ 敏感词过滤 + 唯一性 `SETNX`；长度 3–12。

### 9.4 认证与鉴权

- 管理 API：租户 API Key（HMAC 签名请求）或星演 SSO JWT；Gateway 统一验签后转发。
- 内部接口在生产用 mTLS / 网络 ACL；单机以「仅监听 localhost + compose 内部网络」等价收敛。跳转接口无鉴权（公网属性），依赖限流风控。

### 9.5 域名治理（机制实现 + 本地多域名验证）

短链域名是宣发命脉，也是平台封禁首要目标。`domain_pool` 表与切换机制完整实现，生产策略按推演保留：

- **本地验证**：`xy1.test / xy2.test / xy3.test` 写入 hosts → Nginx 按 `server_name` 全部转发同一跳转服务，验证「code 与域名无绑定、跨域通用」与一键切主域流程（Grafana webhook 模拟封禁告警触发切换）；
- **生产推演（不实施，文档保留）**：主域 + 2~3 备用域备案养域、按渠道分域投放分散爆炸半径、封禁自动检测（点击率断崖 + 渠道侧巡检）、新域名 2 周灰度养信；
- 可选：购一枚低价真实域名（个人备案），用 Let's Encrypt 证书跑通公网链路——非必需。

### 9.6 数据合规（PIPL，设计标准 + 本地模拟数据实践）

- 本项目全程使用**脚本生成的模拟流量数据**（伪造 UA/IP 段），不采集任何真实个人信息；下列机制按生产标准实现并文档化，作为合规设计能力的展示；
- 明文 IP 不落盘：入口处加盐 SHA-256（盐每日轮换、独立 KMS 保管），地理解析在内存中完成仅存省/市；
- ClickHouse 明细保留 180 天，聚合数据永久；
- 用户侧无 Cookie 追踪新内容（短链跳转天然无状态），落地页隐私政策披露点击统计用途；
- 租户合同模板补充"主办方经本系统收集点击数据的，主办方为个人信息处理者"的责任条款（法务确认）。

---

## 十、里程碑与上线计划（新增）

| 阶段 | 范围 | 出口标准（个人 PC 可验证） |
|---|---|---|
| M1（2-3 周） | docker-compose 环境 + mock 购票页/宣发平台、短码生成（池方案）、302 跳转、ClickEvent→Kafka→ClickHouse 明细、基础统计接口 | 8.1-A 基线达标（压测输出存档 `bench/`）；缓存命中率 > 99.5%（Prometheus 出数） |
| M2（+2-3 周） | 动态路由引擎 + 中间页降级（mock UA 重放模拟微信/抖音容器）、频控与行为评分、多租户配额与看板 | UA 重放脚本证明三级路由与降级正确；bot 流量在双指标看板正确分类 |
| M3（+3 周） | 归因回传 mock 宣发平台、outbox/对账、**Vue 主办方看板（链接列表 / 渠道漏斗 / 有效·疑似刷量双指标）**、8.3 降级演练全表过一遍、多域名切换演练 | 端到端归因对账误差 < 2%（**判定式见 6.4**：传输完整性与归因可 join 两条各自给数字，不合成总分）——**已达成**（M3-04，`accept-m3-04.sh` 78/78：① 0.00% / ② 100.00%，两组负向样本都被完整抓出）；看板三页面数据与 ClickHouse 直查一致——**判定式（v2.14.0 补，原来这句没有算式）**：`accept-m3-05.sh` 里每条 stats 接口的每个字段，与脚本自己直查 CH 的**同一个左闭右开窗口**逐个相等、**0 容差**（同源同表才允许 0 容差，而"居然可以要求 0 容差"正是这件事真的在核对的证据），再用真实浏览器读页面上渲染的数字核对一遍（"接口对但页面错"是另一类失败，只有浏览器抓得到）；每条降级预案有一次实测记录 |
| P2（后续） | 裂变短码批量签发、模型化反作弊实验、开源化整理（README/CI/Badge） | — |

节奏按业余时间（每周 10-15h）估算，总周期约 2.5 个月（v2.2 因看板 +1 周）。全程保留跳转服务「纯 302 无路由」回退开关；每个里程碑收口打 tag，压测与演练记录随 commit 存档——这些就是简历数字的可追溯来源。

---

## 十一、风险与开放问题

| # | 风险/开放问题 | 影响 | 应对 |
|---|---|---|---|
| 1 | 无真实购票页/宣发平台可配合 | 归因闭环只能自证 | **M1 自建 mock 购票页 + mock 宣发平台**（静态页回显 query + 假订单接口），严格按附录 B 契约实现，闭环演示即成立 |
| 2 | 个人无企业资质：无法备案域名、无微信开放平台能力 | 跳小程序链路无法真实验证 | 中间页降级逻辑用 mock UA + 个人真机微信手动验证 Scheme 路径；URL Link 生成留接口桩 + 文档推演调用与配额设计 |
| 3 | UA 容器特征演化（抖音内嵌页规则变更） | 一级路由误判 | default→H5 兜底 + 月度特征更新 |
| 4 | 企业级容量为纸面推演 | 面试被追问 5 万 QPS 实测 | 如实报告单机基线 + 展示 8.1-B 扩展路径与无状态设计；强调「推演」与「实测」口径分离 |
| 5 | ClickHouse UV 精度（uniq 近似） | 看板与财务口径差异 | 声明 ±0.81% 误差；精确 UV 走离线 T+1 |
| 6 | 短链跨域通用后旧域名流量归零速度 | 封禁切换后旧链仍在传播 | 旧域名保留 302 服务不回收 |

---

## 十二、扩展方向

- **短链 × AI 演出口碑**：追踪"看完口碑内容→点击购票"链路，量化口碑内容宣发价值。
- **短链 × VIP 运营**：VIP 人群专属短链，点击与复购转化归因。
- **短链 × 场馆阵地**：海报/取票机/电子屏二维码统一由短链管理跳转目标与效果统计。
- **短链 × 电子票入口**（从初稿场景四移入）：动态有效期、设备绑定访问；**前置条件**为完成核验链路强弱依赖分析与离线兜底设计，确保入场不依赖短链可用性。

---

## 附录 A：初稿审阅修正记录

| 初稿问题 | 本版处理 |
|---|---|
| `short_link` 按 tenant_id 分片但跳转按 short_code 查询（分片键与读路径冲突） | 4.1 读写拆表 + `code_tenant_index` 全局索引 |
| "唤起 App 失败降级"由服务端 302 完成（技术上不可行） | 6.2 两级路由：服务端一级决策 + 中间页客户端降级 / Universal Link |
| 场景四"电子票核验"与"不做主链路"定位冲突 | 移入扩展方向（12），设上线前置条件 |
| 布隆过滤器不支持删除、误判无兜底 | 改 Cuckoo Filter + 空值缓存兜底（5.2） |
| Kafka 发送失败即丢归因数据 | 本地 WAL 重试队列 + at-least-once + event_id 幂等（6.3/8.4） |
| 雪花 ID→Base62 短码可枚举 | CSPRNG 池生成 + 混淆（9.3） |
| origin_url 无准入校验（开放重定向/钓鱼风险） | 9.2 域名白名单 + 审核 + 创建时校验 |
| 301/302 取舍未论证 | 5.2 明确禁用 301 及理由 |
| access_limit 高并发原子性未说明 | Redis Lua 原子扣减 + 对账（5.2/8.4） |
| "面试亮点"数字以已达成口径混入设计文档 | 全部转为 1.3 SLO 目标 + 第十章出口标准，实测回填 |
| 缺接口设计/容量规划/高可用降级/可观测性/合规/域名治理/里程碑 | 新增第七、八、九、十章 |
| IP 明文哈希无轮换、地理采集合规未提 | 9.6 加盐轮换、明细保留期、责任条款 |
| （v2.1）企业组件与个人项目性质不匹配 | 多 AZ/Cluster/256 分片/跨团队契约/备案域名全部收敛为「单机实现 + 设计推演」；新增 3.3 Compose 资源预算、8.1-A 单机压测基线、8.1-C 极简模式、mock 购票页/宣发平台闭环（11 风险 #1） |
| （v2.1.1）v2.1 误把「单机」等同于「单实例」 | 3.3 明确实例数由资源预算决定与机器数无关：jump 数据面默认 ×2 + LB（无状态/滚动重启/Pub/Sub 跨实例失效均可单机实测）；中间件单实例的理由改为诚实口径（同宿主共享盘无容灾收益），复制与故障转移语义提供可选 redis-ha profile |
| （v2.2）看板形态与仓库组织未定 | 选定轻量 Vue 3 主办方看板（3.2/console-web，M3 交付三页面）；确立 monorepo + 细粒度部署形态（3.4），模块依赖方向由 Maven 约束防腐化 |

（从 v2.2.1 起本表加一列"原因"：这个项目里更值得留档的不是"改了什么"，而是"当时为什么会那样写"。）

| 修订点 | 为什么会写错／为什么会那样判 | 本版处理 |
|---|---|---|
| （v2.2.1，m1 收口）1.3 只有"目标"一列，实测值散落在验收脚本输出里 | 单列表格会诱导引用目标值，而 1.3 自己规定"不得以目标值冒充实测" | 1.3 增"实测（截至 m1）"+"凭证"两列：可复现项（零 5xx 滚动重启、0 丢失、看板 2s）回填并指向 `scripts/accept-m1-*.sh`；未测项（吞吐、P99、命中率百分比）如实写"未测"并给出前置条件。8.1-A 同步增加实测进度段，说明 `bench/reports/` 为空是 M1-11 顺延（本机 Docker VM 3.9G）而非遗漏 |
| （v2.2.2，M1-11 补跑）8.1-A 的六项基线在收口时全部"未跑"，1.3 的吞吐/延迟两行只能空着 | 前置条件（Docker VM 内存、压测工具）在收口当日才解除；空着比借用目标值诚实 | 六项全部补跑并加"实测"列：四项达标（跳转吞吐 16.5k–23.8k QPS、命中率 100.000%、Kafka 24.5k events/s 且丢失 0、CH 在 1659 万行真实明细上聚合 18–78ms），两项**未达标照实写**（200 连接饱和区 P99 68–74ms、签发 396–541 TPS），一项因功能属 M2 而未测（频控 Lua）。所有数字指向 `bench/reports/` 存档，未达标项附瓶颈分析与复测路径（见 ITER-M1 §6） |
| （v2.2.3，M2-00）1.3 与 8.1-A 把"200 连接 P99 52–74ms"记为**未达标** | 这条判定把两件事混成了同一件：DESIGN 1.3 的 `≥2000 QPS` 与 `P99<50ms` 是同一条 SLO，而 200 连接≈10× 目标负载，测的是容量上限 | 延迟行改为**在目标负载上判**：c=4/8/16 = 5,002／6,439／6,459 QPS 时 P99 = 21.08／23.76／37.74ms ⇒ 达标，饱和段（c≥32）单列为容量描述；8.1-A 的"跳转延迟"行同步改口径、限制说明由四条扩为五条（新增**容量上限归属**＝ClickHouse 写入/合并、**绝对值不跨天比较**）并修掉重复编号；GC 那条补上"mem_limit 512m→768m 是稳定性决定不是性能决定"。本轮自查发现的两个错数（`P50 5.62ms` 无出处、把越线的 c=32 算进达标段）在 commit 前改回，过程记入 ITER-M2 §4 倒数两行 |
| （v2.3.0，M2-10 防刷主线细化）初稿把频控写成"滑动窗口"、把它排在 `access_limit` 扣减**之后**、且默认接受 `X-Forwarded-For` 首元素 | 三处都会在实现时暴露成实际问题：① 多键 Lua 与 8.1-B「键设计无跨槽约束」的扩展承诺冲突，且 ×2 实例下上一槽计数走进程内会因轮询被劈半；② 按原序，攻击者可用高频请求在数秒内烧光链接配额（限流反成 DoS 面）；③ XFF 首元素由客户端可控，频控可直接绕过 | 5.2 把频控提到有效性检查之前；5.3 第 2 层按实现改口径为**固定 60s 窗口**并写明两条不做滑动的理由与边界代价（最坏 2× 阈值），真滑动连同 hash tag 留作后续；IP 统一取 nginx 覆盖写入的 `X-Real-IP`（回落 XFF 末段→remoteAddr），4.2 的 `ip_hash` 同此口径；4.3 键表、8.4 对账落点（快照列在 `link_route`、回写任务在 sl-admin、jump 只在冷路径读）一并回写。5.3 增"实施进度"说明 `risk_score` 自本期起不再恒 0 |
| （v2.4.0，M2-01）8.1-A 第 4 条与 1.3 吞吐行写死了一个结论：**上限由 ClickHouse 写入/合并决定** | 那句话在当时是实测正确的（负载中 CH ≈1.9 核、停 consumer 后同一入口 24,525 QPS），但它没标明自己是**某个实现状态下的结论**——批量化做完之后就不再成立 | 按时间线改写而不是覆盖：6.3 补批量语义与两条不变量（`wait_for_async_insert=1` 保住"写成功才提交 offset"；producer 按 `short_code` 选分区所以码内有序不变）；1.3 延迟行与 8.1-A 吞吐/投递行同时给出改前改后两组数（12,185→18,193 QPS、追赶 29,578→41,223 events/s、事件链 CPU 2.17→0.92 核），第 4 条明确写出"上限归属已随本次改动改变，现在在数据面自身"；**目标 ≥20k 未达成（差 9%）照实记为未达**，不把阈值改成能过的样子 |
| （v2.5.0，M2-30 动态路由细化）6.2 只写"读 route_rule 按 priority 匹配、UA 库月度更新、微信 URL Link"，读起来像已具备的能力清单 | 三处会在实现时变含糊：①"UA 解析库"没说自研还是引库、如何更新；② `geo_province`/时间窗依赖的离线地理库单机根本没有，条件却列在表里；③ `wx_urllink` 暗示已接微信。另有第十章的"纯 302 回退开关"在代码里从来只是注释（`JumpResolver` 只有一个实现、无条件装配） | 6.2 增"实现口径"六条：特征表自维护 + 配置加载（"月度更新"＝改配置不改代码）、未识别一律 default（代价：覆盖窄；收益：新容器不被误伤）、未知事实不匹配（geo 恒未知是**能力边界**不是缺陷）、`xsl.jump.mode` 必须做成真条件装配、`wx_urllink` 是 provider 接口 + 本地 mock（不接真实微信 API）、中间页由 jump 返 200 HTML（与禁用 301 同一取舍的两面）、mock 购票页用 nginx 静态回显不建 Java 模块。三处"文档承诺无实现"（mode 假开关、route_rule 零引用、outbox 无读方）记入 ITER-M2 §4 |
| （v2.6.0，M2-31 双轨落地）6.2 的规则示例写成 `{"when":{...},"then":{...}}` 嵌套形状，且"跳转服务读取 `route_rule`" | 实现出来的不是这个形状也不是这个读取源：一行只承载**一个**条件维度（`(type,op,value)` 三元组 + 目标三列），同条 AND 组合（示例里的"上海且大促期内"）没有；数据面读的是 `route_json.rules` 编译产物，jump 的 `sharding.yaml` 里根本没有 `route_rule`。留着原示例，M2-33 的实现者会照嵌套形状解不出快照 | 示例换成实际形状并把读取源改为快照；新增"双轨的落地口径"六条（单一写路径、单条件维度、算子按维度收窄且 `gt`/`http(s)` scheme 不受理、`PUT` 整体替换与 `"rules": null` 显式序列化、校验前置于短码分配 + PATCH 必带既有规则重编译、版本与缓存回写沿用 PATCH 那条现路径）。跨实例缓存失效仍标为 M3 那一格，本期靠 TTL/冷重建收口 |
| （v2.7.0，M2-32 UA 特征引擎）4.2 只写"device_type / os 来自 UA-Parser 库"，6.2 的容器维度初稿写成 `micromessenger`，而 CH 的 MV 字面量是 `'wechat'`/`'douyin'` | 三处共用一套词汇表却没有指定它是哪个串时，"规则命中微信"与"统计里的微信 PV"就会各用各的字符串，而 `countIf(device_type='wechat')` 在写成 `micromessenger` 之后会**静默恒为 0**（不报错、看板只是没数）；另外 UA 串本身不该当标识——它是会变的事实 | 4.2 补"取值词汇表"：`device_type`=容器 key（wechat/douyin/qq/weibo/alipay/unknown）、`os`=设备大类 key（ios/android/desktop/unknown），并把列名与语义的错位写明（改名要动 CH 迁移、历史行分类会失效，本轮不改名）；6.2 补特征表落地形状（表在 `application.yml`、代码不留默认、key 必须在枚举里否则启动即失败、顺序是语义）；UA 串（`micromessenger`/`aweme`）只作为 tokens 存在。`accept-m2-32.sh` 用 13 条真实 UA 把这三处贯通验一次 |
| （v2.8.0，M2-33 一级决策）6.2 里"中间页 HTML 本身可缓存 CDN"，以及"跳转服务读取 route_rule"两条与实现冲突 | 中间页带着**这一次点击**的 `xy_click_id` 与归因参数，可缓存就等于把 A 的 click_id 发给 B——归因静默作废；而数据面读的是编译产物快照，`route_rule` 它压根查不到。另外实现时发现"首条生效"如果按数组顺序判，快照乱序（M3 补偿、手工修数据、校验器上线前的旧行）会让某条规则永远轮不到，且不报错 | 缓存那条改为 **`Cache-Control: no-store`**（并把二级降级那句同步改掉）；新增"一级决策的落地口径"八条：匹配语义（含 geo 恒不匹配、time_window 含首尾、不认识的维度不匹配）、`unknown` 可被 `eq` 显式命中、四类 target → 响应形态映射、中间页 no-store 且每个值按落点语境转义两次、归因只拼一次且页内复用、开关落在 `JumpResolver` 层但两个实现共用抽象链、无规则时与 direct302 逐字节同形、`UrlLinkProvider` 只有 mock。数据面改为按 priority 排序后再取首条命中 |
| （v2.9.0，M2-34 mock 购票页）6.2 说"mock 购票页回显 query/fragment"，读者会以为 nginx 能把 fragment 也印出来；同时"302 终点变成可看点"这句话没有写清它依赖什么 | fragment（`#…`）只留在浏览器里，压根不发到服务端，服务端回显它是做不到的；而终点是 `https://mock.ticketsales.test/…`（9.2 强制 https），本栈只有 :80、`.test` 又不可解析 ⇒ 不加 443 与本地自签证书，那句"可看"就是空的 | 6.2 增"回显 mechanics 与可看点的前提"一条：页面用 JS 读 `location.search`/`location.hash`；`make tls` 幂等生成 `deploy/compose/tls/`（不入库）并挂成 `up` 的前置（目录缺失时 Docker 会建成空目录，nginx 只在容器日志里报 cannot load certificate）；未登记 Host 在 443 上同样按 9.5 的白名单语义掐断。`make demo` 新增 4b 段，跟着 302 真打一次终点并把输出固化成演示的一部分 |
| （v2.10.0，M3-01 对账键）4.2 的 `click_event` 字段表里没有 `click_id`，而附录 B 早已把 `click_id` 定为订单落库字段之一 | 点击侧从来没把这个值发给过统计库（URL 上有、消息体里没有），所以"订单 ↔ 点击"在设计里成立、在数据里做不到——而 M3 的转化率与端到端对账都建立在它上面 | 4.2 补 `click_id` 一行并写清两个空串含义（历史行 / 被拦击，后者用 `risk_score` 区分）；6.4 与 8.5 从"缺口"改成"已补 + 边界"；`trace_id` 明确不落库（只留一列对外键）。排序键不动（它是 ReplacingMergeTree 的去重依据），跳数索引等实测点查耗时出来再决定；历史行不回填 |
| （v2.11.0，M3-02 outbox 同步器）8.4 从 v2.0 起就用完成态写着"事务内写主表 + outbox 表，**异步同步**路由表 + **失效缓存**"，代码里这三件事一件都没有：outbox 只有三处 INSERT、零读方，`xsl:route:evict` 这类广播通道不存在，"version 强校验通道"只有 jump 回源那半个 Lua | 创建/更新时管理面是**直写** `link_route` 与缓存的，直写成功时看不出缺口，所以 M1-06 收口的验收脚本全绿而补偿链路是空的；`LinkService` 的注释一直老实写着"待 M3 补偿重放"，但 8.4 没有对应的"待"字 | 把两句话拆成可实施的口径（8.4 两条各加一段落点细化）：同步器在 sl-admin（5s 一轮 / ≤500 行 / `0→1`，失败 `retry_count++` 超阈置 `2` 并停自动重试）；"同步路由表"= **两条条件写**（`WHERE version<?`，0 行受影响时分辨"缺行→补插（路由与归属索引成对补）"与"已被取代→置 1"，而缓存永远写当时最权威的那一份）——这一步顺带给出一个可测性质：**重放整张 outbox 安全**，不可能把路由表或缓存写回旧值；"失效缓存"= Pub/Sub 广播清 L1，且**明确它不进正确性链路**（丢消息由 L1 的 30s TTL 自愈，正确性靠条件写先把 `link_route` 与 L2 改对）。7.2 补 `/internal/**` 的拓扑可达性（不经 nginx、不经租户 HMAC，所以不需要再造一套凭据），8.5 补 `xsl_outbox_*` 与 `xsl_jump_route_evict_total` 并把"outbox 积压"这句话落到实处 |
| （v2.12.0，M3-03 归因回传细化）6.3-3 那句"每 5 分钟聚合推送宣发平台，回传带 `batch_id + 校验和`，平台侧幂等"和 7.3 那行 batch JSON 从 v2.0 起就在，而代码里发送端、接收端、`sl-mock` 模块三者都不存在（`sl-mock/` 是只有 README 的占位目录）；更要紧的是第十章的出口标准"端到端归因对账误差 < 2%"**只有阈值没有算式** | 三处都没落到具体形态就无法实现，也无法验收：`batch_id` 该由什么决定、幂等发生在哪一侧、`valid_pv` 是什么、以及"<2%"到底比什么。本项目已经因"目标没写判定条件"翻过两次（M1 的延迟 SLO 负载、M2 的频控连接数，见 ITER-M1/M2），这次在写代码之前先把算式定死 | 六处补齐：6.3-3 落点细化（发送端在 sl-consumer、事实源取明细 FINAL 而非聚合表的三条理由与"耗时实测决定改不改"的出口、`batch_id` 为什么必须含内容 hash、失败不重试也不推进水位、**迟到超过回看窗口就不进回传**这条边界）；7.3 契约五条（batch 以租户为边界且 `items` 不带 `tenant_id`、排序是 checksum 的一部分、不符 400 拒收、接收端只验签 + 时间戳窗口不做 nonce 表、同 id 幂等 / 新内容覆盖）；6.4 钉死四个对外量的算式并**如实写出 `bot_pv` 恒 0、`valid_pv` 现在等于放行数**（能力边界不是链路缺陷），同时补上 `<2%` 的两条判定式（传输完整性 + 归因可 join，各自给数字不合成总分，且聚合表与明细的差异不混进阈值）；3.3 与 3.4 把 `sl-mock` 从占位改成真模块（256M、端口 8050、不挂对外路由）；8.5 补 `xsl_push_*`；第十章那行出口标准改为指向 6.4 的算式 |
| （v2.13.0，M3-04 端到端对账落地）附录 B 写着"mock 订单接口落库、闭环即成立"，风险 #1 的应对也承诺了它，但订单侧一行代码都没有；同时 6.4 的判定式虽然算出来了，却没说**两侧怎么对齐**、也没说 ② 要怎么证明它不是自证 | 没有订单侧数据，② 就无从算起；而对齐方式一旦含糊，最先受害的是结论——"平台收到的 Σpv"与"明细 pv"如果都取"现在"，回传只推已关闭分钟这件事会稳定制造一个非零差，看着像丢数据；② 若两边都从同一个集合派生，"100% 命中"就是同义反复 | 附录 B 补实现落点（`POST /mock/orders` + `xsl_mock.mock_order`（表名带前缀：`order` 是保留字）；字段是那六个**加一个 `order_id`**，调用方生成、当主键做幂等，重复提交不虚高；这一跳**刻意不鉴权并写明理由**——它两端都在 mock 侧、不是我们系统的对外契约；下单者是脚本从 302 的 Location 抓真实 `xy_click_id`，静态购票页不加副作用）；6.4 补两条执行细节（① 两侧必须取同一个已关闭的 `[from,to)`；② 除主组外还要注入引用不存在 click_id 的坏订单，断言缺失条数恰好等于注入条数，否则 ② 是自证）与"核对工具 `recon-attribution.sh` 与验收 `accept-m3-04.sh` 分成两份"的理由；8.5 补 `xsl_mock_order_total{result}`；3.4 把 sl-mock 的定位写成"扮演外部世界" |
| （v2.13.1，M3-04 收口回填）落地时才撞上两处"算错了不会被发现"的口子，计划里都没有：① 只验了"能算出 0.00%"，没验"真丢数时它报什么"；② 的窗口该按哪个时钟切也没定 | 这两处都属于"结论看着对、口径其实没钉住"：① 如果没有负样本，回传整批挂掉时工具会报一个无害的 `n/a`；② 如果按 `create_time`（mock 服务器的 UTC 时钟）切，正确的订单会被整批算成找不到，报出一个纯假造的 100% 缺失 | 6.4 补三条收口口径：**① 也配负样本**（另起一批"只点击、不回传"的活动，必须报 **100.00% 而不是 `n/a`**——"两侧都没数"与"数丢光了"必须是两个能区分的值）、**② 按订单自带的 `first_click_time` 切窗口**（与 `click_time`/`minute` 同一个时钟；附录 B 与 `03-mock-schema.sql` 那条"两把时钟别互相比较"在这里咬人）、**核对工具的 `RESULT` 行一字段一值**（窗口写成 ISO 的 `T`，人类可读的说明行才保留空格形式）；8.5 的订单计数补 `rejected_validation`（缺键拒收要和"重复吸收"分开看，全挤在一个值里就失去了定位价值）。顺带修一条旧账：**文档头的版本号从 v2.11.0 起就没跟着附录 A 走**（写着 v2.10.0 而变更记录已到 v2.13.0，diff 核对确认是 M3-02 那轮起漏的），本次对齐成 v2.13.1，此后版本号与附录 A 同批更新 |
| （v2.14.0，M3-05 看板定口径）三处"文档写着、代码没有"与"计划里没有"的地方一次性摊开：① 7.1 那行 `GET /stats/links/{code}` 从 v2.2 起就写着 `from,to,granularity`，实际响应只有 `click_count` 一个数，而 `GET /stats/campaigns/{id}` 根本没实现；② 3.2/144 写的技术栈是 Vue3 + Vite + **Element Plus** + ECharts，而同一行自己又写着"不做管理后台全家桶"；③ 3.4/165 写着 console-web 占 128M 并"同源反代**管理面** API"，而管理面那条路要求 HMAC 签名、浏览器不能放密钥 | ① 是接口表从没被当作契约核对过（M3 之前没人真读过它）；② 是当初按"企业后台"的惯性选库，落到三页只读视图上就成了一个没人需要的重依赖；③ 是**把看板当成管理面的一个视图**来写的——而浏览器里放不进那把 key（key 本身就是 HMAC secret，放进去丢的是写权限），这条不通就整个页面出不来 | 7.1 把四条只读接口的参数与响应写实（并写明 `click_count` 必须保留，`make demo` 与 `accept-m1-09` 在读它）；7.2 新增一条"看板可达性与边界"：只读、不经 HMAC、聚合数字对入口开放这条**如实写进 9.1 风险表**，租户号放路径而不是放头（放头会被读成"有隔离"），不做看板专用 token；144 去掉 Element Plus（同理不引 TS/Pinia）；165 改成"不占独立容器、8081 静态托管 + 同源反代 consumer"；6.4 补看板取数口径五条（走明细 `FINAL` 及为何不能走聚合表、新增 `blocked_pv=countIf(risk_score>=25)` 与"当前 `pv = valid_pv + blocked_pv`、评分层交付后会破"这条诚实说明、本期 UV 是精确值所以 ±0.81% 那条不适用于页面、窗口左闭右开与粒度自动规则、**转化那一档留空并注明未接入**）；第十章那行"看板三页面数据与直查一致"补上判定式（`accept-m3-05.sh` 逐字段 **0 容差** + 真实浏览器读页面数字）；8.5 补 `xsl_stats_query_total`/`xsl_stats_seconds{endpoint,window}` 并照 M3-02 的规矩要求被读到过一次 |

## 附录 B：归因字段契约（与星演购票页/宣发平台对齐）

跳转追加参数：`xy_click_id, channel_id, campaign_id, promoter_id, utm_source, utm_medium, utm_campaign, utm_term, utm_content`。
订单落库归因字段：`click_id, channel_id, campaign_id, promoter_id, first_click_time, last_click_time`（首次/末次归因口径由宣发平台配置）。

实现落点（个人环境）：mock 购票页接收并回显归因参数、mock 订单接口落库，本地即可跑通"点击 → 订单 → 渠道漏斗"完整闭环，契约与生产形态保持一致。
v2.13.0 细化（M3-04）：订单接口是 `POST /mock/orders`，落 `xsl_mock.mock_order`（与宣发平台同库，同在 sl-mock；表名带前缀是因为 `order` 是 MySQL 保留字，裸用会到处逼着加反引号）。字段就是上面那六个归因字段 **+ 一个 `order_id`**——`order_id` 由调用方生成并当主键，重复提交返回已有记录、**不重复计入**（真实形态里订单号本来就是购票系统生成的，用它做幂等最自然；不做的话"用户双击"会把转化率算掉一半）。这一跳**刻意不鉴权**：它扮演的是"购票页后端 → 平台"，两端都在 mock 侧、不碰业务库，而 7.3 里我们系统的对外契约只有"归因回传"那一行；真实形态的鉴权由购票系统与平台另谈，现在套一层假 HMAC 只会多一个没人验证的东西。下单者是验收脚本扮演的用户：从 302 的 `Location` 里抓真实 `xy_click_id` 再去下单——**不把购票静态页改成自动下单**（它到"回显归因参数"为止，加副作用会牵动 6.2 的 UA 重放断言，换不到对账价值）。

> **这个对账键是怎么补上的（时间线）**：M2-35/36 收口时才发现明细表压根没有 `click_id` 列——订单回传闭环可以跑，对账脚本却无从 join。M3-01 按"先改契约、再动代码、必重跑旧验收"补成 4.2 里那一行（选 `click_id` 而不是 `trace_id`：前者才是订单侧带回的串）。历史行为空串且**不回填**（那个值从没进过消息体）。
