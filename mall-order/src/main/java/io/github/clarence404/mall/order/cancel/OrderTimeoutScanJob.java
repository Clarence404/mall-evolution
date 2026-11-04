package io.github.clarence404.mall.order.cancel;

import io.github.clarence404.mall.order.domain.OrderMapper;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 阶段一的超时关单：每分钟扫一次 idx_status_created，关掉下单超过 15 分钟仍未支付的订单。
 *
 * <p>两台应用机同时扫也没关系：关单是带旧状态的条件更新，只有一台能成功。
 */
@Slf4j
@Component
@RequiredArgsConstructor
class OrderTimeoutScanJob {

    private final OrderMapper orderMapper;
    private final OrderCloseService orderCloseService;

    @Value("${mall.order.pay-timeout:15m}")
    private java.time.Duration payTimeout;

    @Scheduled(fixedDelayString = "${mall.order.timeout-scan-interval:60s}", initialDelayString = "30s")
    void closeTimeoutOrders() {
        List<String> orderNos =
                orderMapper.selectUnpaidOrderNosBefore(LocalDateTime.now().minus(payTimeout), 500);
        int closed = 0;
        for (String orderNo : orderNos) {
            try {
                closed += orderCloseService.closeIfUnpaid(orderNo) ? 1 : 0;
            } catch (RuntimeException e) {
                log.warn("超时关单失败，下一轮重试，orderNo={}", orderNo, e);
            }
        }
        if (closed > 0) {
            log.info("超时关单 {} 笔", closed);
        }
    }
}
