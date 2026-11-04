package io.github.clarence404.mall.order.domain;

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
}
