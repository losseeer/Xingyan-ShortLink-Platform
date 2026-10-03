# bench/ — 单机压测存档

DESIGN 1.3 那句"简历/分享中引用的所有数字必须来自本节实测记录"就是这里兑现的：
`bench/reports/` 里每一份都是**实际跑出来的输出**，`docs/DESIGN.md` 1.3 的"实测"列只引用这些文件。

## 读数字前必读：这台机器的测量条件

1. **SLO 在"目标负载"上判，不在饱和区判。** DESIGN 1.3 的 `≥2000 QPS` 与 `P99<50ms` 是同一条 SLO：
   要判的是"跑到 2000 QPS 时 P99 是否 <50ms"。`bench/run-jump-loadcurve.sh` 的并发曲线就是为此准备的——
   200 连接那档测的是**容量上限**，不是 SLO 违例（历史上这两个口径混过一次，见 ITER-M2 §4）。
2. **先预热再采样**：容器重建后的第一个点会被 JIT 冷启、Caffeine 冷启与上一轮写入引发的 ClickHouse 合并污染
   （本机实测首点 c=4 只有 1,016 QPS、p99 379ms，比 c=200 还差 4 倍）。loadcurve 已内置 12s 预热 + 等合并排空。
3. **每份报告头部有 `env_snapshot`**：宿主 loadavg、容器内存合计、ClickHouse 合并/mutation 数。
   宿主 16G 用满、loadavg 20+ 时同一份曲线两次能差 2–4 倍——没有这一栏就会把环境噪声读成性能变化。
4. **跨天绝对值不可直接比**：只比同一存档内的相对关系，或条件相同（快照相近）的两份。

## 复现步骤

```bash
make devenv && make images && make up          # 前置：凭据 + 镜像 + 十个容器 healthy
bash bench/setup-hot-links.sh 1000             # 预置 1000 条热码并预热 L1/L2
bash bench/run-jump-bench.sh                   # 跳转吞吐/P99（wrk -t8 -c200 -d60s ×3 轮）
bash bench/run-jump-loadcurve.sh               # 并发曲线：判 SLO 取目标负载行，饱和行只作容量
bash bench/run-jump-diagnosis.sh               # 三层归因：wrk ↔ nginx rt/uct/urt ↔ jump 服务端耗时
bash bench/run-jump-attribution.sh             # 负载进行中逐容器采 CPU（空载采样指认不了瓶颈）
bash bench/run-kafka-bench.sh                  # Kafka 两段测：停 consumer 测堆积 → 放开测追赶
bash bench/run-clickhouse-bench.sh             # ClickHouse 明细量级与聚合查询基准
bash bench/run-create-bench.sh                 # 短码签发 TPS + 唯一性
```

可调环境变量：`ROUNDS` / `DURATION` / `THREADS` / `CONNS`（跳转）、`CURVE_POINTS` / `CURVE_DURATION`（曲线）、`DIAG_CONNS` / `DIAG_DURATION`（归因）、`N_OUTAGE`（堆积）、`CH_ROWS`（聚合）、`N_CREATE` / `CREATORS`（签发）。

## 口径约定（写报告时必须遵守）

1. **同机竞争如实声明**：压测客户端与服务共用一台 M5（Docker VM 7.75 GiB / 10 核），
   吞吐上界包含客户端自身开销，因此一律按保守口径报告，不声称"服务端上限"。
2. **未达标就写未达标**：报告必须给实际值 + 瓶颈分析清单，禁止把目标值写进实测列。
3. **只增不改**：`bench/reports/` 历史文件不修订；结论变化就新增一份，旧的留着（可追溯）。
4. **每份报告自带四元组**：日期、JVM 参数、宿主型号、结果——由 `bench/lib-bench.sh` 的 `host_facts` 自动写头部。
5. **报告文本里的命令不可执行**：说明性命令一律写在单引号里或用文本描述。本仓曾有一条 `echo "…"` 在双引号里用反引号包住 `docker compose down -v`，生成报告时真的执行了一次——写脚本时把这条当硬性规则。
6. **变体跑要带标签**：同一基准换配置再跑时加 `RUN_LABEL`（如 `RUN_LABEL=g1 bash bench/run-jump-bench.sh`），否则存档文件名与默认跑撞名，只能靠时间戳分辨（本轮就出过一次，已把 `…-1348…` 改名 `-g1` 并同步所有引用）。
7. **计数用指标而不是日志**：命中率取 `xsl_jump_route_lookup_total{level=...}` 的轮次增量，
   事件量取 ClickHouse `count() FINAL`，不拿 nginx access log 估算。
8. **文档里每个实测数字，落笔前先 grep 回存档**：`bench/reports/` 是唯一事实源，DESIGN/ITER/wiki 只允许出现
   能在其中点达的值。M2-00 收口自查抓到两个错数：一个凭印象写的 `P50 5.62ms`（任何存档里都没有）、
   一个把已经越线的 c=32（P99 116ms）算进"达标段"——都在 commit 前改回存档原值。
   单位也要核对：wrk 会把亚毫秒自己打成 `us`，按 `m?s` 抠字段会整体失配并把整行摘要落盘
   （`parse-wrk.py` 现统一归一成 ms，`run-jump-loadcurve.sh` 改按字段名取值而不是贪婪 `.*`）。

## 目录

| 文件 | 作用 |
|---|---|
| `lib-bench.sh` | 装载凭据（`scripts/lib-devenv.sh`）、指标快照、consumer lag、宿主事实、报告头部 |
| `setup-hot-links.sh` | 经 nginx→gateway(HMAC)→admin 批量签发热码 + 预热缓存 |
| `wrk/jump-path.lua` | wrk 请求脚本：随机码、固定 Host 头、只认 302 |
| `run-*.sh` | 六类基准的跑批与存档（跳转、并发曲线、归因采样、Kafka 两段、ClickHouse、签发） |
| `parse-wrk.py` / `parse-nginx-timing.py` | wrk 输出与 nginx `rt/uct/urt` 的解析（BSD awk 不能一行写多条规则，故用 python） |
| `reports/` | 存档输出（只增不改），命名 `YYYY-MM-DD-HHMM-m1-<任务>-<对象>[-<变体>].md` |
| `.tmp/` | 中间产物（热码清单等），不入库 |
