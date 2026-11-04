package io.github.clarence404.mall.user.points;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.clarence404.mall.common.mq.ConsumeRecordMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class PointsGrantServiceTest {

    ConsumeRecordMapper records = mock(ConsumeRecordMapper.class);
    PointsMapper points = mock(PointsMapper.class);
    PointsGrantService service = new PointsGrantService(records, points);

    @Test
    void firstDeliveryGrantsFlooredAmount() {
        when(records.insertIgnore("points-consumer", "1001")).thenReturn(1);

        service.grant(new OrderPaidEvent("1001", 10001L, new BigDecimal("79.80"), LocalDateTime.now()));

        verify(points).add(10001L, 79, "1001");
    }

    @Test
    void duplicateDeliveryGrantsNothing() {
        when(records.insertIgnore("points-consumer", "1001")).thenReturn(0);

        service.grant(new OrderPaidEvent("1001", 10001L, new BigDecimal("79.80"), LocalDateTime.now()));

        verify(points, never()).add(anyLong(), anyInt(), anyString());
    }
}
