package io.github.clarence404.mall.inventory.stock;

import io.github.clarence404.mall.inventory.api.StockView;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * stock 仍是可售数量：预占时 stock -n, reserved +n；确认时 reserved -n；释放时 stock +n, reserved -n。商品页展示 stock。
 */
@Mapper
public interface InventoryMapper {

    /** stock >= n 条件更新，影响 0 行表示库存不足。 */
    @Update("""
            UPDATE t_inventory SET stock = stock - #{quantity}, reserved = reserved + #{quantity}
            WHERE sku_id = #{skuId} AND stock >= #{quantity}
            """)
    int reserve(@Param("skuId") long skuId, @Param("quantity") int quantity);

    @Update("""
            UPDATE t_inventory SET stock = stock + #{quantity}, reserved = reserved - #{quantity}
            WHERE sku_id = #{skuId}
            """)
    int unreserve(@Param("skuId") long skuId, @Param("quantity") int quantity);

    @Update("UPDATE t_inventory SET reserved = reserved - #{quantity} WHERE sku_id = #{skuId}")
    int confirm(@Param("skuId") long skuId, @Param("quantity") int quantity);

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
