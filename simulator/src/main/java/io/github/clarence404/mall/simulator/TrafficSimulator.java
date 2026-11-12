package io.github.clarence404.mall.simulator;

import io.github.clarence404.mall.simulator.SimulatorProperties.Behavior;
import io.github.clarence404.mall.simulator.Stats.Counter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 模拟器的总控：随 Spring 容器启动、停止（{@link SmartLifecycle}），管着几类后台线程：
 *
 * <ul>
 *   <li>max-shoppers 个虚拟用户（{@link Shopper}），按流量曲线决定几个在逛
 *   <li>库存巡检：每分钟看一次库存，低于水位就补货（{@link StockGuard}）
 *   <li>突发高峰：偶尔几个用户狂点「提交订单」，触发网关限流（{@link SpikePlanner}）
 *   <li>统计：每分钟打印一行
 * </ul>
 *
 * <p>线程都是 Java 21 的虚拟线程：大部分时间在 sleep 或等 HTTP 响应，用虚拟线程几乎不占资源。停止时把 running 置为 false 并打断所有线程，
 * 它们从 sleep 里醒来就退出（{@link #pause} 返回 false），最多等 5 秒。
 */
@Component
class TrafficSimulator implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TrafficSimulator.class);
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private final SimulatorProperties props;
    private final ConfigurableApplicationContext context;
    private final Stats stats = new Stats();
    private final MallApi api;
    private final LoadCurve curve;
    private final SimClock clock;
    private final WeightedPicker<Behavior> behaviors;
    private final WeightedPicker<Long> browseSkus;
    private final WeightedPicker<Long> purchaseSkus;
    private final StockGuard stockGuard;
    private final HourlyQuota orderQuota;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private final List<Thread> threads = new ArrayList<>();

    private volatile boolean running;
    private volatile Instant startedAt;
    private volatile String spikeState = "未开启";

    TrafficSimulator(SimulatorProperties props, ConfigurableApplicationContext context) {
        this.props = props;
        this.context = context;
        this.api = new MallApi(props.baseUrl(), JsonMapper.builder().build(), stats);
        this.curve = new LoadCurve(props.curve().hourly(), props.curve().minFactor());
        this.clock = new SimClock(Clock.systemDefaultZone(), props.curve().acceleration());
        this.behaviors = new WeightedPicker<>(props.behaviors());
        this.browseSkus = new WeightedPicker<>(props.browseSkus());
        this.purchaseSkus = new WeightedPicker<>(props.purchase().skus());
        this.stockGuard =
                new StockGuard(props.stock().lowWatermark(), props.stock().floor());
        this.orderQuota = new HourlyQuota(props.purchase().maxOrdersPerHour());
        if (props.accounts() == null || props.accounts().isEmpty()) {
            throw new IllegalArgumentException("simulator.accounts 至少要有一个演示账号");
        }
    }

    @Override
    public void start() {
        running = true;
        startedAt = Instant.now();
        printBanner();
        for (int i = 0; i < props.maxShoppers(); i++) {
            threads.add(Thread.ofVirtual().name("shopper-" + (i + 1)).start(new Shopper(i, this)));
        }
        threads.add(Thread.ofVirtual().name("stock-guard").start(this::stockLoop));
        threads.add(Thread.ofVirtual().name("stats").start(this::statsLoop));
        if (props.spike().enabled()) {
            threads.add(Thread.ofVirtual().name("spike").start(this::spikeLoop));
        }
        if (!props.duration().isZero()) {
            threads.add(Thread.ofVirtual().name("timer").start(() -> {
                if (pause(props.duration())) {
                    log.info("已运行 {}，按 simulator.duration 自动退出", props.duration());
                    context.close(); // 关容器 → 调 stop() → keep-alive 线程结束 → JVM 退出
                }
            }));
        }
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        log.info("正在停止：等虚拟用户做完手上的请求……");
        running = false;
        threads.forEach(Thread::interrupt);
        for (Thread t : threads) {
            if (t == Thread.currentThread()) {
                continue; // 按 duration 自动退出时，stop() 就跑在 timer 线程上，不能等自己
            }
            try {
                t.join(Duration.ofSeconds(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.info(
                "已停止。累计：请求 {}，登录 {}，下单 {}（支付 {}、取消 {}、待超时关单 {}），补货 {} 次",
                stats.total(Counter.REQUESTS),
                stats.total(Counter.LOGINS),
                stats.total(Counter.ORDERS),
                stats.total(Counter.PAID),
                stats.total(Counter.CANCELLED),
                stats.total(Counter.UNPAID),
                stats.total(Counter.RESTOCKS));
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void printBanner() {
        LocalDateTime now = clock.now();
        log.info("流量模拟器启动：目标 {}（经网关），演示账号 {} 个", api.baseUrl(), props.accounts().size());
        log.info(
                "速率：最多 {} 个虚拟用户同时在逛（当前时段 {} 个，曲线系数 {}，时间加速 {} 倍），思考时间 {}～{} 秒，每次逛 {}～{} 件事",
                props.maxShoppers(),
                activeShoppers(),
                String.format("%.2f", curve.factor(now.toLocalTime())),
                props.curve().acceleration(),
                props.thinkTime().min().toSeconds(),
                props.thinkTime().max().toSeconds(),
                props.sessionActions().min(),
                props.sessionActions().max());
        log.info(
                "行为占比：{}；下单后付款 {}%，每小时最多 {} 单；异常请求：错密码 {}%、非法参数 {}%、不存在的商品 {}%",
                props.behaviors().keySet().stream()
                        .map(b -> b.name().toLowerCase() + " " + Math.round(behaviors.probability(b) * 100) + "%")
                        .collect(Collectors.joining("、")),
                Math.round(props.purchase().payProbability() * 100),
                props.purchase().maxOrdersPerHour(),
                props.anomalies().wrongPassword() * 100,
                props.anomalies().badRequest() * 100,
                props.anomalies().notFound() * 100);
        log.info(
                "突发高峰：{}；库存保护：低于 {} 补到 {}；{}",
                props.spike().enabled()
                        ? "平均每 " + props.spike().meanInterval().toMinutes() + " 分钟一次、每次 "
                                + props.spike().duration().toMinutes() + " 分钟（最早启动后 "
                                + props.spike().firstAfter().toMinutes() + " 分钟）"
                        : "关闭",
                props.stock().lowWatermark(),
                props.stock().restockTo(),
                props.duration().isZero() ? "一直运行，Ctrl+C 或 IDEA 停止按钮退出" : "运行 " + props.duration() + " 后退出");
    }

    // ———————————————————— 给 Shopper 用的共享状态 ————————————————————

    SimulatorProperties props() {
        return props;
    }

    MallApi api() {
        return api;
    }

    Stats stats() {
        return stats;
    }

    WeightedPicker<Behavior> behaviors() {
        return behaviors;
    }

    WeightedPicker<Long> browseSkus() {
        return browseSkus;
    }

    WeightedPicker<Long> purchaseSkus() {
        return purchaseSkus;
    }

    StockGuard stockGuard() {
        return stockGuard;
    }

    HourlyQuota orderQuota() {
        return orderQuota;
    }

    boolean running() {
        return running;
    }

    /** 当前模拟时刻应该有几个虚拟用户在逛。 */
    int activeShoppers() {
        return curve.activeShoppers(clock.now().toLocalTime(), props.maxShoppers());
    }

    /**
     * 睡一会；停止时被打断就返回 false，调用方据此退出循环。
     *
     * @param duration 时长
     * @return 正常睡醒且仍在运行返回 true
     */
    boolean pause(Duration duration) {
        if (!running) {
            return false;
        }
        try {
            Thread.sleep(duration);
            return running;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 同一个问题只警告一次，免得每个虚拟用户每次都刷一行。 */
    void warnOnce(String key, String message) {
        if (warned.add(key)) {
            log.warn(message);
        }
    }

    // ———————————————————— 库存巡检 ————————————————————

    /** 每 check-interval 看一次库存，低于水位就补货。看库存和补货都要登录（网关校验 JWT），用第一个演示账号。 */
    private void stockLoop() {
        String token = null;
        while (running) {
            if (token == null) {
                MallApi.Resp login = api.post(
                        "/api/auth/login",
                        null,
                        Map.of("phone", props.accounts().getFirst(), "password", props.password()));
                token = login.ok() ? login.text("accessToken") : null;
            }
            if (token != null) {
                token = checkStock(token);
            }
            if (!pause(props.stock().checkInterval())) {
                return;
            }
        }
    }

    /** 看一次库存并按需补货；令牌失效时返回 null，下一轮重新登录。 */
    private String checkStock(String token) {
        String ids = props.stock().skus().stream().map(String::valueOf).collect(Collectors.joining(","));
        MallApi.Resp resp = api.get("/api/demo/inventory/stocks?skuIds=" + ids, token);
        if (resp.status() == 401) {
            return null; // 令牌 30 分钟过期
        }
        if (resp.status() == 404) {
            warnOnce("stocks-404", "看不到库存（/api/demo/inventory/stocks 返回 404）：四个服务没开 demo profile？库存保护只剩每小时下单上限");
            stockGuard.markRestockUnavailable();
            return token;
        }
        if (!resp.ok() || resp.json() == null || !resp.json().isArray()) {
            return token;
        }
        for (JsonNode row : resp.json()) {
            long sku = row.get("skuId").asLong();
            int stock = row.get("stock").asInt();
            if (stockGuard.onStock(sku, stock) == StockGuard.Action.RESTOCK) {
                restock(sku, stock, token);
            }
        }
        return token;
    }

    private void restock(long sku, int before, String token) {
        MallApi.Resp resp = api.send(
                "PUT",
                "/api/demo/inventory/stocks/" + sku + "/restock?target="
                        + props.stock().restockTo(),
                token,
                null,
                Map.of());
        if (resp.ok()
                && resp.json() != null
                && resp.json().isArray()
                && !resp.json().isEmpty()) {
            int after = resp.json().get(0).get("stock").asInt();
            stockGuard.onStock(sku, after);
            stats.inc(Counter.RESTOCKS);
            log.info("补货：商品 {} 库存 {} → {}", sku, before, after);
        } else if (resp.status() == 404 || resp.status() == 405) {
            stockGuard.markRestockUnavailable();
            warnOnce(
                    "restock-404",
                    "补货接口不可用（HTTP " + resp.status() + "）：库存服务是旧版本？改为库存低于 "
                            + props.stock().floor() + " 就停买该商品；更新并重启 inventory-service 后再重启模拟器即可恢复补货");
        }
    }

    // ———————————————————— 突发高峰 ————————————————————

    /**
     * 等到下一次高峰，几个用户（取账号列表最后几个）对一个还没开卖的商品（默认已下架的 20004）狂点「提交订单」，每秒 requests-per-second 次。
     * 网关的下单路由每人每秒只放行 1 次（突发 3 次），多出来的返回 429；放行的那些到订单服务计价时返回 422（商品已下架），
     * 不产生订单、不动库存，业务大盘的下单量曲线也不会被这阵高峰撑高。
     */
    private void spikeLoop() {
        SpikePlanner planner =
                new SpikePlanner(props.spike().firstAfter(), props.spike().meanInterval());
        Instant next = planner.next(Instant.now(), ThreadLocalRandom.current());
        while (running) {
            spikeState = "下一次约在 "
                    + HM.format(LocalDateTime.ofInstant(
                            next, Clock.systemDefaultZone().getZone()));
            log.info("突发高峰：{}", spikeState);
            if (!pause(
                    Duration.between(Instant.now(), next).isNegative()
                            ? Duration.ZERO
                            : Duration.between(Instant.now(), next))) {
                return;
            }
            int users = Math.clamp(props.spike().users(), 1, props.accounts().size());
            log.info(
                    "突发高峰开始：{} 个用户对还没开卖的商品 {} 狂点「提交订单」，每人每秒 {} 次，持续 {} 分钟（网关返回 429，放行的返回 422）",
                    users,
                    props.spike().sku(),
                    props.spike().requestsPerSecond(),
                    props.spike().duration().toMinutes());
            spikeState = "进行中";
            Instant end = Instant.now().plus(props.spike().duration());
            List<Thread> clickers = new ArrayList<>();
            for (int i = 0; i < users; i++) {
                String phone = props.accounts().get(props.accounts().size() - 1 - i);
                clickers.add(Thread.ofVirtual().name("spike-" + (i + 1)).start(() -> hammer(phone, end)));
            }
            for (Thread t : clickers) {
                try {
                    t.join();
                } catch (InterruptedException e) {
                    clickers.forEach(Thread::interrupt);
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            log.info("突发高峰结束");
            next = planner.next(Instant.now(), ThreadLocalRandom.current());
        }
    }

    private void hammer(String phone, Instant end) {
        MallApi.Resp login = api.post("/api/auth/login", null, Map.of("phone", phone, "password", props.password()));
        String token = login.ok() ? login.text("accessToken") : null;
        if (token == null) {
            return;
        }
        Map<String, Object> body = Map.of(
                "lines", List.of(Map.of("skuId", String.valueOf(props.spike().sku()), "quantity", 1)),
                "address", Map.of("receiver", "演示用户", "phone", phone, "detail", "杭州市西湖区文三路 100 号"));
        long intervalMs =
                Math.max(50, (long) (1000 / Math.max(0.1, props.spike().requestsPerSecond())));
        while (running && Instant.now().isBefore(end)) {
            // 每次点击一个新的幂等键（脚本式狂点）；失败的下单不记幂等结果，不占存储
            MallApi.Resp resp = api.send(
                    "POST",
                    "/api/orders",
                    token,
                    body,
                    Map.of("Idempotency-Key", UUID.randomUUID().toString()));
            if (resp.status() == 401) {
                return; // 令牌过期（高峰不超过 30 分钟，一般遇不到）
            }
            if (!pause(Duration.ofMillis(intervalMs))) {
                return;
            }
        }
    }

    // ———————————————————— 统计 ————————————————————

    private void statsLoop() {
        while (pause(props.statsInterval())) {
            Map<Counter, Long> s = stats.drainInterval();
            LocalDateTime simNow = clock.now();
            log.info(
                    "最近 {} 秒：在逛 {}/{} 人（模拟时刻 {}，系数 {}）｜请求 {}：2xx {}、4xx {}、429 {}、5xx {}、连不上 {}｜登录 {}，下单 {}（付款 {}、取消 {}、待关单 {}、被拒 {}、未下 {}），异常请求 {}｜库存 {}｜高峰 {}｜累计下单 {}",
                    props.statsInterval().toSeconds(),
                    activeShoppers(),
                    props.maxShoppers(),
                    HM.format(simNow),
                    String.format("%.2f", curve.factor(simNow.toLocalTime())),
                    s.get(Counter.REQUESTS),
                    s.get(Counter.S2XX),
                    s.get(Counter.S4XX),
                    s.get(Counter.S429),
                    s.get(Counter.S5XX),
                    s.get(Counter.IO_ERRORS),
                    s.get(Counter.LOGINS),
                    s.get(Counter.ORDERS),
                    s.get(Counter.PAID),
                    s.get(Counter.CANCELLED),
                    s.get(Counter.UNPAID),
                    s.get(Counter.ORDER_REJECTED),
                    s.get(Counter.ORDER_SKIPPED),
                    s.get(Counter.ANOMALIES),
                    stockGuard.snapshot().isEmpty() ? "未知" : stockGuard.snapshot(),
                    spikeState,
                    stats.total(Counter.ORDERS));
        }
    }
}
