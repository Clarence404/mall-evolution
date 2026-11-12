package io.github.clarence404.mall.simulator;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模拟器的全部配置（{@code simulator.*}），默认值和每一项的含义写在 {@code application.yml} 里。
 *
 * <p>用 record 做配置类：Spring Boot 通过构造器绑定，绑定完就不可变，多个虚拟用户线程同时读也不用担心。
 *
 * @param baseUrl 网关地址，所有请求都经网关
 * @param accounts 演示账号的手机号
 * @param password 演示账号的密码
 * @param maxShoppers 流量最高时同时在逛的虚拟用户数
 * @param thinkTime 两次操作之间的思考时间
 * @param sessionActions 一次逛做几件事
 * @param sessionGap 两次逛之间歇多久
 * @param duration 跑多久自动退出，0 表示一直跑
 * @param statsInterval 多久打印一行统计
 * @param curve 按时段起伏的流量曲线
 * @param behaviors 各类行为的权重
 * @param browseSkus 浏览哪些商品（权重）
 * @param purchase 下单相关
 * @param anomalies 异常行为的概率
 * @param spike 突发高峰
 * @param stock 库存保护
 * @param mockPaySecret 模拟支付渠道回调的签名密钥
 */
@ConfigurationProperties("simulator")
public record SimulatorProperties(
        String baseUrl,
        List<String> accounts,
        String password,
        int maxShoppers,
        Range<Duration> thinkTime,
        Range<Integer> sessionActions,
        Range<Duration> sessionGap,
        Duration duration,
        Duration statsInterval,
        Curve curve,
        Map<Behavior, Double> behaviors,
        Map<Long, Double> browseSkus,
        Purchase purchase,
        Anomalies anomalies,
        Spike spike,
        Stock stock,
        String mockPaySecret) {

    /** 一个闭区间，在 min～max 之间均匀取值。 */
    public record Range<T>(T min, T max) {}

    /**
     * 流量曲线。
     *
     * @param hourly 0～23 点各整点的相对流量
     * @param minFactor 最低比例
     * @param acceleration 时间加速倍数
     */
    public record Curve(List<Double> hourly, double minFactor, double acceleration) {}

    /**
     * 下单。
     *
     * @param skus 买哪些商品（权重）
     * @param payProbability 下单后付款的比例
     * @param cancelProbability 不付款的订单里主动取消的比例
     * @param duplicateSubmitProbability 用同一个幂等键再提交一次的比例
     * @param maxOrdersPerHour 每小时下单上限
     */
    public record Purchase(
            Map<Long, Double> skus,
            double payProbability,
            double cancelProbability,
            double duplicateSubmitProbability,
            int maxOrdersPerHour) {}

    /**
     * 异常行为，每次操作改做一次出错请求的概率。
     *
     * @param wrongPassword 输错密码
     * @param badRequest 非法参数
     * @param notFound 不存在的商品
     */
    public record Anomalies(double wrongPassword, double badRequest, double notFound) {}

    /**
     * 突发高峰。
     *
     * @param enabled 开关
     * @param firstAfter 启动后至少多久才来第一次
     * @param meanInterval 平均间隔
     * @param duration 每次持续多久
     * @param users 几个用户在狂点
     * @param requestsPerSecond 每人每秒点几次
     */
    public record Spike(
            boolean enabled,
            Duration firstAfter,
            Duration meanInterval,
            Duration duration,
            int users,
            double requestsPerSecond) {}

    /**
     * 库存保护。
     *
     * @param skus 要看着的商品
     * @param checkInterval 多久看一次
     * @param lowWatermark 低于多少补货
     * @param restockTo 补到多少
     * @param floor 补不了货时，低于多少就不再买
     */
    public record Stock(List<Long> skus, Duration checkInterval, int lowWatermark, int restockTo, int floor) {}

    /** 正常行为的种类（yml 里写 browse、my-orders 这样的小写连字符，Spring Boot 会转成枚举）。 */
    public enum Behavior {
        BROWSE,
        RECOMMEND,
        PROFILE,
        MY_ORDERS,
        PURCHASE
    }
}
