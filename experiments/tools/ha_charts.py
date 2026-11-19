# -*- coding: utf-8 -*-
"""Draw the experiment line charts (SVG with the site's CSS variables) from timeline.csv."""
import sys
from collections import defaultdict
from ha_lib import *

# 图输出到哪里：环境变量 HA_ASSETS（文档仓库的 docs/assets/high-avail），不设就写到当前目录的 charts/
ASSETS = os.environ.get("HA_ASSETS") or os.path.join(os.getcwd(), "charts")
STYLE = """<style>
:root { --fg:#1a1a2e; --muted:#6b7280; --bg:#f8f9fc; --card:#ffffff; --border:#e2e8f0; --p:#6366f1; --b:#0284c7; --g:#059669; --y:#d97706; --r:#dc2626; }
:root[data-theme="dark"] { --fg:#e5e7eb; --muted:#9ca3af; --bg:#1a1a2e; --card:#252540; --border:#3a3a5c; --p:#818cf8; --b:#38bdf8; --g:#34d399; --y:#fbbf24; --r:#f87171; }
@media (prefers-color-scheme: dark) { :root { --fg:#e5e7eb; --muted:#9ca3af; --bg:#1a1a2e; --card:#252540; --border:#3a3a5c; --p:#818cf8; --b:#38bdf8; --g:#34d399; --y:#fbbf24; --r:#f87171; } }
text { font-family: -apple-system, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif; fill: var(--fg); }
.m { fill: var(--muted); }
</style>"""
DASH = ["", "6 3", "2 3", "8 3 2 3", "1 2"]


def chart(fname, title, series, xmax, ymax, ylabel, xlabel="压测开始后的秒数", events=(), xstep=60, yticks=5, yfmt="{:.0f}", ymarks=(), xticks=None, guides=()):
    """series: [(name, colorvar, [(x, y), ...])]; events: [(x, label)]"""
    W, H = 720, 380
    L, R, T, B = 62, 24, 78, 62
    pw, ph = W - L - R, H - T - B
    X = lambda x: L + pw * x / xmax
    Y = lambda y: T + ph * (1 - min(y, ymax) / ymax)
    o = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}" role="img" aria-label="{title}">', STYLE,
         f'<rect x="0" y="0" width="{W}" height="{H}" rx="8" fill="var(--bg)"/>',
         f'<text x="{W/2:.0f}" y="26" text-anchor="middle" font-size="14" font-weight="700">{title}</text>']
    # legend, centered
    widths = [28 + 8 + sum(13 if ord(c) > 255 else 7 for c in n) + 22 for n, _, _ in series]
    x = (W - sum(widths) + 22) / 2
    for i, (n, c, _) in enumerate(series):
        o.append(f'<line x1="{x:.0f}" y1="48" x2="{x+28:.0f}" y2="48" stroke="var(--{c})" stroke-width="2.5"' + (f' stroke-dasharray="{DASH[i % len(DASH)]}"' if DASH[i % len(DASH)] else "") + "/>")
        o.append(f'<text x="{x+36:.0f}" y="52" font-size="12">{n}</text>')
        x += widths[i]
    # grid + y ticks
    for i in range(yticks + 1):
        yv = ymax * i / yticks
        o.append(f'<line x1="{L}" y1="{Y(yv):.1f}" x2="{W-R}" y2="{Y(yv):.1f}" stroke="var(--border)" stroke-width="1"/>')
        o.append(f'<text class="m" x="{L-8}" y="{Y(yv)+4:.1f}" text-anchor="end" font-size="11">{yfmt.format(yv)}</text>')
    # xticks: [(x, label)]，不给就按 xstep 等距标
    for xv, lab in (xticks or [(v, str(v)) for v in range(0, int(xmax) + 1, xstep)]):
        o.append(f'<line x1="{X(xv):.1f}" y1="{T+ph}" x2="{X(xv):.1f}" y2="{T+ph+4}" stroke="var(--muted)" stroke-width="1"/>')
        o.append(f'<text class="m" x="{X(xv):.1f}" y="{T+ph+18}" text-anchor="middle" font-size="11">{lab}</text>')
    o.append(f'<line x1="{L}" y1="{T}" x2="{L}" y2="{T+ph}" stroke="var(--muted)" stroke-width="1"/>')
    o.append(f'<line x1="{L}" y1="{T+ph}" x2="{W-R}" y2="{T+ph}" stroke="var(--muted)" stroke-width="1"/>')
    o.append(f'<text class="m" x="{L}" y="{T-10}" text-anchor="middle" font-size="11">{ylabel}</text>')
    o.append(f'<text class="m" x="{L+pw/2:.0f}" y="{H-16}" text-anchor="middle" font-size="11">{xlabel}</text>')
    for yv, lab in ymarks:
        o.append(f'<line x1="{L}" y1="{Y(yv):.1f}" x2="{W-R}" y2="{Y(yv):.1f}" stroke="var(--muted)" stroke-width="1" stroke-dasharray="3 3"/>')
        o.append(f'<text class="m" x="{W-R-4}" y="{Y(yv)-5:.1f}" text-anchor="end" font-size="11">{lab}</text>')
    # guides: 参考线 [((x1, y1), (x2, y2))]，灰色虚线，不进图例
    for (x1, y1), (x2, y2) in guides:
        o.append(f'<line x1="{X(x1):.1f}" y1="{Y(y1):.1f}" x2="{X(x2):.1f}" y2="{Y(y2):.1f}" stroke="var(--muted)" stroke-width="1" stroke-dasharray="3 3"/>')
    # events: vertical dashed lines, label above the plot
    for xv, lab in events:
        o.append(f'<line x1="{X(xv):.1f}" y1="{T-4}" x2="{X(xv):.1f}" y2="{T+ph}" stroke="var(--muted)" stroke-width="1" stroke-dasharray="4 3"/>')
        o.append(f'<text class="m" x="{X(xv):.1f}" y="{T-9}" text-anchor="middle" font-size="11">{lab}</text>')
    for i, (n, c, pts) in enumerate(series):
        p = " ".join(f"{X(x):.1f},{Y(y):.1f}" for x, y in pts if x <= xmax)
        o.append(f'<polyline points="{p}" fill="none" stroke="var(--{c})" stroke-width="2" stroke-linejoin="round"' + (f' stroke-dasharray="{DASH[i % len(DASH)]}"' if DASH[i % len(DASH)] else "") + "/>")
    o.append("</svg>")
    os.makedirs(ASSETS, exist_ok=True)
    with open(os.path.join(ASSETS, fname), "w", encoding="utf-8", newline="\n") as f: f.write("\n".join(o) + "\n")
    print("wrote", fname)


def rate_series(run, req, okst, step=10, tmax=360):
    rows = timeline(run); b = defaultdict(lambda: [0, 0])
    for r in rows:
        if r["req"] == req:
            b[r["t"] // step][1] += r["n"]
            if r["st"] == okst: b[r["t"] // step][0] += r["n"]
    return [(k * step + step / 2, 100 * b[k][0] / b[k][1]) for k in sorted(b) if b[k][1] and k * step < tmax]


def run_dir(exp, name): return os.path.join(OUT, exp, name)


def chart_a():
    A = lambda n: run_dir("a-slow-downstream", n)
    DET = "GET /api/products/{skuId}"
    chart("exp-slow-downstream-detail.svg", "库存服务变慢期间，商品详情每 10 秒的成功率", [
        ("无保护", "r", rate_series(A("20261118-103200-a0-no-protection"), DET, "200")),
        ("只加超时", "y", rate_series(A("20261118-103928-a1-timeout"), DET, "200")),
        ("超时 + 舱壁 + 熔断", "b", rate_series(A("20261117-230815-a3-circuit-breaker"), DET, "200")),
        ("再放开连接池", "g", rate_series(A("20261117-232307-a4-pool"), DET, "200")),
    ], 360, 100, "成功率 %", events=[(60, "变慢 5 秒"), (240, "恢复")])


def chart_b():
    B = lambda n: run_dir("b-retry-storm", n)
    Q = "GET /api/orders/{orderNo}/payment"
    chart("exp-retry-storm-success.svg", "20 秒小故障前后，首次查询每 10 秒的成功率", [
        ("两端都不受控", "r", rate_series(B("20261118-112540-b1-naive"), Q, "200", tmax=310)),
        ("只管住 App 端", "y", rate_series(B("20261117-234350-b1-client-only"), Q, "200", tmax=310)),
        ("只管住服务端", "b", rate_series(B("20261117-235031-b1-server-only"), Q, "200", tmax=310)),
        ("两端都受控", "g", rate_series(B("20261118-113234-b1-controlled"), Q, "200", tmax=310)),
    ], 300, 100, "成功率 %", events=[(73, "变慢"), (94, "恢复")], xstep=30)


def chart_c():
    C = lambda n: run_dir("c-rolling-restart", n)
    def fails(run, step=5):
        b = defaultdict(int)
        for r in timeline(run):
            if r["st"] not in ("200", "201", "409"): b[r["t"] // step] += r["n"]
        return [(k * step + step / 2, b.get(k, 0) / step) for k in range(0, 600 // step)]
    chart("exp-rolling-restart-failures.svg", "逐个重启 3 个实例时每秒失败的请求数（总流量 80 次/秒）", [
        ("优雅停机，不先注销", "r", fails(C("20261118-122658-c1-graceful"))),
        ("立即停，停时注销", "y", fails(C("20261118-121520-c0-hard"))),
        ("先注销，等 15 秒再停", "g", fails(C("20261118-124320-c2-offline-first"))),
    ], 600, 30, "失败 次/秒", events=[(60, "停实例 1"), (227, "停实例 2"), (386, "停实例 3")], yticks=3)


def chart_d(limit240="", limit240_degrade=""):
    D = lambda n: run_dir("d-overload", n)
    ORD = "POST /api/orders"
    def p99(run, tmax=360):
        b = defaultdict(float)
        for r in timeline(run):
            if r["req"] == ORD and r["st"] == "201": b[r["t"] // 10] = max(b[r["t"] // 10], r["p99"])
        return [(k * 10 + 5, b[k]) for k in sorted(b) if k * 10 < tmax]
    s = [("不限流", "r", rate_series(D("20261118-114430-d1-none"), ORD, "201")),
         ("限流 300/s", "y", rate_series(D("20261118-115234-d2-limit"), ORD, "201"))]
    if limit240: s.append(("限流 240/s", "g", rate_series(D(limit240), ORD, "201")))
    chart("exp-overload-order-success.svg", "浏览量突增期间，下单每 10 秒的成功率", s, 360, 100, "成功率 %", events=[(60, "浏览量升到 900/s"), (250, "回落")])
    if limit240:
        chart("exp-overload-order-p99.svg", "浏览量突增期间，成功下单的 P99 耗时", [
            ("限流 300/s", "y", p99(D("20261118-115234-d2-limit"))),
            ("限流 240/s", "g", p99(D(limit240))),
        ], 360, 3000, "P99 ms", events=[(60, "浏览量升到 900/s"), (250, "回落")], yticks=6, ymarks=[(3000, "客户端超时 3 秒")])


if __name__ == "__main__":
    if sys.argv[1] == "d": chart_d(*sys.argv[2:]); sys.exit(0)
    for n in sys.argv[1:]: globals()["chart_" + n]()
