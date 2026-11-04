package io.github.clarence404.mall.order.shipment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.clarence404.mall.common.mq.ConsumeRecordMapper;
import io.github.clarence404.mall.order.paid.OrderPaidEvent;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class ShipmentCreatorTest {

    ConsumeRecordMapper records = mock(ConsumeRecordMapper.class);
    WmsClient wms = mock(WmsClient.class);
    ShipmentCreator creator = new ShipmentCreator(records, wms);
    OrderPaidEvent event = new OrderPaidEvent("1001", 10001L, new BigDecimal("79.80"), LocalDateTime.now());

    @Test
    void transientFailureIsRetriedLocallyThenRecorded() {
        doThrow(new IllegalStateException("timeout")).doNothing().when(wms).createShipment("1001");

        creator.onPaid(event);

        verify(wms, times(2)).createShipment("1001");
        verify(records).insertIgnore("shipment-consumer", "1001");
    }

    @Test
    void persistentFailureIsHandedBackToBroker() {
        doThrow(new IllegalStateException("down")).when(wms).createShipment("1001");

        assertThatThrownBy(() -> creator.onPaid(event)).isInstanceOf(IllegalStateException.class);

        verify(wms, times(3)).createShipment("1001"); // 首次 + 本地重试 2 次
        verify(records, never()).insertIgnore(anyString(), anyString());
    }

    @Test
    void usesOrderNoAsExternalNoSoWmsCanDeduplicate() {
        doNothing().when(wms).createShipment("1001");
        creator.onPaid(event);
        verify(wms).createShipment("1001");
    }
}
