#!/usr/bin/env python3
"""从 nginx route 日志算 rt/uct/urt 的分位数（bench/run-jump-diagnosis.sh 的第 ② 层）。

为什么单独成文件而不是内嵌 here-doc：`cmd | python3 - <<'PY' ... PY` 里 here-doc 会占用 stdin，
管道里的日志被整个丢掉，于是解析结果永远是"无样本"——本机踩过一次，报表明面上看还是绿的。
"""
import sys

rt, urt, uct = [], [], []
for line in sys.stdin:
    if " rt=" not in line:
        continue
    try:
        tail = line.split(" rt=", 1)[1].split("]", 1)[0]
        r, rest = tail.split(" uct=")
        c, u = rest.split(" urt=")
        if r.strip() != "-":
            rt.append(float(r) * 1000)
        if c.strip() != "-":
            uct.append(float(c) * 1000)
        if u.strip() != "-":
            urt.append(float(u) * 1000)
    except ValueError:
        continue


def pct(xs, p):
    if not xs:
        return 0.0
    xs = sorted(xs)
    return xs[max(min(int(round(len(xs) * p)) - 1, len(xs) - 1), 0)]


for name, xs in (("rt  nginx 视角总耗时", rt), ("urt 上游应答", urt), ("uct 上游握手", uct)):
    if not xs:
        print(f"- {name}：无样本")
        continue
    print(f"- {name}：n={len(xs)} p50={pct(xs, 0.5):.2f} p95={pct(xs, 0.95):.2f} "
          f"p99={pct(xs, 0.99):.2f} p999={pct(xs, 0.999):.2f} max={max(xs):.2f}")

if rt and urt:
    print(f"- urt/rt 比值：中位数 {pct(urt, 0.5) / pct(rt, 0.5):.2f}、p99 {pct(urt, 0.99) / pct(rt, 0.99):.2f}"
          "（≈1 表示时间在上游应答上；明显小于 1 表示尾巴在 nginx↔客户端之间）")
elif not rt:
    print("- 日志里没解析到 rt= 字段：确认 nginx 已 `make nginx-reload` 生效新版 log_format")
