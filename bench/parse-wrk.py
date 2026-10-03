#!/usr/bin/env python3
"""把 wrk 的输出解析成一行结构化指标（供 bench/run-jump-loadcurve.sh 与诊断脚本复用）。

为什么不用 awk：BSD awk 不接受同一行上多条 `规则 规则` 的写法（本机报
`syntax error at source line 1`），而 wrk 的分位数表正是"一行一个百分位"的格式。

单位一律归一成 ms。wrk 会按量级自己挑单位：亚毫秒打成 `646.00us`，秒级打成 `1.51s`，
调用方若按 `m?s` 取值就会漏掉 us（本机实测：负载曲线首点 p50=646us，sed 没匹配上，
整行摘要被当成 p50 写进了报告）。
"""
import re
import sys

UNIT_MS = {"us": 1 / 1000, "ms": 1, "s": 1000}

TEXT = sys.stdin.read() if not sys.argv[1:] else open(sys.argv[1]).read()


def raw(pattern, default="-"):
    m = re.search(pattern, TEXT)
    return m.group(1) if m else default


def ms(pattern, default="-"):
    v = raw(pattern, default)
    if v == default:
        return default
    m = re.fullmatch(r"([\d.]+)(us|ms|s)", v)
    if not m:
        return v
    return f"{float(m.group(1)) * UNIT_MS[m.group(2)]:.3f}ms"


qps = f"{float(raw(r'Requests/sec:\s+([\d,.]+)', '0').replace(',', '')):,.0f}"
p50 = ms(r"\n\s+50%\s+([\d.]+\w+)")
p75 = ms(r"\n\s+75%\s+([\d.]+\w+)")
p90 = ms(r"\n\s+90%\s+([\d.]+\w+)")
p99 = ms(r"\n\s+99%\s+([\d.]+\w+)")
# wrk 的摘要行是 `Latency <均值> <标准差> <最大值> <±标准差%>`
avg = ms(r"\n\s+Latency\s+([\d.]+\w+)")
pmax = ms(r"\n\s+Latency\s+[\d.]+\w+\s+[\d.]+\w+\s+([\d.]+\w+)")
reqs = raw(r"([\d,]+) requests in", "0")
non23 = raw(r"(\d+) non-2xx or 3xx responses", "0")
line = (f"QPS={qps}  avg={avg}  p50={p50}  p75={p75}  p90={p90}  p99={p99}  "
        f"max={pmax}  requests={reqs}  non-2xx/3xx={non23}")
err = re.search(r"Socket errors: connect (\d+), read (\d+), write (\d+), timeout (\d+)", TEXT)
if err:
    line += f"  socketErrors={err.group(1)}/{err.group(2)}/{err.group(3)}/{err.group(4)}"
print(line)
