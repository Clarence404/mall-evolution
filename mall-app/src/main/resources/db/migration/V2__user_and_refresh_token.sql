-- mall-user：用户与刷新令牌（服务端只存刷新令牌的哈希，改密码、踢下线时删掉即可）
CREATE TABLE t_user (
    id            BIGINT       NOT NULL COMMENT '用户 ID',
    phone         VARCHAR(32)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    nickname      VARCHAR(64)  NOT NULL DEFAULT '',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1 正常 0 禁用',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_phone (phone)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户';

CREATE TABLE t_refresh_token (
    id         BIGINT      NOT NULL COMMENT '雪花 ID',
    user_id    BIGINT      NOT NULL,
    token_hash CHAR(64)    NOT NULL COMMENT 'SHA-256(refresh token)',
    expire_at  DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_token_hash (token_hash),
    KEY idx_refresh_user_id (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '刷新令牌';
