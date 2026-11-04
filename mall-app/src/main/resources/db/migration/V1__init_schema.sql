-- 阶段一的五张核心表：每张表只归一个模块，不建外键，引用关系由应用保证

-- mall-order：订单主表
CREATE TABLE t_order (
    id              BIGINT        NOT NULL COMMENT '雪花 ID',
    order_no        VARCHAR(32)   NOT NULL COMMENT '对外订单号',
    user_id         BIGINT        NOT NULL,
    biz_no          VARCHAR(64)   NOT NULL COMMENT '下单幂等键，取自 Idempotency-Key 请求头',
    status          TINYINT       NOT NULL DEFAULT 10 COMMENT '10 待支付 20 待发货 30 已发货 40 已完成 50 已取消 60 已关闭',
    total_amount    DECIMAL(12,2) NOT NULL COMMENT '商品总额',
    freight_amount  DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    discount_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    pay_amount      DECIMAL(12,2) NOT NULL COMMENT '应付 = 总额 + 运费 - 优惠',
    receiver_name   VARCHAR(64)   NOT NULL,
    receiver_phone  VARCHAR(32)   NOT NULL,
    receiver_addr   VARCHAR(255)  NOT NULL,
    remark          VARCHAR(255)  NOT NULL DEFAULT '',
    paid_at         DATETIME(3)   NULL,
    version         INT           NOT NULL DEFAULT 0,
    created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    UNIQUE KEY uk_user_biz (user_id, biz_no),
    KEY idx_user_id (user_id, id),
    KEY idx_status_created (status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '订单';

-- mall-order：订单明细，保存下单时的商品快照
CREATE TABLE t_order_item (
    id            BIGINT        NOT NULL COMMENT '雪花 ID',
    order_id      BIGINT        NOT NULL,
    sku_id        BIGINT        NOT NULL,
    product_name  VARCHAR(128)  NOT NULL COMMENT '快照：下单时商品名',
    product_image VARCHAR(255)  NOT NULL DEFAULT '' COMMENT '快照：下单时主图',
    unit_price    DECIMAL(12,2) NOT NULL COMMENT '快照：下单时单价',
    quantity      INT           NOT NULL,
    item_amount   DECIMAL(12,2) NOT NULL COMMENT 'unit_price × quantity',
    created_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_order_id (order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '订单明细';

-- mall-order（pay 包）：支付单，一笔订单可能有多次支付尝试
CREATE TABLE t_payment (
    id               BIGINT        NOT NULL COMMENT '雪花 ID',
    payment_no       VARCHAR(32)   NOT NULL COMMENT '对外支付单号，提交给支付渠道',
    order_id         BIGINT        NOT NULL,
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
    KEY idx_payment_order_id (order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '支付单';

-- mall-product：商品，一行即一个可售规格，id 即 sku_id
CREATE TABLE t_product (
    id         BIGINT        NOT NULL COMMENT 'sku_id',
    name       VARCHAR(128)  NOT NULL,
    image      VARCHAR(255)  NOT NULL DEFAULT '',
    price      DECIMAL(12,2) NOT NULL COMMENT '售价',
    status     TINYINT       NOT NULL DEFAULT 1 COMMENT '1 上架 0 下架',
    created_at DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品';

-- mall-inventory：库存，按 sku_id 一行，只存可售数量
CREATE TABLE t_inventory (
    sku_id     BIGINT      NOT NULL,
    stock      INT         NOT NULL COMMENT '可售数量',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (sku_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '库存';
