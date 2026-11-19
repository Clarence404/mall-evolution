# -*- coding: utf-8 -*-
"""把一份 JFR 录制汇总成几张表（定位实验 experiments/i-locate 用），不依赖 JDK 以外的工具。

用法：python hp_jfr.py <xxx.jfr> [--out 目录] [--threads 正则] [--top N]
环境变量 MALL_JAVA_HOME 或 JAVA_HOME 指向 JDK 21+（用它的 bin/jfr 把事件打印成文本再解析）。

输出（写到 --out，默认是 jfr 文件旁边的 <名字>-summary/）：
  cpu-by-layer.csv     CPU 采样按「线程类别 × 归到哪一层」计数。一个采样归给谁：从栈顶往下找，跳过 JDK 自己的帧，
                       第一个能认出来的库就是它（比如栈顶在 HashMap.put、往下第一帧是 Jackson，就算 JSON）
  cpu-top-frames.csv   出现在栈顶次数最多的方法（自身耗时）
  cpu-stacks.txt       折叠后的调用栈（「根;…;栈顶 次数」一行一个），可以直接喂给任何火焰图工具
  alloc-by-layer.csv   分配采样按同样的规则归层，按估算的字节数汇总
  alloc-top-class.csv  分配最多的类型
  socket-by-peer.csv   网络读写按「线程类别 × 对端」汇总：次数、总耗时、字节数（等 MySQL、等库存服务各花了多久）
  wait-top.csv         锁等待与线程挂起（1ms 以上）按栈顶几帧汇总
  summary.txt          以上各表的前几行，方便直接看
"""
import argparse, csv, os, re, subprocess, sys
from collections import Counter, defaultdict

# 一帧属于哪一层：按顺序匹配，先中先得
LAYERS = [
    ("业务代码", r"^io\.github\.clarence404\."),
    ("JSON（Jackson）", r"^(tools\.jackson|com\.fasterxml\.jackson)\."),
    ("MySQL 驱动", r"^com\.mysql\."),
    ("MyBatis", r"^org\.(apache\.ibatis|mybatis)\."),
    ("连接池（HikariCP）", r"^com\.zaxxer\.hikari\."),
    ("JWT 验签（Nimbus）", r"^com\.nimbusds\."),
    ("Spring Security", r"^org\.springframework\.security\."),
    ("HTTP 客户端（HttpClient 5）", r"^org\.apache\.hc\."),
    ("熔断与舱壁（Resilience4j）", r"^io\.github\.resilience4j\."),
    ("指标与追踪（Micrometer）", r"^io\.(micrometer|opentelemetry)\."),
    ("日志（Logback）", r"^(ch\.qos\.logback|org\.slf4j|org\.apache\.logging)\."),
    ("参数校验（Hibernate Validator）", r"^(org\.hibernate\.validator|jakarta\.validation)\."),
    ("Redis 客户端（Lettuce）", r"^(io\.lettuce|org\.springframework\.data\.redis)\."),
    ("本机缓存（Caffeine）", r"^com\.github\.benmanes\."),
    ("RocketMQ 客户端", r"^(org\.apache\.rocketmq|io\.grpc|com\.google\.protobuf)\."),
    ("Nacos 客户端", r"^com\.alibaba\."),
    ("Netty / Reactor", r"^(io\.netty|reactor)\."),
    ("Spring 事务与 JDBC", r"^org\.springframework\.(transaction|jdbc|dao)\."),
    ("Spring Cloud Gateway", r"^org\.springframework\.cloud\.gateway\."),
    ("Spring MVC / WebFlux", r"^org\.springframework\.(web|http)\."),
    ("Spring 其他（AOP、容器）", r"^org\.springframework\."),
    ("Tomcat", r"^org\.apache\.(catalina|coyote|tomcat)\."),
]
LAYERS = [(n, re.compile(rx)) for n, rx in LAYERS]
JDK = re.compile(r"^(java|javax|jdk|sun|com\.sun)\.")

THREADS = [
    ("Tomcat 业务线程", r"^http-nio-\d+-exec-"),
    ("Tomcat 接入线程", r"^http-nio-\d+-(Poller|Acceptor)"),
    ("Netty 事件循环", r"^(reactor-http-nio|nioEventLoopGroup|lettuce-(nio|epoll)EventLoop)"),
    ("消息消费线程", r"(?i)(rocketmq|consume|MessageConsumption|PushConsumer)"),
    ("定时任务与 outbox", r"^(scheduling-|outbox|task-)"),
    ("GC 线程", r"^(G1 |GC Thread|ZGC|ZWorker|ZDriver|XWorker|ParGC|VM Thread)"),
    ("JIT 编译线程", r"^(C1|C2) CompilerThre"),
    ("连接池维护线程", r"^HikariPool"),
]
THREADS = [(n, re.compile(rx)) for n, rx in THREADS]

PEERS = {3306: "MySQL", 6379: "Redis", 8082: "库存服务", 8083: "支付服务", 8080: "mall-app", 8848: "Nacos", 9848: "Nacos",
         8081: "RocketMQ", 9000: "网关"}


def thread_group(name):
    for n, rx in THREADS:
        if rx.search(name): return n
    return "其他线程"


def layer_of(frames):
    """frames 栈顶在前。跳过 JDK 的帧，第一个认得的库；整条栈都是 JDK 的归「JDK 自身」"""
    for f in frames:
        if JDK.match(f): continue
        for n, rx in LAYERS:
            if rx.match(f): return n
        return "其他库（" + ".".join(f.split(".")[:3]) + "）"
    return "JDK 自身（没有经过任何库）"


def jfr_bin():
    home = os.environ.get("MALL_JAVA_HOME") or os.environ.get("JAVA_HOME")
    if not home: sys.exit("要设置 MALL_JAVA_HOME 或 JAVA_HOME（JDK 21+）")
    return os.path.join(home, "bin", "jfr.exe" if os.name == "nt" else "jfr")


def events(jfr, names, depth=0):
    """逐个产出事件：{'type':..., 字段名: 值, 'stack': [栈顶在前的帧]}。jfr print 的文本输出一个事件一段：类型 { 字段 = 值 ... }"""
    cmd = [jfr_bin(), "print", "--events", ",".join(names)]
    if depth: cmd += ["--stack-depth", str(depth)]
    p = subprocess.Popen(cmd + [jfr], stdout=subprocess.PIPE, encoding="utf-8", errors="replace")
    ev = None; in_stack = False
    for line in p.stdout:
        s = line.strip()
        if ev is None:
            m = re.match(r"^(jdk\.\w+) \{$", s)
            if m: ev = {"type": m.group(1), "stack": []}
            continue
        if in_stack:
            if s == "]": in_stack = False
            elif s and s != "...":
                ev["stack"].append(re.sub(r"\(.*$", "", s).strip())   # 去掉参数表和行号
            continue
        if s == "}":
            yield ev; ev = None; continue
        m = re.match(r"^(\w+) = (.*)$", s)
        if not m: continue
        k, v = m.group(1), m.group(2)
        if k == "stackTrace" and v.startswith("["):
            in_stack = not v.endswith("]")
        else:
            ev[k] = v
    p.wait()


def thread_name(v):
    m = re.match(r'^"(.*)" \(', v or "")
    return m.group(1) if m else (v or "?")


def dur_ms(v):
    """'1.23 ms' / '45.6 us' / '2.1 s' / '0 s' → 毫秒"""
    m = re.match(r"^([\d.,]+) (ns|us|µs|μs|ms|s|m)$", (v or "").strip())
    if not m: return 0.0
    x = float(m.group(1).replace(",", ""))
    return x * {"ns": 1e-6, "us": 1e-3, "µs": 1e-3, "μs": 1e-3, "ms": 1.0, "s": 1e3, "m": 6e4}[m.group(2)]


def size_bytes(v):
    m = re.match(r"^([\d.,]+) (bytes|kB|MB|GB)$", (v or "").strip())
    if not m: return 0.0
    return float(m.group(1).replace(",", "")) * {"bytes": 1, "kB": 1e3, "MB": 1e6, "GB": 1e9}[m.group(2)]


def write(path, header, rows):
    with open(path, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f); w.writerow(header); w.writerows(rows)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("jfr"); ap.add_argument("--out"); ap.add_argument("--top", type=int, default=40)
    ap.add_argument("--threads", help="只统计线程名匹配这个正则的采样（默认全部）")
    a = ap.parse_args()
    out = a.out or os.path.splitext(a.jfr)[0] + "-summary"
    os.makedirs(out, exist_ok=True)
    only = re.compile(a.threads) if a.threads else None
    lines = []

    # ---- CPU 采样
    by_layer = Counter(); top = Counter(); stacks = Counter(); per_group = Counter(); n = 0
    for e in events(a.jfr, ["jdk.ExecutionSample"], depth=256):
        t = thread_name(e.get("sampledThread"))
        if only and not only.search(t): continue
        g = thread_group(t); fr = e["stack"]
        if not fr: continue
        n += 1; per_group[g] += 1
        by_layer[(g, layer_of(fr))] += 1
        top[(g, fr[0])] += 1
        stacks[g + ";" + ";".join(reversed(fr))] += 1
    write(os.path.join(out, "cpu-by-layer.csv"), ["thread_group", "layer", "samples", "pct_of_all", "pct_of_group"],
          [(g, l, c, round(100 * c / n, 2), round(100 * c / per_group[g], 2)) for (g, l), c in by_layer.most_common()])
    write(os.path.join(out, "cpu-top-frames.csv"), ["thread_group", "frame", "samples", "pct_of_all"],
          [(g, f, c, round(100 * c / n, 2)) for (g, f), c in top.most_common(a.top * 3)])
    with open(os.path.join(out, "cpu-stacks.txt"), "w", encoding="utf-8") as f:
        for k, c in stacks.most_common(): f.write(f"{k} {c}\n")
    lines.append(f"CPU 采样 {n} 个。按线程类别：" + "，".join(f"{g} {c}（{100 * c / n:.1f}%）" for g, c in per_group.most_common()))
    lines.append("按层（前 25）：")
    lines += [f"  {100 * c / n:5.1f}%  {g} / {l}" for (g, l), c in by_layer.most_common(25)]
    lines.append("栈顶方法（前 25）：")
    lines += [f"  {100 * c / n:5.1f}%  {g} / {f}" for (g, f), c in top.most_common(25)]

    # ---- 分配采样
    al = Counter(); cls = Counter(); tot = 0.0
    for e in events(a.jfr, ["jdk.ObjectAllocationSample"], depth=64):
        t = thread_name(e.get("eventThread"))
        if only and not only.search(t): continue
        w = size_bytes(e.get("weight")); tot += w
        al[(thread_group(t), layer_of(e["stack"]))] += w
        cls[re.sub(r" \(classLoader.*$", "", e.get("objectClass", "?"))] += w
    if tot:
        write(os.path.join(out, "alloc-by-layer.csv"), ["thread_group", "layer", "est_mb", "pct"],
              [(g, l, round(w / 1e6, 1), round(100 * w / tot, 2)) for (g, l), w in al.most_common()])
        write(os.path.join(out, "alloc-top-class.csv"), ["class", "est_mb", "pct"],
              [(c, round(w / 1e6, 1), round(100 * w / tot, 2)) for c, w in cls.most_common(a.top)])
        lines.append(f"分配采样估算共 {tot / 1e6:.0f} MB。按层（前 15）：")
        lines += [f"  {100 * w / tot:5.1f}%  {g} / {l}" for (g, l), w in al.most_common(15)]
        lines.append("按类型（前 10）：")
        lines += [f"  {100 * w / tot:5.1f}%  {c}" for c, w in cls.most_common(10)]

    # ---- 网络读写
    so = defaultdict(lambda: [0, 0.0, 0.0])
    for e in events(a.jfr, ["jdk.SocketRead", "jdk.SocketWrite"]):
        t = thread_name(e.get("eventThread"))
        if only and not only.search(t): continue
        try: port = int(e.get("port", "0").replace(",", ""))
        except ValueError: port = 0
        peer = PEERS.get(port, "其他（多为入站连接的对端）")
        kind = "读" if e["type"].endswith("Read") else "写"
        x = so[(thread_group(t), peer, kind)]
        x[0] += 1; x[1] += dur_ms(e.get("duration")); x[2] += size_bytes(e.get("bytesRead") or e.get("bytesWritten"))
    if so:
        rows = sorted(((g, p, k, c, round(ms, 1), round(ms * 1000 / c, 1), round(b / 1e6, 2)) for (g, p, k), (c, ms, b) in so.items()),
                      key=lambda r: -r[4])
        write(os.path.join(out, "socket-by-peer.csv"), ["thread_group", "peer", "op", "count", "total_ms", "avg_us", "mb"], rows)
        lines.append("网络读写（按总耗时，前 15）：")
        lines += [f"  {r[4]:10.0f} ms  {r[3]:8d} 次  平均 {r[5]:8.0f} µs  {r[0]} / {r[1]} / {r[2]}" for r in rows[:15]]

    # ---- 锁等待与挂起
    wt = defaultdict(lambda: [0, 0.0])
    for e in events(a.jfr, ["jdk.JavaMonitorEnter", "jdk.ThreadPark"], depth=12):
        t = thread_name(e.get("eventThread"))
        if only and not only.search(t): continue
        g = thread_group(t)
        if g != "Tomcat 业务线程": continue   # 其他线程挂起是在等活干，不是在等别人
        fr = [f for f in e["stack"] if not re.match(r"^(jdk\.internal\.misc\.Unsafe|java\.util\.concurrent\.locks\.)", f)][:3]
        x = wt[(e["type"].replace("jdk.", ""), " ← ".join(fr))]
        x[0] += 1; x[1] += dur_ms(e.get("duration"))
    if wt:
        rows = sorted(((k, s, c, round(ms, 1)) for (k, s), (c, ms) in wt.items()), key=lambda r: -r[3])[:a.top]
        write(os.path.join(out, "wait-top.csv"), ["event", "top_frames", "count", "total_ms"], rows)
        lines.append("Tomcat 业务线程的锁等待与挂起（1ms 以上，按总耗时，前 10）：")
        lines += [f"  {r[3]:10.0f} ms  {r[2]:6d} 次  {r[0]}  {r[1]}" for r in rows[:10]]

    with open(os.path.join(out, "summary.txt"), "w", encoding="utf-8") as f: f.write("\n".join(lines) + "\n")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
