-- R7 软性面试官：Critic 报告新增行为维度汇总（STAR+表达四维 / 总评 / 改进建议）
-- 上线前 ddl-auto:none 时手动执行此脚本（MySQL ddl-auto:update 会自动加列，无需执行；H2 测试 profile 自动建表）
-- H2 / MySQL 8 兼容；纯技术场该列为 NULL，老报告无需回填

ALTER TABLE critic_report ADD COLUMN behavior_review TEXT;
