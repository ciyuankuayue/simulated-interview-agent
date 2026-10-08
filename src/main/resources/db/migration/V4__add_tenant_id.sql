-- R2-T0 多租户（D1，Hibernate @TenantId 分区）：13 张 owner 表加 tenant_id。
-- 仅 MySQL 8（仅 prod profile 由 Flyway 执行；H2 create-drop 由实体自动建列，不读本脚本）。
-- 存量库四段式：加可空列 → 用 user_id 回填（MVP tenant=userId）→ 改 NOT NULL → 加索引。
--
-- 幂等：列/索引存在性经 information_schema 判断。两种库形态都可安全执行：
--   A. 存量库（mysql profile update 维护，无 tenant_id）→ 正常四段式；
--   B. 全新库（首部署先用 mysql profile update 建表，实体已带可空 tenant_id）
--      → ADD 跳过，回填/NOT NULL/索引照常收敛。
-- 上线纪律：先备份；prod 的 ddl-auto:none 保证本脚本不被 Hibernate 重复应用。
-- Flyway 原生理解 MySQL 存储过程复合语句，无需 DELIMITER。

DROP PROCEDURE IF EXISTS r2t0_ensure_tenant_column;
DROP PROCEDURE IF EXISTS r2t0_ensure_tenant_index;

CREATE PROCEDURE r2t0_ensure_tenant_column(IN p_table VARCHAR(64))
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = DATABASE()
                     AND table_name = p_table AND column_name = 'tenant_id') THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN tenant_id BIGINT NULL');
        PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    END IF;
    SET @dml = CONCAT('UPDATE `', p_table, '` SET tenant_id = user_id WHERE tenant_id IS NULL');
    PREPARE stmt FROM @dml; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    SET @ddl = CONCAT('ALTER TABLE `', p_table, '` MODIFY COLUMN tenant_id BIGINT NOT NULL');
    PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
END;

CREATE PROCEDURE r2t0_ensure_tenant_index(IN p_table VARCHAR(64), IN p_index VARCHAR(64))
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE()
                     AND table_name = p_table AND index_name = p_index) THEN
        SET @ddl = CONCAT('CREATE INDEX `', p_index, '` ON `', p_table, '` (tenant_id)');
        PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    END IF;
END;

-- 1. jd_profile
CALL r2t0_ensure_tenant_column('jd_profile');
CALL r2t0_ensure_tenant_index('jd_profile', 'idx_jd_tenant');

-- 2. resume_version
CALL r2t0_ensure_tenant_column('resume_version');
CALL r2t0_ensure_tenant_index('resume_version', 'idx_resume_tenant');

-- 3. note
CALL r2t0_ensure_tenant_column('note');
CALL r2t0_ensure_tenant_index('note', 'idx_note_tenant');

-- 4. project_doc
CALL r2t0_ensure_tenant_column('project_doc');
CALL r2t0_ensure_tenant_index('project_doc', 'idx_doc_tenant');

-- 5. real_interview_record
CALL r2t0_ensure_tenant_column('real_interview_record');
CALL r2t0_ensure_tenant_index('real_interview_record', 'idx_record_tenant');

-- 6. interview_session
CALL r2t0_ensure_tenant_column('interview_session');
CALL r2t0_ensure_tenant_index('interview_session', 'idx_session_tenant');

-- 7. interview_turn
CALL r2t0_ensure_tenant_column('interview_turn');
CALL r2t0_ensure_tenant_index('interview_turn', 'idx_turn_tenant');

-- 8. critic_report
CALL r2t0_ensure_tenant_column('critic_report');
CALL r2t0_ensure_tenant_index('critic_report', 'idx_report_tenant');

-- 9. weak_point
CALL r2t0_ensure_tenant_column('weak_point');
CALL r2t0_ensure_tenant_index('weak_point', 'idx_wp_tenant');

-- 10. knowledge_chunk
CALL r2t0_ensure_tenant_column('knowledge_chunk');
CALL r2t0_ensure_tenant_index('knowledge_chunk', 'idx_chunk_tenant');

-- 11. quota
CALL r2t0_ensure_tenant_column('quota');
CALL r2t0_ensure_tenant_index('quota', 'idx_quota_tenant');

-- 12. user_api_key
CALL r2t0_ensure_tenant_column('user_api_key');
CALL r2t0_ensure_tenant_index('user_api_key', 'idx_uak_tenant');

-- 13. llm_usage_log
CALL r2t0_ensure_tenant_column('llm_usage_log');
CALL r2t0_ensure_tenant_index('llm_usage_log', 'idx_usage_tenant');

DROP PROCEDURE IF EXISTS r2t0_ensure_tenant_column;
DROP PROCEDURE IF EXISTS r2t0_ensure_tenant_index;
