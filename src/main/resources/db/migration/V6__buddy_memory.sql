-- Back up prod before applying Flyway. No chat text or transient emotions are stored.
-- IF NOT EXISTS also tolerates a local mysql/ddl-auto:update schema made by this version.
CREATE TABLE IF NOT EXISTS buddy_memory (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL,
    revision BIGINT NOT NULL,
    state_json LONGTEXT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_buddy_memory_user (user_id),
    CONSTRAINT fk_buddy_memory_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE
) ENGINE=InnoDB;
