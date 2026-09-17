CREATE TABLE knowledge_document_task (
    id BIGINT NOT NULL AUTO_INCREMENT,
    knowledge_base_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    task_type VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    recovery_count INT NOT NULL DEFAULT 0,
    worker_id VARCHAR(255) NULL,
    run_token CHAR(36) NULL,
    last_error VARCHAR(2000) NULL,
    enqueued_at DATETIME(3) NOT NULL,
    started_at DATETIME(3) NULL,
    heartbeat_at DATETIME(3) NULL,
    finished_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_document_task_logical_identity (document_id, task_type),
    KEY idx_document_task_claim (status, enqueued_at, id),
    KEY idx_document_task_recovery (status, heartbeat_at),
    KEY idx_document_task_kb_status (knowledge_base_id, status),
    CONSTRAINT ck_document_task_type CHECK (task_type IN ('PROCESS', 'INDEX')),
    CONSTRAINT ck_document_task_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_document_task_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_document_task_recovery_count CHECK (recovery_count >= 0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
