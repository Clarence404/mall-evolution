package io.github.clarence404.mall.inventory.reserve;

import io.github.clarence404.mall.common.remote.RemoteBizException;
import io.github.clarence404.mall.order.api.OrderQueryApi;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 预占过期回查（兜底）：找出已过期仍为 RESERVED 的记录，回查订单状态——订单不存在或已取消就 release，
 * 已支付及以后就 confirm，仍待支付则等下一轮。这是库存服务唯一一处反向调用订单服务，只在兜底任务里出现。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationExpireJob {

    static final int PENDING_PAYMENT = 10;
    static final int CANCELLED = 50;
    static final int NOT_FOUND = -1;

    private final ReservationMapper reservationMapper;
    private final ReservationService reservationService;
    private final OrderQueryApi orderQueryApi;

    @Scheduled(fixedDelayString = "${mall.inventory.reservation-expire-interval:5m}", initialDelayString = "60s")
    public void reconcileExpired() {
        List<String> orderNos = reservationMapper.selectExpiredOrderNos(200);
        for (String orderNo : orderNos) {
            try {
                handle(orderNo, orderStatus(orderNo));
            } catch (RuntimeException e) {
                log.warn("预占回查失败，下一轮再试，orderNo={}", orderNo, e);
            }
        }
    }

    void handle(String orderNo, int status) {
        if (status == NOT_FOUND || status == CANCELLED) {
            reservationService.releaseByOrderNo(orderNo);
            log.info("回查后释放过期预占，orderNo={}, orderStatus={}", orderNo, status);
        } else if (status != PENDING_PAYMENT) {
            reservationService.confirm(orderNo);
            log.info("回查后确认过期预占，orderNo={}, orderStatus={}", orderNo, status);
        }
    }

    private int orderStatus(String orderNo) {
        try {
            return orderQueryApi.getByOrderNo(orderNo).status();
        } catch (RemoteBizException e) {
            if (e.getStatus().value() == 404) {
                return NOT_FOUND; // 写订单失败、释放也失败留下的预占
            }
            throw e;
        }
    }
}
