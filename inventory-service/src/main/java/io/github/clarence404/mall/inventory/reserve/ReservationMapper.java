package io.github.clarence404.mall.inventory.reserve;

import io.github.clarence404.mall.common.id.Ids;
import io.github.clarence404.mall.inventory.api.ReserveLine;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ReservationMapper {

    @Select("""
            SELECT id, order_no, sku_id, quantity, status, expire_at, bucket_no
            FROM t_inventory_reservation WHERE order_no = #{orderNo} ORDER BY sku_id
            """)
    List<Reservation> selectByOrderNo(@Param("orderNo") String orderNo);

    /** 并发时撞 uk_order_sku 抛 DuplicateKeyException，整个 reserve 事务回滚。 */
    @Insert("""
            INSERT INTO t_inventory_reservation (id, order_no, sku_id, quantity, status, expire_at, bucket_no)
            VALUES (#{id}, #{orderNo}, #{skuId}, #{quantity}, #{status}, #{expireAt}, #{bucketNo})
            """)
    int insert(Reservation reservation);

    /**
     * 空回滚占位：INSERT IGNORE ... status = 3。返回 1 说明从没预占过，返回 0 说明记录已存在。
     *
     * <p>不用 ON DUPLICATE KEY UPDATE id = id：Connector/J 默认返回 found rows，已存在也返回 1。
     */
    default int insertReleasedIfAbsent(String orderNo, ReserveLine line) {
        return insertIgnoreReleased(Ids.next(), orderNo, line.skuId(), line.quantity());
    }

    @Insert("""
            INSERT IGNORE INTO t_inventory_reservation (id, order_no, sku_id, quantity, status, expire_at)
            VALUES (#{id}, #{orderNo}, #{skuId}, #{quantity}, 3, NOW(3))
            """)
    int insertIgnoreReleased(
            @Param("id") long id,
            @Param("orderNo") String orderNo,
            @Param("skuId") long skuId,
            @Param("quantity") int quantity);

    /** 带旧状态的条件更新：执行两次与执行一次结果相同。 */
    @Update("""
            UPDATE t_inventory_reservation SET status = #{to}
            WHERE order_no = #{orderNo} AND sku_id = #{skuId} AND status = #{from}
            """)
    int transit(
            @Param("orderNo") String orderNo, @Param("skuId") long skuId, @Param("from") int from, @Param("to") int to);

    /** 预占过期回查：按 idx_status_expire 找出已过期仍为 RESERVED 的订单。 */
    @Select("""
            SELECT DISTINCT order_no FROM t_inventory_reservation
            WHERE status = 1 AND expire_at < NOW(3) LIMIT #{limit}
            """)
    List<String> selectExpiredOrderNos(@Param("limit") int limit);
}
