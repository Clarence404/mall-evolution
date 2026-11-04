package io.github.clarence404.mall.order.domain;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OrderItemMapper {

    @Insert("""
            <script>
            INSERT INTO t_order_item (id, order_id, sku_id, product_name, product_image, unit_price, quantity,
                item_amount, created_at)
            VALUES
            <foreach collection="items" item="i" separator=",">
                (#{i.id}, #{i.orderId}, #{i.skuId}, #{i.productName}, #{i.productImage}, #{i.unitPrice},
                 #{i.quantity}, #{i.itemAmount}, #{i.createdAt})
            </foreach>
            </script>
            """)
    int batchInsert(@Param("items") List<OrderItem> items);

    @Select("""
            SELECT id, order_id, sku_id, product_name, product_image, unit_price, quantity, item_amount, created_at
            FROM t_order_item WHERE order_id = #{orderId} ORDER BY id
            """)
    List<OrderItem> selectByOrderId(@Param("orderId") long orderId);
}
