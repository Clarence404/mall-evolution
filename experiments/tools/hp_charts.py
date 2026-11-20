# -*- coding: utf-8 -*-
"""高性能实验（I 定位、J 数据访问、K JVM、L 微基准）的图。数据取 experiments/out/ 下的各轮结果，同一变体的多轮取中位数。

用法：python hp_charts.py i_cpu i_sql j_paging j_batch k_hump k_pause k_cold l_exception     # 要画哪几张（另有 k_tail，文章里没用）
环境变量：HA_OUT（结果目录，默认 ../out）、HA_ASSETS（图输出目录，指到文档仓库的 docs/assets/high-perf）、
          HP_SINCE（只用目录名不小于它的轮次，默认 20261119-18）、MALL_JAVA_HOME（i_cpu 要用 JDK 的 jfr 命令汇总录制）
"""
import csv, json, math, re, statistics, subprocess, sys
from collections import defaultdict
from ha_lib import *
from ha_charts import STYLE, ASSETS, DASH

SINCE = os.environ.get("HP_SINCE", "20261119-18")
HERE = os.path.dirname(os.path.abspath(__file__))


def med(xs): return statistics.median(xs) if xs else 0.0


def by_variant(exp):
    res = defaultdict(list)
    for name, v, d, bad in runs(exp, include_bad=True):
        # 压测端没跑完的（没有 latency.csv）和手工标了 EXCLUDED.txt 的轮次不用，后者里写着原因
        if name >= SINCE and os.path.exists(os.path.join(d, "latency.csv")) and not os.path.exists(os.path.join(d, "EXCLUDED.txt")): res[v].append(d)
    return res


def tw(s, size):
    """估一行字有多宽：汉字按字号，其余按 0.55 倍"""
    return sum(size if ord(c) > 255 else size * 0.55 for c in s)


def save(fname, o):
    os.makedirs(ASSETS, exist_ok=True)
    with open(os.path.join(ASSETS, fname), "w", encoding="utf-8", newline="\n") as f: f.write("\n".join(o) + "\n")
    print("wrote", fname)


def head(W, H, title, legend=()):
    o = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}" role="img" aria-label="{title}">', STYLE,
         f'<rect x="0" y="0" width="{W}" height="{H}" rx="8" fill="var(--bg)"/>',
         f'<text x="{W/2:.0f}" y="26" text-anchor="middle" font-size="14" font-weight="700">{title}</text>']
    if legend:
        widths = [14 + 6 + tw(n, 12) + 22 for n, _ in legend]
        x = (W - sum(widths) + 22) / 2
        for (n, c), w in zip(legend, widths):
            o.append(f'<line x1="{x:.0f}" y1="48" x2="{x+14:.0f}" y2="48" stroke="var(--{c})" stroke-width="8"/>')
            o.append(f'<text x="{x+20:.0f}" y="52" font-size="12">{n}</text>')
            x += w
    return o


def hbars(fname, title, rows, series, unit="", fmt="{:.1f}", note=""):
    """横向条形图。rows: [(标签, [每个系列的值])]；series: [(系列名, 颜色变量)]，只有一个系列时不画图例"""
    ns = len(series); bh, bgap, ggap = 14, 8, 16
    lw = max(tw(l, 12) for l, _ in rows) + 16
    W = 720; L = max(120, lw); R = 90; T = 66 if ns > 1 else 46
    gh = ns * bh + (ns - 1) * bgap
    H = T + len(rows) * (gh + ggap) - ggap + (40 if note else 22)
    vmax = max(v for _, vs in rows for v in vs) or 1
    pw = W - L - R
    o = head(W, H, title, series if ns > 1 else ())
    y = T
    for label, vs in rows:
        o.append(f'<text x="{L-10:.0f}" y="{y + gh/2 + 4:.1f}" text-anchor="end" font-size="12">{label}</text>')
        for i, v in enumerate(vs):
            by = y + i * (bh + bgap); w = max(1.0, pw * v / vmax)
            o.append(f'<line x1="{L}" y1="{by + bh/2:.1f}" x2="{L + w:.1f}" y2="{by + bh/2:.1f}" stroke="var(--{series[i][1]})" stroke-width="{bh}"/>')
            o.append(f'<text class="m" x="{L + w + 6:.1f}" y="{by + bh/2 + 4:.1f}" font-size="11">{fmt.format(v)}{unit}</text>')
        y += gh + ggap
    if note: o.append(f'<text class="m" x="{W/2:.0f}" y="{H-12}" text-anchor="middle" font-size="11">{note}</text>')
    o.append("</svg>")
    save(fname, o)


def lines(fname, title, series, xticks, ylabel, xlabel, ymin, ymax, log=False, yticks=None, yfmt="{:g}"):
    """折线图，横轴是若干个等距的档位（xticks 是档位的标签），纵轴可以取对数。series: [(名字, 颜色变量, [每个档位的值，None 表示没有])]"""
    W, H = 720, 380; L, R, T, B = 66, 24, 78, 62
    pw, ph = W - L - R, H - T - B; n = len(xticks)
    X = lambda i: L + pw * (i + 0.5) / n
    if log:
        Y = lambda y: T + ph * (1 - (math.log10(max(y, ymin)) - math.log10(ymin)) / (math.log10(ymax) - math.log10(ymin)))
        yticks = yticks or [10 ** k for k in range(int(math.floor(math.log10(ymin))), int(math.ceil(math.log10(ymax))) + 1)]
    else:
        Y = lambda y: T + ph * (1 - (min(y, ymax) - ymin) / (ymax - ymin))
        yticks = yticks or [ymin + (ymax - ymin) * i / 5 for i in range(6)]
    o = head(W, H, title)
    widths = [28 + 8 + tw(nm, 12) + 22 for nm, _, _ in series]
    x = (W - sum(widths) + 22) / 2
    for i, (nm, c, _) in enumerate(series):
        o.append(f'<line x1="{x:.0f}" y1="48" x2="{x+28:.0f}" y2="48" stroke="var(--{c})" stroke-width="2.5"' + (f' stroke-dasharray="{DASH[i % len(DASH)]}"' if DASH[i % len(DASH)] else "") + "/>")
        o.append(f'<text x="{x+36:.0f}" y="52" font-size="12">{nm}</text>')
        x += widths[i]
    for yv in yticks:
        o.append(f'<line x1="{L}" y1="{Y(yv):.1f}" x2="{W-R}" y2="{Y(yv):.1f}" stroke="var(--border)" stroke-width="1"/>')
        o.append(f'<text class="m" x="{L-8}" y="{Y(yv)+4:.1f}" text-anchor="end" font-size="11">{yfmt.format(yv)}</text>')
    for i, lab in enumerate(xticks):
        o.append(f'<line x1="{X(i):.1f}" y1="{T+ph}" x2="{X(i):.1f}" y2="{T+ph+4}" stroke="var(--muted)" stroke-width="1"/>')
        o.append(f'<text class="m" x="{X(i):.1f}" y="{T+ph+18}" text-anchor="middle" font-size="11">{lab}</text>')
    o.append(f'<line x1="{L}" y1="{T}" x2="{L}" y2="{T+ph}" stroke="var(--muted)" stroke-width="1"/>')
    o.append(f'<line x1="{L}" y1="{T+ph}" x2="{W-R}" y2="{T+ph}" stroke="var(--muted)" stroke-width="1"/>')
    o.append(f'<text class="m" x="{L}" y="{T-10}" text-anchor="middle" font-size="11">{ylabel}</text>')
    o.append(f'<text class="m" x="{L+pw/2:.0f}" y="{H-16}" text-anchor="middle" font-size="11">{xlabel}</text>')
    for i, (nm, c, vals) in enumerate(series):
        pts = [(X(k), Y(v)) for k, v in enumerate(vals) if v is not None]
        p = " ".join(f"{a:.1f},{b:.1f}" for a, b in pts)
        o.append(f'<polyline points="{p}" fill="none" stroke="var(--{c})" stroke-width="2" stroke-linejoin="round"' + (f' stroke-dasharray="{DASH[i % len(DASH)]}"' if DASH[i % len(DASH)] else "") + "/>")
        for a, b in pts: o.append(f'<circle cx="{a:.1f}" cy="{b:.1f}" r="3" fill="var(--{c})"/>')
    o.append("</svg>")
    save(fname, o)


def latency(run):
    """latency.csv：请求名 -> {n, mean, p50, p99, p999, p9999, max}（毫秒，只取成功的、不含预热）"""
    out = {}
    with open(os.path.join(run, "latency.csv"), encoding="utf-8-sig") as f:
        for r in csv.DictReader(f):
            if "预热" in r["request"] or not r["status"].startswith("2"): continue
            out[r["request"]] = dict(n=int(r["count"]), mean=int(r["mean_us"]) / 1000, p50=int(r["p50_us"]) / 1000, p99=int(r["p99_us"]) / 1000,
                                     p999=int(r["p999_us"]) / 1000, p9999=int(r["p9999_us"]) / 1000, max=int(r["max_us"]) / 1000)
    return out


def chart_j_paging():
    """深分页：翻到不同深度时三种写法的平均耗时（对数纵轴）"""
    agg = defaultdict(lambda: defaultdict(list))
    for d in by_variant("j-data-access")["j2-deep-paging"]:
        for k, v in latency(d).items():
            m = re.match(r"翻到第 (\d+) 行：(.+)", k)
            if m: agg[int(m.group(1))][m.group(2)].append(v["mean"])
    depths = sorted(agg)
    lab = lambda x: "第 1 页" if x == 0 else (f"{x // 10000} 万行" if x >= 10000 else f"{x} 行")
    lines("exp-data-access-paging.svg", "订单列表翻到不同深度时，一次查询的平均耗时", [
        ("偏移量", "r", [med(agg[x]["偏移量"]) or None for x in depths]),
        ("延迟关联", "y", [med(agg[x]["延迟关联"]) or None for x in depths]),
        ("游标", "g", [med(agg[x]["游标"]) or None for x in depths]),
    ], [lab(x) for x in depths], "耗时 ms（对数）", "翻到第几行（大客户共 20 万单，每页 20 行）", 0.5, 5000, log=True, yticks=[1, 10, 100, 1000])


def chart_j_batch():
    """批量写：各种写法每秒写入的行数"""
    agg = defaultdict(list)
    for v in ("j1-batch-write", "j1-batch-write-rewrite"):
        for d in by_variant("j-data-access")[v]:
            for r in csv.DictReader(open(os.path.join(d, "batch-write.csv"), encoding="utf-8-sig")):
                agg[(r["mode"], int(r["batch_size"]), v.endswith("rewrite"))].append(int(r["rows_per_sec"]))
    both = lambda m, b: med(agg[(m, b, False)] + agg[(m, b, True)])   # 和驱动改不改写无关的写法：两个变体合起来
    rows = [("逐条，每条自动提交", [both("single", 1)]), ("逐条，1000 行提交一次", [both("single-tx", 1000)]),
            ("JDBC 批量 1000 行，驱动不改写", [med(agg[("jdbc-batch", 1000, False)])]),
            ("一条语句 100 行", [both("multi-values", 100)]), ("一条语句 1000 行", [both("multi-values", 1000)]),
            ("JDBC 批量 100 行，驱动改写", [med(agg[("jdbc-batch", 100, True)])]),
            ("JDBC 批量 1000 行，驱动改写", [med(agg[("jdbc-batch", 1000, True)])])]
    hbars("exp-data-access-batch.svg", "往订单明细表写 2 万行：各种写法每秒写入的行数", rows, [("每秒行数", "b")], fmt="{:,.0f}", unit=" 行/秒")



def jfr_layers(run, service, group):
    """一轮里某个服务的 CPU 采样：{层: 采样数}，只算 group 这一类线程。没汇总过就先调 hp_jfr.py 汇总"""
    jfr = os.path.join(run, "jfr", service + ".jfr")
    out = os.path.join(run, "jfr", service + "-summary")
    if not os.path.exists(os.path.join(out, "cpu-by-layer.csv")):
        if not os.path.exists(jfr): return {}
        subprocess.run([sys.executable, os.path.join(HERE, "hp_jfr.py"), jfr], check=True, stdout=subprocess.DEVNULL)
    res = defaultdict(int)
    with open(os.path.join(out, "cpu-by-layer.csv"), encoding="utf-8-sig") as f:
        for r in csv.DictReader(f):
            if r["thread_group"] == group: res[r["layer"]] += int(r["samples"])
    return res


def chart_i_cpu(variant="i2-index-hint"):
    """定位实验：mall-app 处理请求的线程，CPU 采样落在哪一层（几轮合计）"""
    tot = defaultdict(int)
    for d in by_variant("i-locate")[variant]:
        for k, v in jfr_layers(d, "mall-app-1", "Tomcat 业务线程").items(): tot[k] += v
    n = sum(tot.values())
    if not n: print("没有采样"); return
    top = sorted(tot.items(), key=lambda kv: -kv[1])
    rows = [(k, [100 * v / n]) for k, v in top[:11]]
    rest = sum(v for _, v in top[11:])
    if rest: rows.append(("其余", [100 * rest / n]))
    hbars("exp-locate-cpu-layers.svg", "mall-app 处理请求的线程，CPU 采样落在哪一层", rows, [("占比", "p")], unit="%",
          note=f"{len(by_variant('i-locate')[variant])} 轮合计 {n} 个采样；一个采样归给调用栈里离栈顶最近的那个库")


def digest(run):
    """vm-mysql-digest.txt：[(库, 次数, 平均微秒, 扫描行数, 返回行数, 语句)]"""
    p = os.path.join(run, "vm-mysql-digest.txt"); out = []
    if not os.path.exists(p): return out
    for line in open(p, encoding="utf-8-sig").read().splitlines()[1:]:
        c = line.split("\t")
        if len(c) < 9: continue
        out.append((c[0], int(c[1]), float(c[3]), int(c[5]), int(c[6]), c[8]))
    return out


def window_requests(run):
    """要看的那一段（语句统计清零到取回）里前台的请求数：速率固定，按时长折算"""
    reqs = sum(v["n"] for v in latency(run).values())
    t = [int(e["epoch_ms"]) for e in events(run, "script-events.csv") if "要看的那一段" in e["event"] or "已取回" in e["event"]]
    meas = [int(e["t_s"]) for e in events(run) if "开始计数" in e["event"]]
    dur = max(r["t"] for r in timeline(run)) - (meas[0] if meas else 0)
    win = (t[1] - t[0]) / 1000 if len(t) >= 2 else dur
    return reqs * win / dur


def sql_per_request(variant):
    """{类别: 每个请求几条语句}，同一变体多轮取中位数"""
    vals = defaultdict(list)
    for d in by_variant("i-locate")[variant]:
        rows = digest(d)
        if not rows: continue
        n = window_requests(d)
        chat = sum(c for _, c, _, _, _, q in rows if re.match(r"^(SELECT @@|SET )", q))
        commit = sum(c for _, c, _, _, _, q in rows if q.startswith("COMMIT"))
        biz = sum(c for _, c, _, _, _, q in rows) - chat - commit
        vals["业务语句"].append(biz / n); vals["COMMIT"].append(commit / n); vals["会话状态的查询与设置"].append(chat / n)
    return {k: med(v) for k, v in vals.items()}


def chart_i_sql():
    """定位实验：每处理一个请求，发给 MySQL 几条语句——驱动默认连接属性与调整之后"""
    a, b = sql_per_request("i2-index-hint"), sql_per_request("i3-jdbc-params")
    if not a or not b: print("缺语句统计"); return
    rows = [(k, [a[k], b[k]]) for k in ("业务语句", "COMMIT", "会话状态的查询与设置")]
    hbars("exp-locate-sql-per-request.svg", "平均每个请求发给 MySQL 几条语句", rows, [("驱动默认连接属性", "r"), ("记住会话状态之后", "g")], unit=" 条", fmt="{:.2f}",
          note="三个库合计（含后台的关单与消息中继），除以同一段时间里前台的请求数")


def chart_k_tail():
    """JVM 实验：各收集器、各堆大小下订单列表的 P99、P99.9 与最大耗时"""
    rv = by_variant("k-jvm"); rows = []
    for v, label in [("k1-g1-1g", "G1 1GB"), ("k1-g1-512m", "G1 512MB"), ("k1-zgc-1g", "ZGC 1GB"), ("k1-zgc-512m", "ZGC 512MB"),
                     ("k1-parallel-1g", "Parallel 1GB"), ("k1-parallel-512m", "Parallel 512MB"), ("k1-serial-1g", "Serial 1GB"), ("k1-serial-512m", "Serial 512MB"),
                     ("k1-g1-1g-jdk25", "G1 1GB（JDK 25）"), ("k1-zgc-1g-jdk25", "ZGC 1GB（JDK 25）")]:
        ls = [latency(d).get("GET /api/orders") for d in rv[v]]; ls = [x for x in ls if x]
        if ls: rows.append((label, [med([x["p99"] for x in ls]), med([x["p999"] for x in ls]), med([x["max"] for x in ls])]))
    if rows: hbars("exp-jvm-tail-latency.svg", "订单列表在各收集器、各堆大小下的尾部耗时", rows, [("P99", "b"), ("P99.9", "y"), ("最大", "r")], unit=" ms")


def chart_k_cold():
    """JVM 实验：不预热直接全速、不预热但慢启动、预热过，头 60 秒逐秒的中位耗时（对数纵轴）"""
    rv = by_variant("k-jvm")
    def per_sec(d, offset=0):
        b = defaultdict(list)
        for r in timeline(d):
            if r["req"].startswith("GET /api/orders") and "预热" not in r["req"] and r["st"] == "200": b[r["t"] - offset].append((r["n"], r["p50"]))
        out = []
        for t in range(1, 61):
            xs = b.get(t)
            out.append(max(0.5, sum(n * p for n, p in xs) / sum(n for n, _ in xs)) if xs else None)
        return out
    def warm_offset(d):
        ev = [int(e["t_s"]) for e in events(d) if "开始计数" in e["event"]]; return ev[0] if ev else 60
    series = []
    for v, label, c in [("k2-cold", "不预热，第 0 秒全速", "r"), ("k2-ramp", "不预热，30 秒爬坡", "y"), ("k1-g1-1g", "预热 60 秒之后", "g")]:
        if rv[v]:
            d = rv[v][-1]; series.append((label, c, per_sec(d, warm_offset(d) if v == "k1-g1-1g" else 0)))
    if series:
        lines("exp-jvm-cold-start.svg", "刚启动的实例接流量：头 60 秒逐秒的中位耗时", series, [str(t) if t % 10 == 0 else "" for t in range(1, 61)],
              "中位耗时 ms（对数）", "开始接流量后的秒数", 0.5, 10000, log=True, yticks=[1, 10, 100, 1000, 10000])


def jmh(since=None, include_quick=False):
    """out/l-jmh/ 下各次完整运行的结果：{(基准短名, 参数): [(得分, 误差, 每次调用分配字节, 单位)]}"""
    res = defaultdict(list); base = os.path.join(OUT, "l-jmh")
    for name in sorted(os.listdir(base)) if os.path.isdir(base) else []:
        p = os.path.join(base, name, "jmh.json"); info = os.path.join(base, name, "run-info.txt")
        if name < (since or SINCE) or not os.path.exists(p): continue
        if not include_quick and os.path.exists(info) and "quick=True" in open(info, encoding="utf-8-sig").read(): continue
        for b in json.load(open(p, encoding="utf-8")):
            short = ".".join(b["benchmark"].split(".")[-2:]); par = ",".join(f"{k}={v}" for k, v in sorted((b.get("params") or {}).items()))
            alloc = (b.get("secondaryMetrics") or {}).get("gc.alloc.rate.norm", {}).get("score", 0.0)
            pm = b["primaryMetric"]; err = pm.get("scoreError")
            res[(short, par)].append((pm["score"], err if isinstance(err, (int, float)) else 0.0, alloc, pm["scoreUnit"]))
    return res


def chart_l_exception(include_quick=False):
    """微基准：在不同深度的调用栈下抛一次业务异常要多久"""
    r = jmh(include_quick=include_quick); rows = []
    for depth in (1, 30, 120):
        vals = []
        for m in ("throwWithStackTrace", "throwWithoutStackTrace", "returnErrorCode"):
            xs = r.get((f"BizExceptionBenchmark.{m}", f"depth={depth}"))
            vals.append(statistics.mean(x[0] for x in xs) if xs else 0.0)
        rows.append((f"调用栈 {depth} 层", vals))
    if any(v for _, vs in rows for v in vs):
        hbars("exp-micro-exception.svg", "在不同深度的调用栈下抛一次业务异常的耗时", rows, [("抛异常，带调用栈", "r"), ("抛异常，不采集调用栈", "y"), ("不抛，逐层返回错误码", "g")], unit=" ns", fmt="{:,.0f}")


def gatling_dist(run, request):
    """Gatling 报告页里某个请求的耗时分布：{毫秒: 占全部请求的百分比}。只在桶宽是 1ms 时有意义（耗时范围小的那些轮）"""
    import glob
    for f in glob.glob(os.path.join(run, "gatling", "req_*.html")):
        s = open(f, encoding="utf-8", errors="replace").read()
        t = re.search(r"<title>Gatling Stats - ([^<]*)</title>", s)
        if not t or t.group(1).strip() != request: continue
        i = s.find("renderTo: 'responseTimeDistributionContainerId'")
        cats = re.search(r"categories: \[([^\]]*)\]", s[i:]); data = re.search(r"name: 'OK',\s*data: \[([^\]]*)\]", s[i:])
        xs = [int(c.strip(" '")) for c in cats.group(1).split(",") if c.strip()]
        ys = [float(c) for c in data.group(1).split(",") if c.strip()]
        return dict(zip(xs, ys))
    return {}


def chart_k_hump():
    """JVM 实验：订单列表的耗时分布（每 1ms 一档，对数纵轴）——JDK 21 上 15–20ms 处多出一个小峰，JDK 25 上没有"""
    rv = by_variant("k-jvm"); series = []
    for v, label, c in [("k1-g1-1g", "G1，JDK 21", "r"), ("k1-zgc-512m", "ZGC，JDK 21", "y"), ("k1-g1-1g-jdk25", "G1，JDK 25", "g")]:
        ds = [gatling_dist(d, "GET /api/orders") for d in rv[v]]
        ds = [d for d in ds if d and len(d) > 2 and sorted(d)[1] - sorted(d)[0] == 1]
        if not ds: continue
        vals = []
        for ms in range(1, 26):
            x = med([d.get(ms, 0.0) for d in ds]); vals.append(x if x >= 0.01 else None)
        series.append((label, c, vals))
    if series:
        lines("exp-jvm-latency-hump.svg", "订单列表的耗时分布：每 1ms 一档里有多少请求", series, [str(ms) if ms % 5 == 0 or ms == 1 else "" for ms in range(1, 26)],
              "占全部请求 %（对数）", "耗时 ms", 0.01, 100, log=True, yticks=[0.01, 0.1, 1, 10, 100])


_GC_TS = re.compile(r"^\[(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d+)([+-]\d{4})\]")


def gc_pauses(run):
    """GC 日志里正式计数那一段的停顿（毫秒列表）。一次停顿在日志里有一行「Pause … 12.345ms」"""
    import datetime
    files = [f for f in os.listdir(run) if f.startswith("gc-") and f.endswith(".log")]
    if not files: return []
    t0 = started_ms(run); ms = [int(e["t_s"]) for e in events(run) if "开始计数" in e["event"]]
    a = t0 + (ms[0] if ms else 0) * 1000; b = t0 + max(r["t"] for r in timeline(run)) * 1000
    out = []
    for line in open(os.path.join(run, files[0]), encoding="utf-8", errors="replace"):
        m = _GC_TS.match(line)
        if not m or "Pause" not in line or "[safepoint" in line: continue
        t = int(datetime.datetime.strptime(m.group(1) + m.group(2), "%Y-%m-%dT%H:%M:%S.%f%z").timestamp() * 1000)
        if t < a or t > b: continue
        mm = re.search(r"Pause [^\n]*?(\d+\.\d+)ms\s*$", line)
        if mm and ("->" in line or "gc,phases" in line): out.append(float(mm.group(1)))
    return out


def chart_k_pause():
    """JVM 实验：正式压测的 240 秒里，各收集器、各堆大小一共停顿了多少毫秒"""
    rv = by_variant("k-jvm"); rows = []
    for c, cn in [("serial", "Serial"), ("parallel", "Parallel"), ("g1", "G1"), ("zgc", "ZGC")]:
        for h, hn in [("384m", "384MB"), ("512m", "512MB"), ("1g", "1GB")]:
            ps = [sum(gc_pauses(d)) for d in rv[f"k1-{c}-{h}"] if os.path.exists(os.path.join(d, "latency.csv"))]
            ps = [x for x in ps if x > 0]
            if ps: rows.append((f"{cn} {hn}", [med(ps)]))
    if rows: hbars("exp-jvm-gc-pause.svg", "240 秒里垃圾回收一共让业务线程停了多久", rows, [("停顿合计", "b")], unit=" ms", fmt="{:,.0f}",
                   note="GC 日志里计数阶段全部停顿相加，同一变体各轮取中位数；240 秒的 1% 是 2400 ms")

if __name__ == "__main__":
    for n in sys.argv[1:]: globals()["chart_" + n]()
