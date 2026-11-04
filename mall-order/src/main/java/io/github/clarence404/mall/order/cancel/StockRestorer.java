package io.github.clarence404.mall.order.cancel;

import io.github.clarence404.mall.common.json.Json;
import io.github.clarence404.mall.inventory.api.InventoryApi;
import io.github.clarence404.mall.inventory.api.RestoreStockRequest;
import io.github.clarence404.mall.inventory.api.StockLine;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 关单后的远程归还库存（拆分过渡期）：任务和订单状态在同一个本地事务里写进 t_stock_restore_task，
 * 提交后立即调一次 restore，成功删任务；失败（超时、5xx、库存服务不可用）留在表里，每 30 秒按退避重试。
 *
 * <p>库存服务按 order_no 去重，重试或两台实例同时重试都不会多加库存。下一篇改为库存预占，关单写 order-cancelled
 * 消息、由库存服务消费后 release，这张表随之下线。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockRestorer {

    private final StockRestoreTaskMapper taskMapper;
    private final InventoryApi inventoryApi;

    /** 在关单的本地事务里调用。 */
    void enqueue(String orderNo, List<StockLine> lines) {
        taskMapper.insert(orderNo, Json.write(lines), LocalDateTime.now().plusSeconds(10));
    }

    /** 事务提交后调用；返回 true 表示已归还并删除任务。 */
    boolean tryRestore(String orderNo, List<StockLine> lines) {
        return tryRestore(orderNo, lines, 0);
    }

    private boolean tryRestore(String orderNo, List<StockLine> lines, int retryCount) {
        try {
            inventoryApi.restore(new RestoreStockRequest(orderNo, lines));
        } catch (RuntimeException e) {
            log.warn("关单后归还库存失败，稍后重试，orderNo={}, retry={}", orderNo, retryCount, e);
            taskMapper.markRetry(orderNo, LocalDateTime.now().plus(backoff(retryCount)));
            return false;
        }
        taskMapper.delete(orderNo);
        return true;
    }

    /** 指数退避：2、4、8 秒……最长约 17 分钟。一直失败就一直留在表里，看这张表的行数告警。 */
    static Duration backoff(int retryCount) {
        return Duration.ofSeconds(1L << Math.min(retryCount + 1, 10));
    }

    @Scheduled(fixedDelayString = "${mall.order.stock-restore-retry-interval:30s}")
    public int retryDue() {
        int restored = 0;
        for (StockRestoreTaskMapper.Task t : taskMapper.selectDue(100)) {
            List<StockLine> lines = Arrays.asList(Json.read(t.lines(), StockLine[].class));
            if (tryRestore(t.orderNo(), lines, t.retryCount())) {
                restored++;
            }
        }
        return restored;
    }
}
