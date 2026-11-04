package io.github.clarence404.mall.inventory.api;

/**
 * 库存模块对外接口。订单模块只能通过它扣减库存，不直接写 t_inventory。
 */
public interface InventoryApi {

    /** 条件更新扣减，返回 false 表示库存不足。 */
    boolean deduct(long skuId, int quantity);

    /** 取消订单时归还。 */
    void restore(long skuId, int quantity);
}
