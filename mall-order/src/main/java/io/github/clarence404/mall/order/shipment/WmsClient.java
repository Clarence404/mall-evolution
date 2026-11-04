package io.github.clarence404.mall.order.shipment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 仓储系统（WMS）客户端：以 order_no 作为外部单号建发货单，WMS 按外部单号去重。演示工程只打日志。
 */
public interface WmsClient {

    void createShipment(String externalOrderNo);

    @Slf4j
    @Component
    class LoggingWmsClient implements WmsClient {

        @Override
        public void createShipment(String externalOrderNo) {
            log.info("[wms] 建发货单 externalOrderNo={}", externalOrderNo);
        }
    }
}
