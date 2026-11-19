package io.github.clarence404.mall.order.demo;

import io.github.clarence404.mall.order.domain.Order;
import io.github.clarence404.mall.order.domain.OrderItem;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 高性能实验「数据访问」用到的 SQL：正式代码里故意不写的那几种写法（偏移量分页、逐单查明细、逐条插入），和它们的对照组。
 * 只被 demo profile 下的 {@link DemoPerfService} 调用，正式环境里这些语句不会被执行。见 experiments/j-data-access。
 */
@Mapper
interface DemoPerfMapper {

    /** 灌数据：一条语句多行；重复执行时已有的行跳过（order_no 唯一）。超时放到 60 秒（全局是 5 秒，开发库偶尔会卡几秒）。 */
    @Options(timeout = 60)
    @Insert("""
            <script>
            INSERT IGNORE INTO t_order (id, order_no, user_id, biz_no, status, total_amount, freight_amount,
                discount_amount, pay_amount, receiver_name, receiver_phone, receiver_addr, remark, created_at)
            VALUES
            <foreach collection="rows" item="o" separator=",">
                (#{o.id}, #{o.orderNo}, #{o.userId}, #{o.bizNo}, 50, #{o.payAmount}, 0, 0, #{o.payAmount},
                 '实验', '13800000000', '高性能实验的种子订单', '', #{o.createdAt})
            </foreach>
            </script>
            """)
    int insertOrders(@Param("rows") List<SeedOrder> rows);

    /** 批量写的写法一、二：一条语句一行。 */
    @Insert("""
            INSERT INTO t_order_item (id, order_id, sku_id, product_name, product_image, unit_price, quantity,
                item_amount, created_at)
            VALUES (#{id}, #{orderId}, #{skuId}, #{productName}, '', #{unitPrice}, #{quantity}, #{itemAmount},
                #{createdAt})
            """)
    int insertItem(SeedItem item);

    /** 批量写的写法三：一条语句多行（和正式代码 OrderItemMapper.batchInsert 是同一种写法）。 */
    @Options(timeout = 60)
    @Insert("""
            <script>
            INSERT INTO t_order_item (id, order_id, sku_id, product_name, product_image, unit_price, quantity,
                item_amount, created_at)
            VALUES
            <foreach collection="rows" item="i" separator=",">
                (#{i.id}, #{i.orderId}, #{i.skuId}, #{i.productName}, '', #{i.unitPrice}, #{i.quantity},
                 #{i.itemAmount}, #{i.createdAt})
            </foreach>
            </script>
            """)
    int insertItems(@Param("rows") List<SeedItem> rows);

    /** 批量写实验写进去的明细挂在一个不存在的订单 ID 上，量完按这个 ID 删掉。 */
    @Delete("DELETE FROM t_order_item WHERE order_id = #{orderId} LIMIT #{limit}")
    int deleteItemsOfOrder(@Param("orderId") long orderId, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM t_order WHERE user_id = #{userId}")
    long countOrders(@Param("userId") long userId);

    /**
     * 偏移量分页：沿 idx_user_id 读出 offset + limit 行（每一行都要回表），再丢掉前 offset 行。
     * 三种分页都用提示固定在 idx_user_id 上，比的是翻页方式，不让优化器换索引把结果搅乱（不加提示时它会选 uk_user_biz 再排序，更慢）。
     */
    @Select("""
            SELECT /*+ INDEX(t_order idx_user_id) */ id, order_no, status, pay_amount, created_at
            FROM t_order
            WHERE user_id = #{userId}
            ORDER BY id DESC
            LIMIT #{offset}, #{limit}
            """)
    List<Order> pageByOffset(@Param("userId") long userId, @Param("offset") int offset, @Param("limit") int limit);

    /** 延迟关联：子查询只在索引 idx_user_id (user_id, id) 上数过 offset 行、拿到这一页的主键，再回表取这 limit 行。 */
    @Select("""
            SELECT o.id, o.order_no, o.status, o.pay_amount, o.created_at
            FROM t_order o
            JOIN (SELECT /*+ INDEX(t_order idx_user_id) */ id FROM t_order
                  WHERE user_id = #{userId} ORDER BY id DESC LIMIT #{offset}, #{limit}) p
              ON p.id = o.id
            ORDER BY o.id DESC
            """)
    List<Order> pageByDeferredJoin(
            @Param("userId") long userId, @Param("offset") int offset, @Param("limit") int limit);

    /** 第 offset 行的主键：实验给游标分页算「翻到同样深度时的游标」用，不算在被测的查询里。 */
    @Select("SELECT /*+ INDEX(t_order idx_user_id) */ id FROM t_order"
            + " WHERE user_id = #{userId} ORDER BY id DESC LIMIT #{offset}, 1")
    Long idAtOffset(@Param("userId") long userId, @Param("offset") int offset);

    /** 一次查出一页订单的全部明细。 */
    @Select("""
            <script>
            SELECT id, order_id, sku_id, product_name, product_image, unit_price, quantity, item_amount, created_at
            FROM t_order_item
            WHERE order_id IN
            <foreach collection="orderIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY order_id, id
            </script>
            """)
    List<OrderItem> selectItemsByOrderIds(@Param("orderIds") List<Long> orderIds);

    /** 一条语句把一页订单和它们的明细一起查出来：一行是「订单 + 一条明细」，没有明细的订单明细列为空。 */
    @Select("""
            SELECT o.id AS order_id, o.order_no, o.status, o.pay_amount, o.created_at,
                   i.sku_id, i.product_name, i.unit_price, i.quantity, i.item_amount
            FROM (SELECT /*+ INDEX(t_order idx_user_id) */ id, order_no, status, pay_amount, created_at
                  FROM t_order WHERE user_id = #{userId} ORDER BY id DESC LIMIT #{limit}) o
            LEFT JOIN t_order_item i ON i.order_id = o.id
            ORDER BY o.id DESC, i.id
            """)
    List<JoinedRow> listWithItemsJoined(@Param("userId") long userId, @Param("limit") int limit);

    /** 灌数据用的一行订单。 */
    record SeedOrder(
            long id, String orderNo, long userId, String bizNo, BigDecimal payAmount, LocalDateTime createdAt) {}

    /** 灌数据、批量写实验用的一行明细。 */
    record SeedItem(
            long id,
            long orderId,
            long skuId,
            String productName,
            BigDecimal unitPrice,
            int quantity,
            BigDecimal itemAmount,
            LocalDateTime createdAt) {}

    /** {@link #listWithItemsJoined} 的一行。 */
    record JoinedRow(
            long orderId,
            String orderNo,
            int status,
            BigDecimal payAmount,
            LocalDateTime createdAt,
            Long skuId,
            String productName,
            BigDecimal unitPrice,
            Integer quantity,
            BigDecimal itemAmount) {}
}
