-- 跨服务一致性：库存预占（TCC 风格的 reserve / confirm / release），以及与其他服务同一套的本地消息表与消费去重表

-- t_inventory 增加一列 reserved：已预占、尚未确认的数量
ALTER TABLE t_inventory ADD COLUMN reserved INT NOT NULL DEFAULT 0 COMMENT '已预占未确认' AFTER stock;

CREATE TABLE t_inventory_reservation (
    id         BIGINT      NOT NULL COMMENT '雪花 ID',
    order_no   VARCHAR(32) NOT NULL,
    sku_id     BIGINT      NOT NULL,
    quantity   INT         NOT NULL,
    status     TINYINT     NOT NULL COMMENT '1 RESERVED 2 CONFIRMED 3 RELEASED',
    expire_at  DATETIME(3) NOT NULL COMMENT '下单后 30 分钟，超过仍未确认则回查订单',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_sku (order_no, sku_id),
    KEY idx_status_expire (status, expire_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '库存预占记录';

CREATE TABLE t_outbox (
    id            BIGINT        NOT NULL COMMENT '雪花 ID',
    topic         VARCHAR(64)   NOT NULL,
    msg_key       VARCHAR(64)   NOT NULL COMMENT '业务键，即 order_no，发送时写入消息 Keys',
    payload       JSON          NOT NULL,
    deliver_at    DATETIME(3)   NULL     COMMENT '定时投递时间，为空表示立即投递',
    status        TINYINT       NOT NULL DEFAULT 0 COMMENT '0 待发送 1 已发送 2 失败',
    retry_count   INT           NOT NULL DEFAULT 0,
    next_retry_at DATETIME(3)   NOT NULL COMMENT '扫表兜底的最早时间，写入时为当前时间 + 3 秒',
    created_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_topic_key (topic, msg_key),
    KEY idx_status_retry (status, next_retry_at)
) COMMENT '本地消息表，归 inventory-service';

CREATE TABLE t_consume_record (
    consumer_group VARCHAR(64) NOT NULL,
    biz_key        VARCHAR(64) NOT NULL COMMENT '消息 Keys，即 order_no',
    created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (consumer_group, biz_key),
    KEY idx_created (created_at)
) COMMENT '消费去重记录，保留 30 天';
