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

    /**
     * 仅演示环境的补货（demo profile 的 {@code PUT /internal/demo/stocks/{skuId}/restock}，给长期运行的流量模拟器用）：可售数量低于
     * target 时补到 target，只补不减；分桶的活动 SKU 不处理（库存在桶里）。一条条件更新，和并发的预占互不覆盖。
     */
    @Update("""
            UPDATE t_inventory SET stock = #{target}
            WHERE sku_id = #{skuId} AND bucket_count = 0 AND stock < #{target}
            """)
    int restockTo(@Param("skuId") long skuId, @Param("target") int target);

    /** 0 表示不分桶。 */
    @Select("SELECT bucket_count FROM t_inventory WHERE sku_id = #{skuId}")
    Integer selectBucketCount(@Param("skuId") long skuId);

    @Select("SELECT stock, bucket_count FROM t_inventory WHERE sku_id = #{skuId} FOR UPDATE")
    StockRow selectForUpdate(@Param("skuId") long skuId);

    /** 拆桶：库存全部搬进桶里。 */
    @Update("UPDATE t_inventory SET stock = 0, bucket_count = #{bucketCount} WHERE sku_id = #{skuId}")
    int markBucketed(@Param("skuId") long skuId, @Param("bucketCount") int bucketCount);

    /** 合并：各桶余量并回。 */
    @Update("UPDATE t_inventory SET stock = stock + #{remaining}, bucket_count = 0 WHERE sku_id = #{skuId}")
    int unmarkBucketed(@Param("skuId") long skuId, @Param("remaining") int remaining);

    record StockRow(int stock, int bucketCount) {}

    /** 分桶 SKU 的展示库存是各桶 stock 之和。 */
    @Select("""
            <script>
            SELECT i.sku_id AS sku_id,
                   CASE WHEN i.bucket_count &gt; 0
                        THEN (SELECT COALESCE(SUM(b.stock), 0) FROM t_inventory_bucket b WHERE b.sku_id = i.sku_id)
                        ELSE i.stock END AS stock
            FROM t_inventory i
            WHERE i.sku_id IN <foreach collection="skuIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<StockView> selectStocks(@Param("skuIds") List<Long> skuIds);
}
