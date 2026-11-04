package io.github.clarence404.mall.payment.pay;

import io.github.clarence404.mall.payment.api.ClosePaymentResult;
import io.github.clarence404.mall.payment.api.PaymentApi;
import io.github.clarence404.mall.payment.api.PaymentStatusView;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;

/** 集群内接口（/internal/payments/{orderNo}/close），路径与参数注解从 PaymentApi 继承。 */
@RestController
@RequiredArgsConstructor
class PaymentInternalController implements PaymentApi {

    private final PaymentCloseService paymentCloseService;
    private final PaymentMapper paymentMapper;

    @Override
    public ClosePaymentResult close(String orderNo) {
        return paymentCloseService.close(orderNo);
    }

    @Override
    public PaymentStatusView query(String orderNo) {
        Payment p = paymentMapper.selectLatestByOrderNo(orderNo);
        if (p == null) {
            return new PaymentStatusView(orderNo, "NONE", null);
        }
        String status = switch (p.getStatus()) {
            case Payment.SUCCESS -> "PAID";
            case Payment.REFUND_PENDING -> "REFUNDING";
            case 4 -> "REFUNDED";
            default -> "PENDING";
        };
        return new PaymentStatusView(orderNo, status, p.getPaidAt());
    }
}
