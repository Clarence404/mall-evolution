package io.github.clarence404.mall.payment.pay;

import io.github.clarence404.mall.payment.api.ClosePaymentResult;
import io.github.clarence404.mall.payment.api.PaymentApi;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;

/** 集群内接口（/internal/payments/{orderNo}/close），路径与参数注解从 PaymentApi 继承。 */
@RestController
@RequiredArgsConstructor
class PaymentInternalController implements PaymentApi {

    private final PaymentCloseService paymentCloseService;

    @Override
    public ClosePaymentResult close(String orderNo) {
        return paymentCloseService.close(orderNo);
    }
}
