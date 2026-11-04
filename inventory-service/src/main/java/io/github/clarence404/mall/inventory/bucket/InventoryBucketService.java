package io.github.clarence404.mall.inventory.bucket;

import io.github.clarence404.mall.inventory.bucket.InventoryBucketMapper.Bucket;
import io.github.clarence404.mall.inventory.stock.InventoryMapper;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 热点库存分桶：大促前一天对活动白名单 SKU 执行 split，大促结束低峰时 merge。
 *
 * <p>为什么默认 10 桶：单行约 600 TPS；最热的 SKU 预计约 2000 TPS，按 1.5 倍余量要 3000 TPS，10 个桶的上限约 6000 TPS。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryBucketService {

    public static final int DEFAULT_BUCKETS = 10;

    private final InventoryMapper inventoryMapper;
    private final InventoryBucketMapper bucketMapper;

    /** 在一个事务里锁住 t_inventory 这一行，把 stock 平均分到 n 个桶，再把 bucket_count 置为 n。 */
    @Transactional(rollbackFor = Exception.class)
    public void split(long skuId, int bucketCount) {
        if (bucketCount < 2 || bucketCount > 100) {
            throw new IllegalArgumentException("桶数应在 2-100 之间");
        }
        InventoryMapper.StockRow row = inventoryMapper.selectForUpdate(skuId);
        if (row == null || row.bucketCount() > 0) {
            throw new IllegalStateException("SKU " + skuId + " 不存在或已分桶");
        }
        int stock = row.stock();
        for (int b = 0; b < bucketCount; b++) {
            bucketMapper.insert(skuId, b, stock / bucketCount + (b < stock % bucketCount ? 1 : 0));
        }
        inventoryMapper.markBucketed(skuId, bucketCount);
        log.info("SKU {} 拆成 {} 个桶，库存 {}", skuId, bucketCount, stock);
    }

    /** 等各桶的预占都确认或释放后，把余量并回 t_inventory，bucket_count 置回 0。 */
    @Transactional(rollbackFor = Exception.class)
    public void merge(long skuId) {
        InventoryMapper.StockRow row = inventoryMapper.selectForUpdate(skuId);
        if (row == null || row.bucketCount() == 0) {
            return;
        }
        List<Bucket> buckets = bucketMapper.selectBySku(skuId);
        if (buckets.stream().anyMatch(b -> b.reserved() != 0)) {
            throw new IllegalStateException("SKU " + skuId + " 仍有未确认的分桶预占，稍后再合并");
        }
        int remaining = buckets.stream().mapToInt(Bucket::stock).sum();
        bucketMapper.deleteBySku(skuId);
        inventoryMapper.unmarkBucketed(skuId, remaining);
        log.info("SKU {} 合并分桶，余量 {}", skuId, remaining);
    }

    /** 把余量最多的桶匀一半给已空的桶，避免「总数还有、每个桶都不够」。 */
    @Transactional(rollbackFor = Exception.class)
    public void rebalance(long skuId) {
        List<Bucket> buckets = bucketMapper.selectBySku(skuId);
        Bucket richest =
                buckets.stream().max(Comparator.comparingInt(Bucket::stock)).orElse(null);
        if (richest == null || richest.stock() < 2) {
            return;
        }
        for (Bucket empty : buckets.stream().filter(b -> b.stock() == 0).toList()) {
            int n = richest.stock() / 2;
            if (n == 0 || bucketMapper.take(skuId, richest.bucketNo(), n) == 0) {
                return;
            }
            bucketMapper.give(skuId, empty.bucketNo(), n);
            richest = new Bucket(skuId, richest.bucketNo(), richest.stock() - n, richest.reserved());
        }
    }
}
