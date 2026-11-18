package io.github.clarence404.mall.product.detail;

import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 仅演示环境：按 {@code mall.demo.detail-cache.*} 关掉商品详情缓存的某一层或某个保护，默认全部打开（和正式环境一样）。
 *
 * <pre>
 * mall.demo.detail-cache.l1-enabled            true   false：不用本机 Caffeine
 * mall.demo.detail-cache.l2-enabled            true   false：不读也不写 Redis
 * mall.demo.detail-cache.single-flight-enabled true   false：L1 未命中时不单飞，并发的请求各自加载
 * mall.demo.detail-cache.null-cache-enabled    true   false：不存在的 SKU 不缓存空值
 * mall.demo.detail-cache.load-delay-ms         0      每次回源多花的毫秒数（占着一条数据库连接）
 * </pre>
 *
 * <p>load-delay-ms 是一个模型，不是真实耗时：演示库里回源只是按主键查一行，一两毫秒就回来，看不出「缓存过期的瞬间一批请求同时回源」
 * 的代价。给它一个明确的值（比如 200ms），相当于「回源是一条 200ms 的慢查询」（真实系统里是多表关联、拼一整个详情页）：
 * 先从连接池取一条连接、开事务，停留这么久，再查库、提交，停留期间连接一直占着。击穿时同时在回源的请求数 ≈ 每秒请求数 × 0.2，
 * 超过连接池大小的部分要排队等连接。
 */
@Slf4j
@Component
@Profile("demo")
class DemoDetailCachePolicy implements DetailCachePolicy {

    private final boolean l1Enabled;
    private final boolean l2Enabled;
    private final boolean singleFlightEnabled;
    private final boolean nullCacheEnabled;
    private final long loadDelayMs;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    DemoDetailCachePolicy(
            @Value("${mall.demo.detail-cache.l1-enabled:true}") boolean l1Enabled,
            @Value("${mall.demo.detail-cache.l2-enabled:true}") boolean l2Enabled,
            @Value("${mall.demo.detail-cache.single-flight-enabled:true}") boolean singleFlightEnabled,
            @Value("${mall.demo.detail-cache.null-cache-enabled:true}") boolean nullCacheEnabled,
            @Value("${mall.demo.detail-cache.load-delay-ms:0}") long loadDelayMs,
            ObjectProvider<PlatformTransactionManager> transactionManager) {
        this(
                l1Enabled,
                l2Enabled,
                singleFlightEnabled,
                nullCacheEnabled,
                loadDelayMs,
                transactionManager.getIfAvailable());
    }

    /** transactionManager 为空（没有数据源的测试）时只停留、不占连接。 */
    DemoDetailCachePolicy(
            boolean l1Enabled,
            boolean l2Enabled,
            boolean singleFlightEnabled,
            boolean nullCacheEnabled,
            long loadDelayMs,
            PlatformTransactionManager transactionManager) {
        this.l1Enabled = l1Enabled;
        this.l2Enabled = l2Enabled;
        this.singleFlightEnabled = singleFlightEnabled;
        this.nullCacheEnabled = nullCacheEnabled;
        this.loadDelayMs = loadDelayMs;
        this.transactionTemplate = transactionManager == null ? null : new TransactionTemplate(transactionManager);
        if (!l1Enabled || !l2Enabled || !singleFlightEnabled || !nullCacheEnabled || loadDelayMs > 0) {
            log.warn(
                    "商品详情缓存按演示开关运行：L1={}，L2={}，单飞={}，空值缓存={}，每次回源多花 {}ms",
                    l1Enabled,
                    l2Enabled,
                    singleFlightEnabled,
                    nullCacheEnabled,
                    loadDelayMs);
        }
    }

    @Override
    public boolean l1Enabled() {
        return l1Enabled;
    }

    @Override
    public boolean l2Enabled() {
        return l2Enabled;
    }

    @Override
    public boolean singleFlightEnabled() {
        return singleFlightEnabled;
    }

    @Override
    public boolean nullCacheEnabled() {
        return nullCacheEnabled;
    }

    @Override
    public <T> T aroundDbLoad(Supplier<T> load) {
        if (loadDelayMs <= 0) {
            return load.get();
        }
        if (transactionTemplate == null) {
            pause();
            return load.get();
        }
        // 事务一开始就从连接池取连接，停留期间一直占着；随后的查询用的是同一条连接
        return transactionTemplate.execute(status -> {
            pause();
            return load.get();
        });
    }

    private void pause() {
        try {
            Thread.sleep(loadDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
