-- Internal Agent state only. Back up the database before deploying prod/Flyway.
-- Existing sessions remain NULL and continue with the legacy compatibility path.
-- Tolerate a local mysql/ddl-auto:update database that already has these columns.
DROP PROCEDURE IF EXISTS interview_ensure_agent_column;
CREATE PROCEDURE interview_ensure_agent_column(IN p_table VARCHAR(64), IN p_column VARCHAR(64))
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = DATABASE() AND table_name = p_table AND column_name = p_column) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` LONGTEXT NULL');
        PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    END IF;
END;
CALL interview_ensure_agent_column('interview_session', 'agent_state');
CALL interview_ensure_agent_column('interview_turn', 'agent_metadata');
DROP PROCEDURE interview_ensure_agent_column;
