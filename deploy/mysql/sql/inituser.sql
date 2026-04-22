# 1、初始化数据库：创建业务数据库edison_wear
# 2、创建用户，用户名：edison 密码：edison@123
# 3、授予edison用户特定权限

# 1. 创建数据库
CREATE database if NOT EXISTS `edison_wear` default character set utf8mb4 collate utf8mb4_general_ci;

# 2. 创建用户
CREATE USER 'edison'@'%' IDENTIFIED BY 'edison@123';

# 3. 授权
grant replication slave, replication client on *.* to 'edison'@'%';

GRANT ALL PRIVILEGES ON edison_wear.* TO  'edison'@'%';

# 4. 刷新权限
FLUSH PRIVILEGES;
