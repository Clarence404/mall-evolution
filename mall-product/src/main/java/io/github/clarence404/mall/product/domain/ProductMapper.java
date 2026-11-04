package io.github.clarence404.mall.product.domain;

import io.github.clarence404.mall.product.detail.ProductDetailView;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProductMapper {

    @Select("""
            <script>
            SELECT id, name, image, price, status, created_at, updated_at FROM t_product
            WHERE id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<Product> selectByIds(@Param("ids") Collection<Long> ids);

    /** 商品详情回源：只查 t_product。 */
    @Select("SELECT id, name, image, price, status FROM t_product WHERE id = #{skuId}")
    @ConstructorArgs({ // 价格与 skuId 按字符串取出，与接口约定一致；status 只有 1 上架 / 0 下架
        @Arg(column = "id", javaType = String.class),
        @Arg(column = "name", javaType = String.class),
        @Arg(column = "image", javaType = String.class),
        @Arg(column = "price", javaType = String.class),
        @Arg(column = "status", javaType = boolean.class)
    })
    ProductDetailView selectDetail(@Param("skuId") long skuId);

    /** 运营后台改价、改名，是商品唯一的写入口。 */
    @Update("""
            UPDATE t_product SET
                name = COALESCE(#{name}, name),
                price = COALESCE(#{price}, price),
                status = COALESCE(#{status}, status)
            WHERE id = #{skuId}
            """)
    int update(
            @Param("skuId") long skuId,
            @Param("name") String name,
            @Param("price") BigDecimal price,
            @Param("status") Integer status);

    /** 猜你喜欢：价格最接近的在售商品（演示用的简单规则）。 */
    @Select("""
            SELECT p.id FROM t_product p, (SELECT price FROM t_product WHERE id = #{skuId}) base
            WHERE p.status = 1 AND p.id <> #{skuId}
            ORDER BY ABS(p.price - base.price), p.id LIMIT #{limit}
            """)
    List<Long> selectSimilarSkuIds(@Param("skuId") long skuId, @Param("limit") int limit);

    /** 预热用：在售商品按 id 取前 N 个（真实项目按销量排行取头部商品）。 */
    @Select("SELECT id FROM t_product WHERE status = 1 ORDER BY id LIMIT #{limit}")
    List<Long> selectHotSkuIds(@Param("limit") int limit);
}
