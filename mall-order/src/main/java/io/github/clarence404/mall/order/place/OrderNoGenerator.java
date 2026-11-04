package io.github.clarence404.mall.order.place;

import io.github.clarence404.mall.common.id.Ids;
import org.springframework.stereotype.Component;

/**
 * 阶段三的订单号：雪花 ID（19 位）+ 用户 ID 低 10 位（4 位十进制，0000–1023），共 23 位。
 *
 * <p>为以后按 user_id 分库分表留路：按订单号查询也能算出分片（最多 1024 个，取 2 的幂）。主键 id 仍是雪花 ID，表结构不动。
 */
@Component
public class OrderNoGenerator {

    static final long USER_GENE_MASK = 1023L;

    public String next(long userId) {
        return Ids.next() + String.format("%04d", userId & USER_GENE_MASK);
    }

    /** 由订单号还原用户基因，供将来路由分片。阶段一、二的存量 19 位订单号不含基因，返回 -1。 */
    public static int userGene(String orderNo) {
        return orderNo.length() == 23 ? Integer.parseInt(orderNo.substring(19)) : -1;
    }
}
