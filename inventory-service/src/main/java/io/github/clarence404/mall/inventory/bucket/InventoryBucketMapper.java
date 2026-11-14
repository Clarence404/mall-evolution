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

    /** 释放：回到预占时的那个桶。影响 0 行说明桶不存在，调用方按数据不一致报错。 */
    @Update("""
            UPDATE t_inventory_bucket SET stock = stock + #{quantity}, reserved = reserved - #{quantity}
            WHERE sku_id = #{skuId} AND bucket_no = #{bucketNo}
            """)
    int unreserve(@Param("skuId") long skuId, @Param("bucketNo") int bucketNo, @Param("quantity") int quantity);

    /** 确认：从预占时那个桶的 reserved 里减掉。影响 0 行说明桶不存在，调用方按数据不一致报错。 */
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

    /**
     * 合并分桶用：把该 SKU 的全部桶行锁住再读。并发的预占要么已经提交（这里读到 reserved != 0），
     * 要么卡在行锁上等合并提交——不会出现「读完余量之后又被扣走一件」。
     */
    @Select("""
            SELECT sku_id, bucket_no, stock, reserved FROM t_inventory_bucket
            WHERE sku_id = #{skuId} ORDER BY bucket_no FOR UPDATE
            """)
    List<Bucket> selectBySkuForUpdate(@Param("skuId") long skuId);

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
