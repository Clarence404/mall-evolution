package io.github.clarence404.mall.product.domain;

import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ProductMapper {

    @Select("""
            <script>
            SELECT id, name, image, price, status, created_at, updated_at FROM t_product
            WHERE id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<Product> selectByIds(@Param("ids") Collection<Long> ids);
}
