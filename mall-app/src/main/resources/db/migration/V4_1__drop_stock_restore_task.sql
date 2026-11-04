-- 库存预占上线：关单改为写 order-cancelled 消息、由库存服务消费后 release，拆分过渡期的归还重试表下线
DROP TABLE IF EXISTS t_stock_restore_task;
