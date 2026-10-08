-- R2-T0 多租户（D1，Hibernate @TenantId 分区）：13 张 owner 表加 tenant_id。
-- 仅 MySQL 8（Flyway 在 prod/mysql profile 执行；H2 create-drop 由实体自动建列，不读本脚本）。
-- 存量库三段式：加可空列 → 用 user_id 回填（MVP tenant=userId）→ 改 NOT NULL → 加索引。
-- 上线纪律：先备份；ddl-auto:none 保证本脚本不被 Hibernate 重复应用。

-- 1. jd_profile
ALTER TABLE jd_profile ADD COLUMN tenant_id BIGINT NULL;
UPDATE jd_profile SET tenant_id = user_id WHERE tenant_id IS NULL;
ALTER TABLE jd_profile MODIFY COLUMN tenant_id BIGINT NOT NULL;
CREATE INDEX idx_jd_tenant ON jd_profile (tenant_id);

-- 2. resume_version
ALTER TABLE resume_version ADD COLUMN tenant_id BIGINT NULL;
UPDATE resume_version SET tenant_id = user_id WHERE tenant_id IS NULL;
ALTER TABLE resume_version MODIFY COLUMN tenant_id BIGINT NOT NULL;
CREATE INDEX idx_resume_tenant ON resume_version (tenant_id);

-- 3. note
ALTER TABLE note ADD COLUMN tenant_id BIGINT NULL;
UPDATE note SET tenant_id = user_id WHERE tenant_id IS NULL;
ALTER TABLE note MODIFY COLUMN tenant_id BIGINT NOT NULL;
CREATE INDEX idx_note_tenant ON note (tenant_id);

-- 4. project_doc
ALTER TABLE project_doc ADD COLUMN tenant_id BIGINT NULL;
UPDATE project_doc SET tenant_id = user_id WHERE tenant_id IS NULL;
ALTER TABLE project_doc MODIFY COLUMN tenant_id BIGINT NOT NULL;
CREATE INDEX idx_doc_tenant ON project_doc (tenant_id);

-- 5. real_interview_record
ALTER TABLE real_interview_record ADD COLUMN tenant_id BIGINT NULL;
UPDATE real_interview_record SET tenant_id = user_id WHERE tenant_id IS NULL;
ALTER TABLE real_interview_record MODIFY COLUMN tenant_id BIGINT NOT NULL;
CREATE INDEX idx_record_tenant ON real_interview_record (tenant_id);

-- 6. interview_session
ALTER TABLE interview_session ADD COLUMN tenant_id BIGINT NULL;
UPDATE interview_session SET tenant_id = user_id WHERE tenant_id IS NULL;
ALTER TABLE interview_session MODIFY COLUMN tenant_id BIGINT NOT NULL;
CREATE INDEX idx_session_tenant ON interview_session (tenant_id);

-- 7. interview_turn
ALTER TABLE interview_turn ADD COLUMN tenant_id BIGINT NULL;
UPDATE interview_turn SET tenant_id = user_id WHERE tenant_id IS NULL;
ALTER TABLE interview_turn MODIFY COLUMN tenant_id BIGINT NOT NULL;
CREATE INDEX idx_turn_tenant ON interview_turn (tenant_id);

-- 8. critic_report
ALTER TABLE critic_report ADD COLUMN tenant_id BIGINT NULL;
UPDATE critic_report SET tenant_id = user_id WHERE tenant_id IS NULL;
ALTER TABLE critic_report MODIFY COLUMN tenant_id BIGINT NOT NULL;
CREATE INDEX idx_report_tenant ON critic_report (tenant_id);

-- 9. weak_point
ALTER TABLE weak_point ADD COLUMN tenant_id BIGINT NULL;
UPDATE weak_point SET tenant_id = user_id WHERE tenant_id IS NULL;
ALTER TABLE weak_point MODIFY COLUMN tenant_id BIGINT NOT NULL;
CREATE INDEX idx_wp_tenant ON weak_point (tenant_id);

-- 10. knowledge_chunk
ALTER TABLE knowledge_chunk ADD COLUMN tenant_id BIGINT NULL;
UPDATE knowledge_chunk SET tenant_id = user_id WHERE tenant_id IS NULL;
ALTER TABLE knowledge_chunk MODIFY COLUMN tenant_id BIGINT NOT NULL;
CREATE INDEX idx_chunk_tenant ON knowledge_chunk (tenant_id);

-- 11. quota
ALTER TABLE quota ADD COLUMN tenant_id BIGINT NULL;
UPDATE quota SET tenant_id = user_id WHERE tenant_id IS NULL;
ALTER TABLE quota MODIFY COLUMN tenant_id BIGINT NOT NULL;
CREATE INDEX idx_quota_tenant ON quota (tenant_id);

-- 12. user_api_key
ALTER TABLE user_api_key ADD COLUMN tenant_id BIGINT NULL;
UPDATE user_api_key SET tenant_id = user_id WHERE tenant_id IS NULL;
ALTER TABLE user_api_key MODIFY COLUMN tenant_id BIGINT NOT NULL;
CREATE INDEX idx_uak_tenant ON user_api_key (tenant_id);

-- 13. llm_usage_log
ALTER TABLE llm_usage_log ADD COLUMN tenant_id BIGINT NULL;
UPDATE llm_usage_log SET tenant_id = user_id WHERE tenant_id IS NULL;
ALTER TABLE llm_usage_log MODIFY COLUMN tenant_id BIGINT NOT NULL;
CREATE INDEX idx_usage_tenant ON llm_usage_log (tenant_id);
