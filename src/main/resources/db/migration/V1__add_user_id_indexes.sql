-- R1-2 多用户隔离：user_id 索引
-- 上线前 ddl-auto:none 时手动执行此脚本
-- H2 / MySQL 8 兼容

-- User（app_user）username 唯一索引（R1-1 新增表）
-- CREATE UNIQUE INDEX IF NOT EXISTS uk_username ON app_user(username);

-- JD
CREATE INDEX IF NOT EXISTS idx_jd_user ON jd_profile(user_id);
CREATE INDEX IF NOT EXISTS idx_jd_user_created ON jd_profile(user_id, created_at);

-- Resume
CREATE INDEX IF NOT EXISTS idx_resume_user ON resume_version(user_id);
CREATE INDEX IF NOT EXISTS idx_resume_user_active ON resume_version(user_id, is_active);

-- Interview Session
CREATE INDEX IF NOT EXISTS idx_session_user ON interview_session(user_id);
CREATE INDEX IF NOT EXISTS idx_session_user_created ON interview_session(user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_session_status ON interview_session(status);

-- Interview Turn
CREATE INDEX IF NOT EXISTS idx_turn_session ON interview_turn(session_id);
CREATE INDEX IF NOT EXISTS idx_turn_user ON interview_turn(user_id);

-- Critic Report
CREATE INDEX IF NOT EXISTS idx_report_session ON critic_report(session_id);
CREATE INDEX IF NOT EXISTS idx_report_session_user ON critic_report(session_id, user_id);

-- Weak Point
CREATE INDEX IF NOT EXISTS idx_wp_user ON weak_point(user_id);
CREATE INDEX IF NOT EXISTS idx_wp_user_resolved ON weak_point(user_id, resolved);
CREATE INDEX IF NOT EXISTS idx_wp_user_review ON weak_point(user_id, resolved, next_review_at);

-- Knowledge Chunk
CREATE INDEX IF NOT EXISTS idx_chunk_user ON knowledge_chunk(user_id);
CREATE INDEX IF NOT EXISTS idx_chunk_source ON knowledge_chunk(source_type, source_id);

-- Note
CREATE INDEX IF NOT EXISTS idx_note_user ON note(user_id);

-- Project Doc
CREATE INDEX IF NOT EXISTS idx_doc_user ON project_doc(user_id);

-- Real Interview Record
CREATE INDEX IF NOT EXISTS idx_record_user ON real_interview_record(user_id);
