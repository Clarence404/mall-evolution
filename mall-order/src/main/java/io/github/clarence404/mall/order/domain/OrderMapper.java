package io.github.clarence404.mall.order.domain;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 只读写 mall-order 自己的表，不 JOIN 其他模块的表。 */
@Mapper
public interface OrderMapper {

    String COLUMNS = "id, order_no, user_id, biz_no, status, total_amount, freight_amount, discount_amount, pay_amount,"
            + " receiver_name, receiver_phone, receiver_addr, remark, paid_at, version, created_at, updated_at";

    /** 撞 uk_user_biz 抛 DuplicateKeyException。 */
    @Insert("""
            INSERT INTO t_order (id, order_no, user_id, biz_no, status, total_amount, freight_amount, discount_amount,
                pay_amount, receiver_name, receiver_phone, receiver_addr, remark, created_at)
            VALUES (#{id}, #{orderNo}, #{userId}, #{bizNo}, #{status}, #{totalAmount}, #{freightAmount},
                #{discountAmount}, #{payAmount}, #{receiverName}, #{receiverPhone}, #{receiverAddr}, #{remark},
                #{createdAt})
            """)
    int insert(Order order);

    @Select("SELECT " + COLUMNS + " FROM t_order WHERE user_id = #{userId} AND biz_no = #{bizNo}")
    Order selectByUserAndBizNo(@Param("userId") long userId, @Param("bizNo") String bizNo);

    @Select("SELECT " + COLUMNS + " FROM t_order WHERE order_no = #{orderNo}")
    Order selectByOrderNo(@Param("orderNo") String orderNo);

    @Select("SELECT " + COLUMNS + " FROM t_order WHERE id = #{id}")
    Order selectById(@Param("id") long id);

    /**
     * 状态流转入口：先按 {@link OrderStatus} 的流转表校验，非法流转属于代码错误，直接抛异常、不碰数据库；
     * 合法的再做带旧状态的条件更新，影响 0 行说明状态已被别人改过。
     */
    default int updateStatus(long id, OrderStatus from, OrderStatus to) {
        from.checkTransitTo(to);
        return casStatus(id, from, to);
    }

    /** 带旧状态的条件更新（只由 updateStatus 调用）。进入待发货时同时记录支付时间。 */
    @Update("""
            UPDATE t_order SET status = #{to},
                paid_at = CASE WHEN #{to} = 20 THEN NOW(3) ELSE paid_at END
            WHERE id = #{id} AND status = #{from}
            """)
    int casStatus(@Param("id") long id, @Param("from") OrderStatus from, @Param("to") OrderStatus to);

    /**
     * 按订单号的状态流转（消息驱动的流转用订单号做业务键）：和 {@link #updateStatus} 一样先校验流转表，再做条件更新。
     */
    default int transit(String orderNo, OrderStatus from, OrderStatus to, LocalDateTime paidAt) {
        from.checkTransitTo(to);
        return casStatusByOrderNo(orderNo, from, to, paidAt);
    }

    /** 按订单号的状态条件更新（只由 transit 调用）。进入待发货时记录支付时间。 */
    @Update("""
            UPDATE t_order SET status = #{to},
                paid_at = CASE WHEN #{to} = 20 THEN COALESCE(#{paidAt}, NOW(3)) ELSE paid_at END
            WHERE order_no = #{orderNo} AND status = #{from}
            """)
    int casStatusByOrderNo(
            @Param("orderNo") String orderNo,
            @Param("from") OrderStatus from,
            @Param("to") OrderStatus to,
            @Param("paidAt") LocalDateTime paidAt);

    /** 记录把订单付成功的那张支付单（与 10→20 的流转同一个事务）。 */
    @Update("UPDATE t_order SET payment_no = #{paymentNo} WHERE order_no = #{orderNo}")
    int updatePaymentNo(@Param("orderNo") String orderNo, @Param("paymentNo") String paymentNo);

    @Select("SELECT payment_no FROM t_order WHERE order_no = #{orderNo}")
    String selectPaymentNo(@Param("orderNo") String orderNo);

    @Select("SELECT status FROM t_order WHERE order_no = #{orderNo}")
    OrderStatus selectStatus(@Param("orderNo") String orderNo);

    /**
     * 我的订单：游标分页，走 idx_user_id (user_id, id)，索引本身按 id 有序；带 status 时在回表后过滤。多取一条判断 hasMore。
     */
    @Select("""
            <script>
            SELECT id, order_no, status, pay_amount, created_at
            FROM t_order
            WHERE user_id = #{userId}
              <if test="cursorId != null">AND id &lt; #{cursorId}</if>
              <if test="status != null">AND status = #{status}</if>
            ORDER BY id DESC
            LIMIT #{limit}
            </script>
            """)
    List<Order> listByUser(
            @Param("userId") long userId,
            @Param("status") OrderStatus status,
            @Param("cursorId") Long cursorId,
            @Param("limit") int limit);

    /** 超时未支付扫描：走 idx_status_created。 */
    @Select("""
            SELECT order_no FROM t_order
            WHERE status = 10 AND created_at < #{before}
            ORDER BY created_at LIMIT #{limit}
            """)
    List<String> selectUnpaidOrderNosBefore(@Param("before") LocalDateTime before, @Param("limit") int limit);

    /** 状态以外的字段修改用 version 乐观锁，防止后写覆盖先写；null 字段保持不变。 */
    @Update("""
            UPDATE t_order SET
                receiver_name = COALESCE(#{name}, receiver_name),
                receiver_phone = COALESCE(#{phone}, receiver_phone),
                receiver_addr = COALESCE(#{addr}, receiver_addr),
                version = version + 1
            WHERE id = #{id} AND version = #{version} AND status IN (10, 20)
            """)
    int updateAddress(
            @Param("id") long id,
            @Param("version") int version,
            @Param("name") String name,
            @Param("phone") String phone,
            @Param("addr") String addr);
}
