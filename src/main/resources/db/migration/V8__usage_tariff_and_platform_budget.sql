-- Back up production first. Historical usage/credits are deliberately not repriced.
-- Also tolerate the documented fresh-install mysql/update bootstrap before prod/Flyway.
DROP PROCEDURE IF EXISTS interview_ensure_usage_column;
CREATE PROCEDURE interview_ensure_usage_column(IN p_column VARCHAR(64), IN p_type VARCHAR(64))
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = DATABASE() AND table_name = 'llm_usage_log' AND column_name = p_column) THEN
        SET @ddl = CONCAT('ALTER TABLE llm_usage_log ADD COLUMN `', p_column, '` ', p_type, ' NULL');
        PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    END IF;
END;
CALL interview_ensure_usage_column('call_id', 'VARCHAR(64)');
CALL interview_ensure_usage_column('session_id', 'BIGINT');
CALL interview_ensure_usage_column('due_tokens', 'BIGINT');
CALL interview_ensure_usage_column('billing_version', 'VARCHAR(40)');
CALL interview_ensure_usage_column('platform_cost_micros', 'BIGINT');
CALL interview_ensure_usage_column('pricing_version', 'VARCHAR(40)');
CALL interview_ensure_usage_column('status', 'VARCHAR(20)');
CALL interview_ensure_usage_column('attempt_count', 'INT');
DROP PROCEDURE interview_ensure_usage_column;
DROP PROCEDURE IF EXISTS interview_ensure_usage_indexes;
CREATE PROCEDURE interview_ensure_usage_indexes()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
                    AND table_name='llm_usage_log' AND index_name='uk_usage_user_call') THEN
        ALTER TABLE llm_usage_log ADD UNIQUE KEY uk_usage_user_call (user_id, call_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
                    AND table_name='llm_usage_log' AND index_name='idx_usage_session') THEN
        ALTER TABLE llm_usage_log ADD KEY idx_usage_session (user_id, session_id);
    END IF;
END;
CALL interview_ensure_usage_indexes();
DROP PROCEDURE interview_ensure_usage_indexes;

-- These are global platform operations tables, not user-owned data. No private payloads are stored.
CREATE TABLE IF NOT EXISTS platform_budget_day (
    budget_day DATE NOT NULL PRIMARY KEY,
    reserved_micros BIGINT NOT NULL,
    spent_micros BIGINT NOT NULL
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS platform_budget_reservation (
    call_id VARCHAR(64) NOT NULL PRIMARY KEY,
    budget_day DATE NOT NULL,
    reserved_micros BIGINT NOT NULL,
    settled BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    KEY idx_budget_reservation_day (budget_day, settled)
) ENGINE=InnoDB;
