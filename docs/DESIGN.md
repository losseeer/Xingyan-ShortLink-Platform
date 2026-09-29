# 星演宣发短链平台设计文档

**项目名称**：星演宣发短链平台（Xingyan ShortLink Platform）

**版本**：v2.2（v2.0 修订自初稿，v2.1 单机环境收敛，v2.1.1 修正单机/单实例混同，v2.2 确定 Vue 看板与 monorepo 代码组织；修订记录见附录 A）

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

| 指标 | 目标 |
|---|---|
| 跳转接口可用性 | 99.9%（单机连续 72h 压测无 5xx） |
| 跳转服务端延迟 | P50 < 10ms，P99 < 50ms |
| 短码生成接口延迟 | P99 < 20ms，可用性 99.9% |
| 跳转链路缓存命中率 | > 99.5% |
| ClickEvent 丢失率 | < 0.01%（本地重试队列兜底后） |
| 归因数据看板延迟 | 分钟级（≤ 5min） |

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
| 前端看板 | Vue 3 + Vite + Element Plus + ECharts | 主办方轻量看板 `console-web`：链接列表、渠道漏斗、有效/疑似刷量双指标（v2.2 决定，不做管理后台全家桶） |

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
| **jump（数据面）×2** | 2×512M | `--scale jump=2`，Nginx upstream 轮询；验证无状态、滚动重启（逐个 kill 观察零中断）、Pub/Sub 多实例失效收敛 |
| mock 购票页 + mock 宣发平台 | 256M | 静态页回显 query + 两个假接口，闭合归因链路 |
| console-web（Vue 看板） | 128M | Nginx 静态托管 + 同源反代管理面 API；主办方视图三页面（v2.2） |
| Sentinel dashboard | 512M | 可选 profile，仅观察用 |
| Prometheus + Grafana | 1G | 可选 profile（`--profile observability`） |
| Redis 主从×3 + Sentinel | +1G | 可选 profile（`--profile redis-ha`）：单机上完整演示异步复制 + 自动故障转移，客户端 Sentinel 配置切主不感知 |

全容器默认合计 ≈ 10G（可选项另计），16G 宿主留安全余量；8G 机器走「极简模式」：去 Kafka/ClickHouse/Nacos，事件改 Redis Stream、统计落 MySQL 聚合表、配置走本地文件——核心链路完整保留，**jump 仍保持 ×2**（见 8.1-C）。所有中间件仅监听 localhost，无公网暴露面。

### 3.4 代码组织：单 monorepo ≠ 单体（v2.2 决定）

仓库组织与架构粒度是**正交的两个维度**：是否"微服务"由进程边界决定（独立部署、独立扩缩、网络通信），与代码放几个 git 仓库无关。本设计的形态：

- **仓库维度：单 monorepo**（Maven 多模块 + 一个前端模块）：

```
xingyan-shortlink/
├── sl-common/          # DTO、工具、错误码、UA 解析封装
├── sl-gateway/         # Spring Cloud Gateway（限流/鉴权/多域名）
├── sl-admin/           # 管理面：生成、规则、租户、统计 API
├── sl-jump/            # 数据面：跳转服务（无状态，默认 ×2 实例）
├── sl-consumer/        # Kafka → ClickHouse 聚合 + 归因回传
├── sl-console-web/     # Vue 3 主办方看板（独立构建，产物进 Nginx）
├── sl-mock/            # mock 购票页 / mock 宣发平台
├── deploy/compose/     # docker-compose.yml + profiles + nginx 配置
└── bench/              # 压测脚本与结果存档（1.3 口径凭证）
```

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
| status | 0-空闲 1-已租用 2-已消耗 |
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

**outbox（本地事务表，随 short_link 同分片键，v2.1.1 补入——4.1/8.4 一直引用但此前漏建条目）**：`id, aggregate_type, aggregate_id(=short_code), event_type, payload_json, status(待投递/已投递/失败), retry_count, create_time`；异步任务据此同步 `link_route.route_json` 与缓存失效。

> 规则存储双轨澄清：`route_rule`（行式，管理面唯一真源，支持逐条校验/冲突检查）在保存时编译为 `link_route.route_json`（文档式，数据面唯一读取源）；两者不一致时以重编译为准，outbox 事件驱动。

**ClickHouse 表**

`click_event`（明细，MergeTree，按 `toYYYYMM(click_time)` 分区、`click_time` 排序键）：

| 字段 | 类型 | 说明 |
|---|---|---|
| event_id | String | 幂等键（Kafka 重复消费去重，ReplacingMergeTree） |
| short_code | String | |
| click_time | DateTime | |
| ip_hash | String | 加盐 SHA-256，盐每日轮换（PIPL，见 9.6） |
| user_agent / referer | String | |
| device_type / os | String | 服务端 UA 解析（UA-Parser 库） |
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
| `sl:rl:{code}:{iphash}:{slot}` | 滑动窗口频控计数 | 60s |
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
    3. 有效性检查：status / expire_time / access_limit（Lua 原子扣减 `sl:cnt:{code}`，
       超限 → 410 或自定义落地页）
    4. 频控与风控（见 5.3；命中拦截 → 安全验证页，不发送真实跳转）
    5. 动态路由匹配（见 6.2）：
       - 命中简单规则（目标为 H5/浏览器直达）→ 步骤 7
       - 命中需客户端降级的目标（小程序/App Scheme）→ 返回中间页 HTML
    6. ClickEvent 投递 Kafka：异步发送 + 失败落本地磁盘重试队列（新增，见 6.3/8.3）
    7. 返回 302 Location = target_url + 归因参数
```

**为何只用 302、禁用 301（新增说明）**：301 被浏览器/CDN 长期缓存后，后续点击不再经过服务端，点击统计与防刷全部失效，且链接失效后无法服务端回收（缓存不受控）。牺牲少量重复点击流量换取数据完整性，是本场景的正确取舍。

**归因参数透传**：目标 URL query 追加 `channel_id / campaign_id / promoter_id / utm_* / xy_click_id`（点击级 ID，供订单精确 Join）。拼接规则：目标 URL 已有 query 用 `&` 续接；所有参数值 URL-encode；**只允许追加白名单参数，禁止覆盖目标 URL 原有参数**（防参数注入）。

### 5.3 防刷风控流程（四层过滤）

1. **网关层**：Sentinel 对 `/s/{code}` 按 IP 限流，默认 60 次/分钟；按租户可配。超限返回 429。
2. **Redis 滑动频控**：`(short_code, ip_hash)` 维度 Lua 原子 `INCR + 首次 EXPIRE`（单脚本执行，消除 inc/expire 竞态）。
3. **行为评分（同步、轻量）**：无/异常 Referer + UA 异常（无 Mobile 标志、Headless 特征）+20；命中 IDC/代理 IP 库 +30；超频阈值 +25。**score ≥ 40 → 返回验证页/安全落地页**而非真实跳转，事件仍记录并标记 `is_bot=1`。IP 库每日离线更新（新增：初稿未提更新机制，IDC 段失效则第三层形同虚设）。
4. **事后审计（异步）**：Consumer 侧模型化二次判定（如按 UA 分布突变、点击-下单时间差异常聚类），修正 `is_bot`，看板输出"有效点击 / 疑似刷量"双指标。

> 评分规则以可配置规则表落地（复用 route_rule 的条件模型思路），避免硬编码；MVP 只做第 1、2、3 层 + 第四层标记透传，模型判定放 P2。

---

## 六、关键模块设计

### 6.1 多租户与数据隔离

- 租户模型：`tenant_id` = 演出项目/主办方；主办方下辖多个项目共享配额。
- 隔离实现：JWT/API Key 携带租户身份 → MyBatis 拦截器自动注入 `tenant_id` 条件（含 JOIN 语句校验，防遗漏）；分片键选择见 4.1。
- 越权防护：所有按 `short_code` 的管理接口先经 `code_tenant_index` 反查归属并比对请求者身份。
- 配额：链接总数、生成速率、访问次数上限（防单租户挤占）三级配额，Redis 计数 + 定时对账。

### 6.2 动态路由引擎

**两级路由模型（修正初稿"302 完成唤起降级"的不可行描述）**：

- **服务端一级决策**：跳转服务读取 `route_rule`（按 priority 升序，首条匹配生效），条件维度：UA 容器（micromessenger/douyin/qq）、device、geo_province、time_window。
- **客户端二级降级**：一级决策目标为 `app_scheme` 或小程序时，返回**中间页**（HTML，本身可缓存 CDN）：
  - 微信容器 + 目标小程序：服务端调用微信开放接口生成 **URL Link / Scheme**（带 30 天有效期控制），中间页直接跳转；失败展示引导。
  - 抖音等容器 + App：中间页 JS `location.href = xingyan://...`，`setTimeout(2.5s)` 内未触发 `visibilitychange/pagehide` 则降级 `location.href = h5_url`；iOS 优先 **Universal Link**（无失败态、更可靠）。

规则示例（存储为 JSON，缓存在 `route_json`）：

```json
[
  {"priority":10,"when":{"ua_container":"micromessenger"},"then":{"target":"wx_urllink","path":"pages/ticket/detail?itemId=123"}},
  {"priority":20,"when":{"ua_container":"douyin"},"then":{"target":"app_scheme","scheme":"xingyan://item/detail?id=123","fallback":"https://m.xingyan.com/item/123"}},
  {"priority":30,"when":{"geo_province":"上海","time":["2026-09-20","2026-09-30"]},"then":{"target":"h5","url":"https://m.xingyan.com/shanghai/pre-sale"}},
  {"priority":99,"when":{"default":true},"then":{"target":"h5","url":"https://m.xingyan.com/item/123"}}
]
```

UA 解析库维护已知容器特征表（月度更新），未识别 UA 一律落到 default 规则（H5），保证新环境不误伤。

### 6.3 归因数据链路

1. **实时写入**：跳转服务 → Kafka topic `shortlink-click`（分区键 `short_code`，同链接事件保序）。发送失败进本地磁盘重试队列（WAL），不阻塞响应。
2. **异步聚合**：Consumer 批量拉取（`max.poll` + 手动提交 offset，at-least-once），按 `event_id` 幂等去重后写 ClickHouse `click_event`；分钟级物化到 `click_stat_minute`。
3. **归因回传**：每 5 分钟按 `(campaign_id, channel_id, minute)` 聚合推送宣发数据平台 API；宣发平台以 `xy_click_id` 与订单归因字段 Join，生成"渠道→点击→转化"漏斗。回传带 `batch_id + 校验和`，平台侧幂等。

### 6.4 统计服务

- 看板查询统一走 ClickHouse（明细抽样 + 聚合表），管理面 MySQL 不参与统计查询。
- 对外指标：PV/UV、有效点击、疑似刷量、分容器到达率、（Join 订单后）转化率。
- 与宣发数据平台的边界：本系统只负责**点击侧**事实数据与归因参数签发；转化侧口径由宣发平台定义，`xy_click_id` 为唯一对账键。

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
| `GET /stats/links/{code}` | 单链接统计 | from,to,granularity |
| `GET /stats/campaigns/{id}` | 活动漏斗 | — |
| `POST /internal/outbox/replay` | 内部：outbox 补偿 | 运维触发 |

创建/更新接口全部幂等化：支持 `Idempotency-Key` 请求头（Redis 24h），防止主办方重试产生重复链接。

### 7.3 外部契约

- **星演购票页**：接收 query 中 `xy_click_id / channel_id / campaign_id / promoter_id / utm_*`，会话内保持，订单落库归因字段（字段清单附录 B）。
- **宣发数据平台**：回传 batch JSON `{batch_id, from, to, items:[{campaign_id, channel_id, minute, pv, uv, valid_pv, bot_pv}]}`，签名鉴权。

---

## 八、非功能设计（新增，初稿缺失）

### 8.1 单机基线、极简模式与容量推演

**A. 个人 PC 实测基线（简历数据的合法来源）**

| 项 | 目标（16G 宿主、Docker 单机） | 测量方式 |
|---|---|---|
| 跳转吞吐 | ≥ 2000 QPS（缓存全命中路径） | wrk `-t8 -c200 -d60s`，loopback |
| 跳转延迟 | P99 < 50ms | 同上 `--latency` |
| Kafka 投递 | ≥ 1 万 events/s 不丢 | 生产者 burst → 查 consumer lag → 追赶清零 |
| ClickHouse 聚合 | 千万级明细秒级聚合 | 物化视图 + 基准 SQL 脚本 |
| 频控 Lua | ≥ 3 万次/s | redis-benchmark 自定义脚本 |
| 短码生成 | ≥ 1000 TPS，无重复码 | JMeter 并发创建 + 唯一性断言 |

压测注意：压测脚本与服务同机抢 CPU，数字按保守口径报告；ClickEvent 分两段测（先关 consumer 测堆积，再开 consumer 测追赶）；每轮压测输出存档进仓库 `bench/`，作为可追溯凭证。

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
- **缓存失效广播**：配置变更后 Redis Pub/Sub 通知各实例清 Caffeine（接受 30s 收敛窗口，紧急封禁走 `version` 强校验通道立即失效）。jump 默认 ×2 实例，跨实例收敛时间在单机即可实测（改配置 → 两实例分别命中新值的时差）。
- **access_limit 计数**：Redis Lua 扣减为权威，每分钟对账回写 MySQL 快照；Redis 灾难重建以快照 + 保守缓冲恢复（宁可少放不可超放）。
- **事件幂等**：`event_id`（点击级 UUID）+ ClickHouse ReplacingMergeTree 去重，Kafka at-least-once。

### 8.5 可观测性（新增）

- Metrics：跳转 QPS/延迟分位/缓存命中率/频控拦截数/bot 比例/池水位/outbox 积压/Kafka lag，Prometheus + Grafana（compose 可选 profile，见 3.3）；告警以 cron 脚本查 PromQL 阈值等价实现（单机无 Alertmanager 必要）。核心 5 项：跳转错误率、命中率 < 97%、Kafka lag、池水位 < 20%、WAL 积压。
- Tracing：跳转请求生成 `trace_id`，随 `xy_click_id` 关联，贯通 ClickEvent。
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
| M3（+3 周） | 归因回传 mock 宣发平台、outbox/对账、**Vue 主办方看板（链接列表 / 渠道漏斗 / 有效·疑似刷量双指标）**、8.3 降级演练全表过一遍、多域名切换演练 | 端到端归因对账误差 < 2%；看板三页面数据与 ClickHouse 直查一致；每条降级预案有一次实测记录 |
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

## 附录 B：归因字段契约（与星演购票页/宣发平台对齐）

跳转追加参数：`xy_click_id, channel_id, campaign_id, promoter_id, utm_source, utm_medium, utm_campaign, utm_term, utm_content`。
订单落库归因字段：`click_id, channel_id, campaign_id, promoter_id, first_click_time, last_click_time`（首次/末次归因口径由宣发平台配置）。

实现落点（个人环境）：mock 购票页接收并回显归因参数、mock 订单接口落库，本地即可跑通"点击 → 订单 → 渠道漏斗"完整闭环，契约与生产形态保持一致。
