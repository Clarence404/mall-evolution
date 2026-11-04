-- 跨服务一致性：记录是哪张支付单把订单付成功的。同一订单付了两次款时，另一张支付单的 payment-succeeded 据此走退款
ALTER TABLE t_order ADD COLUMN payment_no VARCHAR(32) NULL;
