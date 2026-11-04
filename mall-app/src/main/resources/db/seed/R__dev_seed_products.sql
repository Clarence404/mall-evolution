-- 仅本地开发（dev profile）加载的演示数据：商品
INSERT INTO t_product (id, name, image, price, status) VALUES
    (20001, '青柚 · 红心柚 2 只装', 'https://img.mall.example/20001.jpg', 39.90, 1),
    (20002, '青柚 · 有机菠菜 500g', 'https://img.mall.example/20002.jpg', 12.80, 1),
    (20003, '青柚 · 洗衣液 3kg', 'https://img.mall.example/20003.jpg', 59.00, 1),
    (20004, '青柚 · 已下架演示商品', '', 9.90, 0)
ON DUPLICATE KEY UPDATE name = VALUES(name), price = VALUES(price), status = VALUES(status);

-- 阶段三起库存在 inventory-service 的 mall_inventory 库，演示库存见那边的 seed
