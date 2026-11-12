-- 仅本地开发与集成测试加载的演示账号，密码均为 mall123（BCrypt）；13800000009 是运营演示账号（ADMIN），可调后台改价接口
-- 10003–10008（13800000002–13800000007）给流量模拟器（simulator 模块）和智能客服模拟器分散成多个用户，限流与 Token 预算按用户各算各的
INSERT INTO t_user (id, phone, password_hash, nickname, role) VALUES
    (10001, '13800000000', '{bcrypt}$2a$10$lTqi19qbJf/pYBPIwsJN3eqaljIG4hub3CAr5zFw51I8CvT.Rc2NG', '张三', 'USER'),
    (10002, '13800000001', '{bcrypt}$2a$10$lTqi19qbJf/pYBPIwsJN3eqaljIG4hub3CAr5zFw51I8CvT.Rc2NG', '李四', 'USER'),
    (10003, '13800000002', '{bcrypt}$2a$10$lTqi19qbJf/pYBPIwsJN3eqaljIG4hub3CAr5zFw51I8CvT.Rc2NG', '王五', 'USER'),
    (10004, '13800000003', '{bcrypt}$2a$10$lTqi19qbJf/pYBPIwsJN3eqaljIG4hub3CAr5zFw51I8CvT.Rc2NG', '赵六', 'USER'),
    (10005, '13800000004', '{bcrypt}$2a$10$lTqi19qbJf/pYBPIwsJN3eqaljIG4hub3CAr5zFw51I8CvT.Rc2NG', '孙七', 'USER'),
    (10006, '13800000005', '{bcrypt}$2a$10$lTqi19qbJf/pYBPIwsJN3eqaljIG4hub3CAr5zFw51I8CvT.Rc2NG', '周八', 'USER'),
    (10007, '13800000006', '{bcrypt}$2a$10$lTqi19qbJf/pYBPIwsJN3eqaljIG4hub3CAr5zFw51I8CvT.Rc2NG', '吴九', 'USER'),
    (10008, '13800000007', '{bcrypt}$2a$10$lTqi19qbJf/pYBPIwsJN3eqaljIG4hub3CAr5zFw51I8CvT.Rc2NG', '郑十', 'USER'),
    (10009, '13800000009', '{bcrypt}$2a$10$lTqi19qbJf/pYBPIwsJN3eqaljIG4hub3CAr5zFw51I8CvT.Rc2NG', '运营小王', 'ADMIN')
ON DUPLICATE KEY UPDATE nickname = VALUES(nickname), role = VALUES(role);
