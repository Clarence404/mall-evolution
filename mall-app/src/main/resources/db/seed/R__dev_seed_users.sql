-- 仅本地开发与集成测试加载的演示账号，密码均为 mall123（BCrypt）
INSERT INTO t_user (id, phone, password_hash, nickname) VALUES
    (10001, '13800000000', '{bcrypt}$2a$10$lTqi19qbJf/pYBPIwsJN3eqaljIG4hub3CAr5zFw51I8CvT.Rc2NG', '张三'),
    (10002, '13800000001', '{bcrypt}$2a$10$lTqi19qbJf/pYBPIwsJN3eqaljIG4hub3CAr5zFw51I8CvT.Rc2NG', '李四')
ON DUPLICATE KEY UPDATE nickname = VALUES(nickname);
