-- mall_payment 库：从 mall 拆出的支付单表，结构与拆分前一致，存量与增量由 binlog 同步迁入
CREATE TABLE t_payment (
    id               BIGINT        NOT NULL COMMENT '雪花 ID',
    payment_no       VARCHAR(32)   NOT NULL COMMENT '对外支付单号，提交给支付渠道',
    order_id         BIGINT        NOT NULL DEFAULT 0 COMMENT '拆分前的订单主键；拆分后新建的支付单只认 order_no，此列为 0',
    order_no         VARCHAR(32)   NOT NULL,
    user_id          BIGINT        NOT NULL,
    amount           DECIMAL(12,2) NOT NULL,
    channel          VARCHAR(16)   NOT NULL COMMENT '支付渠道',
    channel_trade_no VARCHAR(64)   NULL     COMMENT '渠道流水号',
    status           TINYINT       NOT NULL DEFAULT 0 COMMENT '0 待支付 1 成功 2 已关闭 3 待退款 4 已退款',
    paid_at          DATETIME(3)   NULL,
    created_at       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_no (payment_no),
    KEY idx_payment_order_no (order_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '支付单';
