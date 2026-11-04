package io.github.clarence404.mall.order.shipment;

import io.github.clarence404.mall.common.mq.ConsumeRecordMapper;
import io.github.clarence404.mall.order.paid.OrderPaidEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * shipment-consumer：依赖 WMS 按外部单号幂等；成功后插去重记录，下次重投在本地就被挡住。
 *
 * <p>瞬时失败（如 WMS 偶发超时）先在方法内短间隔重试 2 次，仍失败再交给 Broker 按退避重投，避免一次抖动就等上 10 秒。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShipmentCreator {

    static final String GROUP = "shipment-consumer";
    private static final int LOCAL_RETRIES = 2;

    private final ConsumeRecordMapper consumeRecordMapper;
    private final WmsClient wmsClient;

    public void onPaid(OrderPaidEvent e) {
        if (consumeRecordMapper.exists(GROUP, e.orderNo())) {
            return;
        }
        for (int attempt = 0; ; attempt++) {
            try {
                wmsClient.createShipment(e.orderNo());
                break;
            } catch (RuntimeException ex) {
                if (attempt >= LOCAL_RETRIES) {
                    throw ex;
                }
                log.warn("调用 WMS 失败，{}ms 后重试，orderNo={}", 200L * (attempt + 1), e.orderNo());
                sleep(200L * (attempt + 1));
            }
        }
        consumeRecordMapper.insertIgnore(GROUP, e.orderNo());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ie);
        }
    }
}
