-- 本地开发：阶段三按服务拆库，每个服务只连自己的库
CREATE DATABASE IF NOT EXISTS mall_inventory DEFAULT CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS mall_payment DEFAULT CHARACTER SET utf8mb4;
GRANT ALL PRIVILEGES ON mall_inventory.* TO 'mall'@'%';
GRANT ALL PRIVILEGES ON mall_payment.* TO 'mall'@'%';
FLUSH PRIVILEGES;
