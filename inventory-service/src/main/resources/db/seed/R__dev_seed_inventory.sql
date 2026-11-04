-- 仅本地开发加载的演示库存（与 mall-app 的演示商品对应）
INSERT INTO t_inventory (sku_id, stock) VALUES (20001, 1000), (20002, 1000), (20003, 1000), (20004, 0)
ON DUPLICATE KEY UPDATE stock = stock;
