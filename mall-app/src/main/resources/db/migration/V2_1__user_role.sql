-- mall-user：用户角色。USER 普通用户，ADMIN 运营（可调 /api/admin/** 后台接口）；访问令牌里带 roles 声明
ALTER TABLE t_user ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'USER';
