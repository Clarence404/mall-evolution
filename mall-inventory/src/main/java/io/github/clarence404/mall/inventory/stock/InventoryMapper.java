package io.github.clarence404.mall.inventory.stock;

import io.github.clarence404.mall.inventory.api.StockView;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface InventoryMapper {

    /** 影响行数为 0 表示库存不足；stock >= ? 由 InnoDB 行锁保证判断与扣减原子。 */
    @Update("UPDATE t_inventory SET stock = stock - #{quantity} WHERE sku_id = #{skuId} AND stock >= #{quantity}")
    int deduct(@Param("skuId") long skuId, @Param("quantity") int quantity);

    /** 取消订单时归还。 */
    @Update("UPDATE t_inventory SET stock = stock + #{quantity} WHERE sku_id = #{skuId}")
    int restore(@Param("skuId") long skuId, @Param("quantity") int quantity);

    @Select("SELECT stock FROM t_inventory WHERE sku_id = #{skuId}")
    Integer selectStock(@Param("skuId") long skuId);

    @Select("""
            <script>
            SELECT sku_id, stock FROM t_inventory
            WHERE sku_id IN <foreach collection="skuIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<StockView> selectStocks(@Param("skuIds") List<Long> skuIds);
}
