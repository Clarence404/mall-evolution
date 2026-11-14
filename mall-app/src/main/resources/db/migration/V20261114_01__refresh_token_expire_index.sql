-- 过期刷新令牌每日清理（RefreshTokenCleanupJob）按 expire_at 删除，加索引避免每次全表扫描。纯扩展：旧版本代码不受影响
CREATE INDEX idx_refresh_expire_at ON t_refresh_token (expire_at);
