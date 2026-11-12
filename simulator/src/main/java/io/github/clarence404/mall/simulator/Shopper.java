package io.github.clarence404.mall.simulator;

import io.github.clarence404.mall.simulator.SimulatorProperties.Behavior;
import io.github.clarence404.mall.simulator.Stats.Counter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * 一个虚拟用户：反复「打开 App 逛一会 → 离开 → 歇一会再来」。每次来随机挑一个演示账号，做 3～12 件事（看商品、看猜你喜欢、看我的、查订单、偶尔下单），
 * 两件事之间停几秒「思考」；需要登录时才登录，离开时有时退出登录。
 *
 * <p>编号 ≥ 当前应在线人数（流量曲线算出来的）时不出门，只每 5 秒看一眼，这样夜里自然只剩几个人在逛。
 *
 * <p>出错时（连不上、429、5xx）不按思考时间继续，而是指数退避，避免变成「狂刷」。
 */
final class Shopper implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(Shopper.class);

    /** 收货地址：随便挑一个，看起来像不同的人在下单。 */
    private static final List<String> ADDRESSES =
            List.of("杭州市西湖区文三路 100 号", "上海市浦东新区张江路 88 号", "北京市海淀区中关村大街 1 号", "广州市天河区体育西路 66 号");

    private final int index;
    private final TrafficSimulator sim;
    private final SimulatorProperties props;
    private final MallApi api;
    private final Stats stats;
    private final Backoff backoff = new Backoff(Duration.ofSeconds(5), Duration.ofMinutes(5));

    /** 这一次逛的登录态：换账号或退出时清空。 */
    private String phone;

    private String accessToken;
    private String refreshToken;
    private Instant tokenExpiresAt = Instant.MIN;

    Shopper(int index, TrafficSimulator sim) {
        this.index = index;
        this.sim = sim;
        this.props = sim.props();
        this.api = sim.api();
        this.stats = sim.stats();
    }

    @Override
    public void run() {
        RandomGenerator random = ThreadLocalRandom.current();
        // 错开启动：十几个人不要在同一毫秒一起打开 App
        if (!sim.pause(Duration.ofMillis(random.nextLong(1, 5000)))) {
            return;
        }
        while (sim.running()) {
            if (index >= sim.activeShoppers()) {
                if (!sim.pause(Duration.ofSeconds(5))) {
                    return;
                }
                continue;
            }
            visit(random);
            if (!sim.pause(between(props.sessionGap(), random))) {
                return;
            }
        }
    }

    /** 逛一次：换一个账号（先不登录），做若干件事，离开时三成概率退出登录。 */
    private void visit(RandomGenerator random) {
        forget(); // 上次没退出登录的，像关掉 App 一样直接丢掉登录态（令牌自己过期）
        phone = props.accounts().get(random.nextInt(props.accounts().size()));
        int actions = random.nextInt(
                props.sessionActions().min(), props.sessionActions().max() + 1);
        for (int i = 0; i < actions && sim.running() && index < sim.activeShoppers(); i++) {
            boolean failed = act(random);
            Duration wait = failed ? backoff.onFailure(random) : between(props.thinkTime(), random);
            if (!failed) {
                backoff.onSuccess();
            } else if (backoff.failures() == 3) {
                log.warn("连续 {} 次请求失败（连不上、限流或 5xx），退避 {} 秒；检查 {} 的服务是否都已启动", 3, wait.toSeconds(), api.baseUrl());
            }
            if (!sim.pause(wait)) {
                return;
            }
        }
        if (accessToken != null && random.nextDouble() < 0.3) {
            logout();
        }
    }

    /**
     * 做一件事。
     *
     * @return 是否失败到需要退避
     */
    private boolean act(RandomGenerator random) {
        SimulatorProperties.Anomalies a = props.anomalies();
        double r = random.nextDouble();
        if (r < a.wrongPassword()) {
            stats.inc(Counter.ANOMALIES);
            // 输错密码：401，账号不会因此被锁（演示账号没有错误次数限制）
            return api.post(
                            "/api/auth/login",
                            null,
                            Map.of("phone", phone, "password", "wrong-" + random.nextInt(1000)))
                    .shouldBackOff();
        }
        if (r < a.wrongPassword() + a.badRequest()) {
            stats.inc(Counter.ANOMALIES);
            return api.get("/api/products/0", null).shouldBackOff(); // skuId 必须是正整数 → 400
        }
        if (r < a.wrongPassword() + a.badRequest() + a.notFound()) {
            stats.inc(Counter.ANOMALIES);
            // 不存在的商品 → 404；只用 10 个固定的号，空值缓存能命中，不会把缓存命中率拉低
            return api.get("/api/products/" + (29990 + random.nextInt(10)), null)
                    .shouldBackOff();
        }
        Behavior behavior = sim.behaviors().pick(random);
        return switch (behavior) {
            case BROWSE -> browse(random);
            case RECOMMEND -> recommend(random);
            case PROFILE -> profile();
            case MY_ORDERS -> myOrders(random);
            case PURCHASE -> purchase(random);
        };
    }

    /** 看一个商品详情（匿名）。 */
    private boolean browse(RandomGenerator random) {
        return api.get("/api/products/" + sim.browseSkus().pick(random), null).shouldBackOff();
    }

    /** 看详情 → 看「猜你喜欢」→ 点进其中一个。 */
    private boolean recommend(RandomGenerator random) {
        long sku = sim.browseSkus().pick(random);
        if (api.get("/api/products/" + sku, null).shouldBackOff()) {
            return true;
        }
        MallApi.Resp rec = api.get("/api/products/" + sku + "/recommendations", null);
        if (rec.shouldBackOff()) {
            return true;
        }
        if (rec.json() != null && rec.json().isArray() && !rec.json().isEmpty() && random.nextDouble() < 0.6) {
            JsonNode pick = rec.json().get(random.nextInt(rec.json().size()));
            if (!sim.pause(Duration.ofMillis(random.nextLong(500, 3000)))) {
                return false;
            }
            return api.get("/api/products/" + pick.get("skuId").asString(), null)
                    .shouldBackOff();
        }
        return false;
    }

    /** 看「我的」（积分）。 */
    private boolean profile() {
        Boolean ok = ensureLogin();
        if (ok == null) {
            return true;
        }
        return ok && authed(api.get("/api/users/me", accessToken)).shouldBackOff();
    }

    /** 查订单列表，一半概率点进第一单看详情。 */
    private boolean myOrders(RandomGenerator random) {
        Boolean ok = ensureLogin();
        if (ok == null) {
            return true;
        }
        if (!ok) {
            return false;
        }
        MallApi.Resp list = authed(api.get("/api/orders?size=10", accessToken));
        if (list.shouldBackOff()) {
            return true;
        }
        JsonNode items = list.json() == null ? null : list.json().get("items");
        if (items != null && items.isArray() && !items.isEmpty() && random.nextDouble() < 0.5) {
            String orderNo = items.get(random.nextInt(Math.min(3, items.size())))
                    .get("orderNo")
                    .asString();
            return authed(api.get("/api/orders/" + orderNo, accessToken)).shouldBackOff();
        }
        return false;
    }

    /**
     * 下单：看详情 → 提交订单（偶尔重复提交）→ 七成付款（创建支付单 + 模拟渠道回调 + 看订单变成待发货）、其余主动取消或放着等超时关单。
     */
    private boolean purchase(RandomGenerator random) {
        long sku = sim.purchaseSkus().pick(random);
        if (!sim.stockGuard().allowOrder(sku) || !sim.orderQuota().tryAcquire(Instant.now())) {
            stats.inc(Counter.ORDER_SKIPPED); // 库存保护或每小时上限：只逛不买
            return browse(random);
        }
        Boolean ok = ensureLogin();
        if (ok == null) {
            return true;
        }
        if (!ok) {
            return false;
        }
        if (api.get("/api/products/" + sku, null).shouldBackOff()) {
            return true;
        }
        if (!sim.pause(Duration.ofMillis(random.nextLong(1000, 4000)))) { // 选规格、确认地址
            return false;
        }
        String idemKey = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of(
                "lines",
                List.of(Map.of("skuId", String.valueOf(sku), "quantity", 1)),
                "address",
                Map.of(
                        "receiver",
                        "演示用户" + phone.substring(phone.length() - 2),
                        "phone",
                        phone,
                        "detail",
                        ADDRESSES.get(random.nextInt(ADDRESSES.size()))));
        MallApi.Resp placed = placeOrder(idemKey, body);
        if (placed.status() == 409) {
            stats.inc(Counter.ORDER_REJECTED); // 库存不足、待支付订单太多：正常的业务结果
            return false;
        }
        if (!placed.ok()) {
            return placed.shouldBackOff();
        }
        stats.inc(Counter.ORDERS);
        String orderNo = placed.text("orderNo");
        if (random.nextDouble() < props.purchase().duplicateSubmitProbability()) {
            stats.inc(Counter.DUPLICATE_SUBMITS); // 网络卡了再点一次：同一个幂等键，返回同一单（Idempotent-Replayed: true）
            placeOrder(idemKey, body);
        }
        if (random.nextDouble() < props.purchase().payProbability()) {
            return pay(orderNo, random);
        }
        if (random.nextDouble() < props.purchase().cancelProbability()) {
            if (!sim.pause(Duration.ofMillis(random.nextLong(3000, 10000)))) {
                return false;
            }
            MallApi.Resp cancel = authed(api.post("/api/orders/" + orderNo + "/cancel", accessToken, null));
            if (cancel.ok()) {
                stats.inc(Counter.CANCELLED);
            }
            return cancel.shouldBackOff();
        }
        stats.inc(Counter.UNPAID); // 不付款：demo profile 下 1 分钟后自动关单、库存归还
        return false;
    }

    private MallApi.Resp placeOrder(String idemKey, Map<String, Object> body) {
        return authed(api.send("POST", "/api/orders", accessToken, body, Map.of("Idempotency-Key", idemKey)));
    }

    /** 付款：犹豫几秒 → 创建支付单 → 模拟渠道回调（HMAC 签名）→ 轮询订单状态 → 看积分。 */
    private boolean pay(String orderNo, RandomGenerator random) {
        if (!sim.pause(Duration.ofMillis(random.nextLong(2000, 15000)))) { // 远小于 1 分钟的关单时间
            return false;
        }
        MallApi.Resp payment = authed(api.post("/api/payments", accessToken, Map.of("orderNo", orderNo)));
        if (!payment.ok()) {
            return payment.shouldBackOff();
        }
        String paymentNo = payment.text("paymentNo");
        String amount = payment.text("amount");
        String channelTradeNo = "SIM" + System.currentTimeMillis() + random.nextInt(1000);
        String sign = MockPaySigner.sign(props.mockPaySecret(), paymentNo, channelTradeNo, amount);
        MallApi.Resp notify = api.post(
                "/api/payments/notify/mock",
                null,
                Map.of("paymentNo", paymentNo, "channelTradeNo", channelTradeNo, "amount", amount, "sign", sign));
        if (!notify.ok()) {
            return notify.shouldBackOff();
        }
        stats.inc(Counter.PAID);
        // 支付结果经消息异步回到订单：像 App 一样隔一会刷新一次，最多 3 次
        for (int i = 0; i < 3; i++) {
            if (!sim.pause(Duration.ofMillis(1500))) {
                return false;
            }
            MallApi.Resp order = authed(api.get("/api/orders/" + orderNo, accessToken));
            if (order.shouldBackOff() || !"PENDING_PAYMENT".equals(order.text("status"))) {
                break;
            }
        }
        return authed(api.get("/api/users/me", accessToken)).shouldBackOff();
    }

    /**
     * 需要登录的操作之前调：没登录或令牌快过期就登录。
     *
     * @return true 已登录；false 登录被拒（不退避）；null 连不上（要退避）
     */
    private Boolean ensureLogin() {
        if (accessToken != null && Instant.now().isBefore(tokenExpiresAt.minusSeconds(60))) {
            return true;
        }
        MallApi.Resp resp = api.post("/api/auth/login", null, Map.of("phone", phone, "password", props.password()));
        if (resp.ok() && resp.text("accessToken") != null) {
            accessToken = resp.text("accessToken");
            refreshToken = resp.text("refreshToken");
            long expiresIn =
                    resp.json().has("expiresIn") ? resp.json().get("expiresIn").asLong() : 1800;
            tokenExpiresAt = Instant.now().plusSeconds(expiresIn);
            stats.inc(Counter.LOGINS);
            return true;
        }
        if (resp.status() == 401) {
            sim.warnOnce("login-" + phone, "演示账号 " + phone + " 登录失败（401）：mall-app 的演示数据里没有这个账号？检查 simulator.accounts");
        }
        return resp.shouldBackOff() ? null : false;
    }

    /** 令牌失效（401）时清掉，下次需要时重新登录。 */
    private MallApi.Resp authed(MallApi.Resp resp) {
        if (resp.status() == 401) {
            accessToken = null;
        }
        return resp;
    }

    /** 退出登录（吊销刷新令牌），清空登录态。 */
    private void logout() {
        if (refreshToken != null && accessToken != null) {
            api.post("/api/auth/logout", null, Map.of("refreshToken", refreshToken));
        }
        forget();
    }

    /** 只清空本地的登录态，不调接口。 */
    private void forget() {
        accessToken = null;
        refreshToken = null;
        tokenExpiresAt = Instant.MIN;
    }

    private static Duration between(SimulatorProperties.Range<Duration> range, RandomGenerator random) {
        long min = range.min().toMillis();
        long max = range.max().toMillis();
        return Duration.ofMillis(max > min ? random.nextLong(min, max + 1) : min);
    }
}
