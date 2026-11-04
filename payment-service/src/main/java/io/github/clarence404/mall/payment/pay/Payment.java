package io.github.clarence404.mall.payment.pay;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Payment {

    public static final int PENDING = 0;
    public static final int SUCCESS = 1;
    public static final int CLOSED = 2;
    public static final int REFUND_PENDING = 3;

    private long id;
    private String paymentNo;
    private String orderNo;
    private long userId;
    private BigDecimal amount;
    private String channel;
    private String channelTradeNo;
    private int status;
    private LocalDateTime paidAt;
    private LocalDateTime createdAt;
}
