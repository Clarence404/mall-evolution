-- 先扩后缩 · 第 2 次发布：拆库遗留在 mall 里的旧表先改名（第 1 次发布已删掉最后一处只读引用）。
-- 万一有漏网的读，报错暴露出来，改名回去即可；再观察两周后，第 3 次发布 DROP TABLE *_deprecated。
ALTER TABLE t_inventory RENAME TO t_inventory_deprecated;
ALTER TABLE t_payment RENAME TO t_payment_deprecated;
