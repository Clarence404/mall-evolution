package io.github.clarence404.mall.order.cancel;

import io.github.clarence404.mall.order.domain.OrderMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 超时关单的兜底扫描：主路径已改为 15 分钟定时消息，这里降级为每 10 分钟一次，
 * 只处理创建超过 20 分钟仍待支付的订单，防止 MQ 或中继故障时订单永远不关。
 */
@Slf4j
@Component
@RequiredArgsConstructor
class OrderTimeoutScanJob {

    private final OrderMapper orderMapper;
    private final OrderCloseService orderCloseService;

    @Value("${mall.order.timeout-scan-after:20m}")
    private Duration scanAfter;

    @Scheduled(fixedDelayString = "${mall.order.timeout-scan-interval:10m}", initialDelayString = "60s")
    void closeTimeoutOrders() {
        List<String> orderNos =
                orderMapper.selectUnpaidOrderNosBefore(LocalDateTime.now().minus(scanAfter), 500);
        int closed = 0;
        for (String orderNo : orderNos) {
            try {
                closed += orderCloseService.closeIfUnpaid(orderNo) ? 1 : 0;
            } catch (RuntimeException e) {
                log.warn("兜底关单失败，下一轮重试，orderNo={}", orderNo, e);
            }
        }
        if (closed > 0) {
            log.warn("兜底扫描关掉 {} 笔超时订单，检查定时消息链路（中继、Broker、order-timeout-consumer）", closed);
        }
    }
}
