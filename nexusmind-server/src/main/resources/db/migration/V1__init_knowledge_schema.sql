CREATE TABLE knowledge_base (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(1024) NULL,
    embedding_model VARCHAR(128) NOT NULL,
    embedding_dimension INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT ck_knowledge_base_embedding_dimension CHECK (embedding_dimension > 0),
    CONSTRAINT ck_knowledge_base_status CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_document (
    id BIGINT NOT NULL AUTO_INCREMENT,
    knowledge_base_id BIGINT NOT NULL,
    original_file_name VARCHAR(255) NOT NULL,
    storage_path VARCHAR(1024) NULL,
    content_type VARCHAR(128) NOT NULL,
    file_size BIGINT NOT NULL,
    file_sha256 CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    chunk_count INT NOT NULL DEFAULT 0,
    error_message VARCHAR(2000) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_document_knowledge_base_id (knowledge_base_id),
    UNIQUE KEY uk_document_kb_sha256 (knowledge_base_id, file_sha256),
    CONSTRAINT ck_knowledge_document_file_size CHECK (file_size >= 0),
    CONSTRAINT ck_knowledge_document_chunk_count CHECK (chunk_count >= 0),
    CONSTRAINT ck_knowledge_document_status CHECK (status IN ('UPLOADED', 'PROCESSING', 'READY', 'FAILED'))
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_chunk (
    id BIGINT NOT NULL AUTO_INCREMENT,
    knowledge_base_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    chunk_index INT NOT NULL,
    content MEDIUMTEXT NOT NULL,
    page_no INT NULL,
    section_title VARCHAR(512) NULL,
    char_count INT NOT NULL,
    token_count INT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_chunk_knowledge_base_id (knowledge_base_id),
    KEY idx_chunk_document_id (document_id),
    UNIQUE KEY uk_chunk_document_index (document_id, chunk_index),
    CONSTRAINT ck_knowledge_chunk_index CHECK (chunk_index >= 0),
    CONSTRAINT ck_knowledge_chunk_char_count CHECK (char_count >= 0),
    CONSTRAINT ck_knowledge_chunk_token_count CHECK (token_count IS NULL OR token_count >= 0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
