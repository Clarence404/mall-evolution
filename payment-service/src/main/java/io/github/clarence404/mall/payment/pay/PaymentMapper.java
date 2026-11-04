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

    /**
     * 渠道确认付款：待支付或已被我方关闭的支付单都置为成功。关闭支付单与用户付款存在竞态，钱已经到账就要如实记账，
     * 订单侧发现订单已取消会发 order-refund-required 走原路退款。影响 0 行：重复回调。
     */
    @Update("""
            UPDATE t_payment SET status = 1, channel_trade_no = #{channelTradeNo}, paid_at = NOW(3)
            WHERE payment_no = #{paymentNo} AND status IN (0, 2)
            """)
    int markSuccess(@Param("paymentNo") String paymentNo, @Param("channelTradeNo") String channelTradeNo);

    /** 该订单是否已有付款成功（含待退款、已退款）的支付单。 */
    @Select("SELECT COUNT(*) FROM t_payment WHERE order_no = #{orderNo} AND status IN (1, 3, 4)")
    int countPaid(@Param("orderNo") String orderNo);

    /** 取消订单前关闭所有待支付的支付单（演示用模拟渠道，真实渠道要先调渠道的关单接口）。 */
    @Update("UPDATE t_payment SET status = 2 WHERE order_no = #{orderNo} AND status = 0")
    int closePending(@Param("orderNo") String orderNo);

    /** 关单后才到的支付、同一订单的重复付款：这一张支付单转待退款，走原路退款。 */
    @Update("UPDATE t_payment SET status = 3 WHERE payment_no = #{paymentNo} AND status = 1")
    int markRefundPending(@Param("paymentNo") String paymentNo);
}
