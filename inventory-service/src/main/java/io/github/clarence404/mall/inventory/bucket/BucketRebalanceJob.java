package io.github.clarence404.mall.inventory.bucket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每 5 秒检查一次分桶 SKU；活动 SKU 每单限购 1–2 件，最后几件时的误判影响很小。 */
@Slf4j
@Component
@RequiredArgsConstructor
class BucketRebalanceJob {

    private final InventoryBucketMapper bucketMapper;
    private final InventoryBucketService bucketService;

    @Scheduled(fixedDelayString = "${mall.inventory.bucket-rebalance-interval:5s}")
    void rebalance() {
        for (Long skuId : bucketMapper.selectBucketedSkuIds()) {
            try {
                bucketService.rebalance(skuId);
            } catch (RuntimeException e) {
                log.warn("分桶再平衡失败，skuId={}", skuId, e);
            }
        }
    }
}
