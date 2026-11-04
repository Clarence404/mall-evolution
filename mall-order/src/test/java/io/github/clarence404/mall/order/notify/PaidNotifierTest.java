package io.github.clarence404.mall.order.notify;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.clarence404.mall.common.mq.ConsumeRecordMapper;
import io.github.clarence404.mall.order.api.OrderPaidEvent;
import io.github.clarence404.mall.user.api.UserApi;
import io.github.clarence404.mall.user.api.UserDTO;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class PaidNotifierTest {

    ConsumeRecordMapper records = mock(ConsumeRecordMapper.class);
    UserApi userApi = mock(UserApi.class);
    SmsClient sms = mock(SmsClient.class);
    PushClient push = mock(PushClient.class);
    PaidNotifier notifier = new PaidNotifier(records, userApi, sms, push);

    @Test
    void sendsFirstThenRecords() {
        when(userApi.findById(10001L)).thenReturn(Optional.of(new UserDTO(10001L, "13800000000", "张三")));

        notifier.onPaid(event(LocalDateTime.now()));

        InOrder inOrder = inOrder(sms, push, records);
        inOrder.verify(sms).send(anyString(), anyString());
        inOrder.verify(push).push(anyLong(), anyString(), anyString());
        inOrder.verify(records).insertIgnore("notify-consumer", "1001");
    }

    @Test
    void duplicateDeliveryIsSkipped() {
        when(records.exists("notify-consumer", "1001")).thenReturn(true);

        notifier.onPaid(event(LocalDateTime.now()));

        verify(sms, never()).send(anyString(), anyString());
    }

    @Test
    void stalePaymentOlderThan30MinutesIsAcknowledgedWithoutSms() {
        notifier.onPaid(event(LocalDateTime.now().minusMinutes(31)));

        verify(sms, never()).send(anyString(), anyString());
        verify(records).insertIgnore("notify-consumer", "1001");
    }

    private static OrderPaidEvent event(LocalDateTime paidAt) {
        return new OrderPaidEvent("1001", 10001L, new BigDecimal("79.80"), paidAt);
    }
}
