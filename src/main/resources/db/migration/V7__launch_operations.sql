-- Back up the production DB before running Flyway. New owner tables use explicit tenant partitions.
CREATE TABLE IF NOT EXISTS operation_grant (
 id BIGINT NOT NULL AUTO_INCREMENT, tenant_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 operator_id BIGINT NOT NULL, request_id VARCHAR(100) NOT NULL, type VARCHAR(24) NOT NULL,
 reason VARCHAR(500) NOT NULL, grant_tokens BIGINT NOT NULL, amount_cny DECIMAL(12,2), order_ref VARCHAR(120),
 created_at DATETIME(6) NOT NULL, PRIMARY KEY(id), UNIQUE KEY uk_grant_request(request_id),
 KEY idx_grant_user_created(user_id,created_at),
 CONSTRAINT fk_grant_user FOREIGN KEY(user_id) REFERENCES app_user(id) ON DELETE CASCADE
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS product_feedback (
 id BIGINT NOT NULL AUTO_INCREMENT, tenant_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 progress VARCHAR(32) NOT NULL, experience VARCHAR(2000) NOT NULL, blockers VARCHAR(250) NOT NULL,
 suggestions VARCHAR(2000), original_experience VARCHAR(2000), follow_up BOOLEAN NOT NULL, contact VARCHAR(200), contact_updated_at DATETIME(6), status VARCHAR(20) NOT NULL,
 review_note VARCHAR(500), reviewer_id BIGINT, reward_grant_id BIGINT,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_feedback_user(user_id),
 CONSTRAINT fk_feedback_user FOREIGN KEY(user_id) REFERENCES app_user(id) ON DELETE CASCADE
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS product_event (
 id BIGINT NOT NULL AUTO_INCREMENT, tenant_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 event_id VARCHAR(120) NOT NULL, name VARCHAR(40) NOT NULL, page VARCHAR(24), session_id BIGINT,
 duration_ms BIGINT, source VARCHAR(32), device VARCHAR(12), result_code VARCHAR(64), server_event BOOLEAN NOT NULL,
 created_at DATETIME(6) NOT NULL, PRIMARY KEY(id), UNIQUE KEY uk_event_user_id(user_id,event_id),
 KEY idx_event_user_created(user_id,created_at), KEY idx_event_created_name(created_at,name),
 CONSTRAINT fk_event_user FOREIGN KEY(user_id) REFERENCES app_user(id) ON DELETE CASCADE
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS analytics_preference (
 id BIGINT NOT NULL AUTO_INCREMENT, tenant_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 enabled BOOLEAN NOT NULL, report_invitation_seen BOOLEAN NOT NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_analytics_user(user_id),
 CONSTRAINT fk_analytics_user FOREIGN KEY(user_id) REFERENCES app_user(id) ON DELETE CASCADE
) ENGINE=InnoDB;
