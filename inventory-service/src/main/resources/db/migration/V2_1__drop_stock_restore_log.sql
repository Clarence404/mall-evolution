-- 库存预占上线：归还改为按 (order_no, sku_id) 幂等的 release，拆分过渡期的归还去重表下线
DROP TABLE IF EXISTS t_stock_restore_log;
