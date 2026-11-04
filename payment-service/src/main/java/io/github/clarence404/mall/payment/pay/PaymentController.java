package io.github.clarence404.mall.payment.pay;

import io.github.clarence404.mall.common.error.BizException;
import io.github.clarence404.mall.common.security.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
class PaymentController {

    private final PaymentService paymentService;
    private final PaymentMapper paymentMapper;
    private final PayCallbackService payCallbackService;
    private final MockChannelSigner signer;

    @Operation(operationId = "createPayment")
    @PostMapping("/api/payments")
    @ResponseStatus(HttpStatus.CREATED)
    PaymentView create(@AuthenticationPrincipal LoginUser user, @RequestBody @Valid CreatePaymentRequest req) {
        Payment p = paymentService.create(user.id(), req.orderNo());
        return new PaymentView(p.getPaymentNo(), p.getOrderNo(), amount(p), p.getChannel());
    }

    /** 渠道回调：免 JWT，改验渠道签名；按支付单号幂等，渠道重发直接返回成功。 */
    @Operation(operationId = "mockChannelNotify")
    @PostMapping("/api/payments/notify/{channel}")
    String notify(@PathVariable String channel, @RequestBody @Valid ChannelNotify req) {
        if (!PaymentService.MOCK_CHANNEL.equals(channel)
                || !signer.verify(req.paymentNo(), req.channelTradeNo(), req.amount(), req.sign())) {
            throw new BizException(PayErrorCode.PAY_SIGNATURE_INVALID);
        }
        Payment p = paymentMapper.selectByPaymentNo(req.paymentNo());
        if (p == null) {
            throw new BizException(PayErrorCode.PAY_PAYMENT_NOT_FOUND);
        }
        if (!amount(p).equals(req.amount())) {
            throw new BizException(PayErrorCode.PAY_AMOUNT_MISMATCH, "支付单 " + p.getPaymentNo() + " 金额不一致");
        }
        payCallbackService.onPaid(new PayNotify(
                p.getPaymentNo(),
                req.channelTradeNo(),
                p.getOrderNo(),
                p.getUserId(),
                p.getAmount(),
                LocalDateTime.now()));
        return "success";
    }

    private static String amount(Payment p) {
        return p.getAmount().setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    record CreatePaymentRequest(@NotBlank String orderNo) {}

    record PaymentView(String paymentNo, String orderNo, String amount, String channel) {}

    record ChannelNotify(
            @NotBlank String paymentNo,
            @NotBlank String channelTradeNo,
            @NotBlank String amount,
            @NotBlank String sign) {}
}
