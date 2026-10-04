-- R3-2 多模型：用户对话模型厂商选择（DEEPSEEK/QWEN）
-- 上线前 ddl-auto:none 时手动执行此脚本（MySQL ddl-auto:update 会自动加列，无需执行）
-- H2 / MySQL 8 兼容；表名以 User 实体 @Table(name="app_user") 为准，VARCHAR(16) 容纳两个枚举足够

ALTER TABLE app_user ADD COLUMN chat_provider VARCHAR(16) NOT NULL DEFAULT 'DEEPSEEK';
