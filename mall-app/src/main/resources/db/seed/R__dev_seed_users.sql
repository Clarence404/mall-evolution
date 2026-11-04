-- 仅本地开发与集成测试加载的演示账号，密码均为 mall123（BCrypt）；13800000009 是运营演示账号（ADMIN），可调后台改价接口
INSERT INTO t_user (id, phone, password_hash, nickname, role) VALUES
    (10001, '13800000000', '{bcrypt}$2a$10$lTqi19qbJf/pYBPIwsJN3eqaljIG4hub3CAr5zFw51I8CvT.Rc2NG', '张三', 'USER'),
    (10002, '13800000001', '{bcrypt}$2a$10$lTqi19qbJf/pYBPIwsJN3eqaljIG4hub3CAr5zFw51I8CvT.Rc2NG', '李四', 'USER'),
    (10009, '13800000009', '{bcrypt}$2a$10$lTqi19qbJf/pYBPIwsJN3eqaljIG4hub3CAr5zFw51I8CvT.Rc2NG', '运营小王', 'ADMIN')
ON DUPLICATE KEY UPDATE nickname = VALUES(nickname), role = VALUES(role);
