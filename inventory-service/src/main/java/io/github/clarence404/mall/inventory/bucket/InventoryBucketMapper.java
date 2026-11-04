package io.github.clarence404.mall.inventory.bucket;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface InventoryBucketMapper {

    /** 预占：从哈希桶开始依次尝试，影响 1 行即成功。 */
    @Update("""
            UPDATE t_inventory_bucket
            SET stock = stock - #{quantity}, reserved = reserved + #{quantity}
            WHERE sku_id = #{skuId} AND bucket_no = #{bucketNo} AND stock >= #{quantity}
            """)
    int reserve(@Param("skuId") long skuId, @Param("bucketNo") int bucketNo, @Param("quantity") int quantity);

    @Update("""
            UPDATE t_inventory_bucket SET stock = stock + #{quantity}, reserved = reserved - #{quantity}
            WHERE sku_id = #{skuId} AND bucket_no = #{bucketNo}
            """)
    int unreserve(@Param("skuId") long skuId, @Param("bucketNo") int bucketNo, @Param("quantity") int quantity);

    @Update("""
            UPDATE t_inventory_bucket SET reserved = reserved - #{quantity}
            WHERE sku_id = #{skuId} AND bucket_no = #{bucketNo}
            """)
    int confirm(@Param("skuId") long skuId, @Param("bucketNo") int bucketNo, @Param("quantity") int quantity);

    @Insert(
            "INSERT INTO t_inventory_bucket (sku_id, bucket_no, stock, reserved) VALUES (#{skuId}, #{bucketNo}, #{stock}, 0)")
    int insert(@Param("skuId") long skuId, @Param("bucketNo") int bucketNo, @Param("stock") int stock);

    @Select(
            "SELECT sku_id, bucket_no, stock, reserved FROM t_inventory_bucket WHERE sku_id = #{skuId} ORDER BY bucket_no")
    List<Bucket> selectBySku(@Param("skuId") long skuId);

    @Select("SELECT DISTINCT sku_id FROM t_inventory_bucket")
    List<Long> selectBucketedSkuIds();

    /** 再平衡：从余量最多的桶搬出 n 件，条件更新防止并发预占时搬成负数。 */
    @Update("""
            UPDATE t_inventory_bucket SET stock = stock - #{n}
            WHERE sku_id = #{skuId} AND bucket_no = #{bucketNo} AND stock >= #{n}
            """)
    int take(@Param("skuId") long skuId, @Param("bucketNo") int bucketNo, @Param("n") int n);

    @Update("UPDATE t_inventory_bucket SET stock = stock + #{n} WHERE sku_id = #{skuId} AND bucket_no = #{bucketNo}")
    int give(@Param("skuId") long skuId, @Param("bucketNo") int bucketNo, @Param("n") int n);

    @Delete("DELETE FROM t_inventory_bucket WHERE sku_id = #{skuId}")
    int deleteBySku(@Param("skuId") long skuId);

    record Bucket(long skuId, int bucketNo, int stock, int reserved) {}
}
