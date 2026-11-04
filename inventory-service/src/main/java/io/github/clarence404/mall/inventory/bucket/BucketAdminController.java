package io.github.clarence404.mall.inventory.bucket;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 库存组在大促前后执行的运维接口（集群内）。 */
@RestController
@RequiredArgsConstructor
class BucketAdminController {

    private final InventoryBucketService bucketService;

    @PostMapping("/internal/inventory/buckets/{skuId}/split")
    ResponseEntity<Void> split(
            @PathVariable long skuId,
            @RequestParam(defaultValue = "" + InventoryBucketService.DEFAULT_BUCKETS) int count) {
        bucketService.split(skuId, count);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/internal/inventory/buckets/{skuId}/merge")
    ResponseEntity<Void> merge(@PathVariable long skuId) {
        bucketService.merge(skuId);
        return ResponseEntity.noContent().build();
    }
}
