package io.github.clarence404.mall.payment.pay;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PaymentMapper {

    @Insert("""
            INSERT INTO t_payment (id, payment_no, order_id, order_no, user_id, amount, channel, status)
            VALUES (#{id}, #{paymentNo}, 0, #{orderNo}, #{userId}, #{amount}, #{channel}, 0)
            """)
    int insert(Payment payment);

    @Select("""
            SELECT id, payment_no, order_no, user_id, amount, channel, channel_trade_no, status, paid_at, created_at
            FROM t_payment WHERE payment_no = #{paymentNo}
            """)
    Payment selectByPaymentNo(@Param("paymentNo") String paymentNo);

    /** 影响 0 行：重复回调，支付单已是成功状态。 */
    @Update("""
            UPDATE t_payment SET status = 1, channel_trade_no = #{channelTradeNo}, paid_at = NOW(3)
            WHERE payment_no = #{paymentNo} AND status = 0
            """)
    int markSuccess(@Param("paymentNo") String paymentNo, @Param("channelTradeNo") String channelTradeNo);

    /** 订单已关闭后才收到支付：支付单转待退款，走原路退款。 */
    @Update("UPDATE t_payment SET status = 3 WHERE payment_no = #{paymentNo} AND status = 1")
    int markRefundPending(@Param("paymentNo") String paymentNo);
}
