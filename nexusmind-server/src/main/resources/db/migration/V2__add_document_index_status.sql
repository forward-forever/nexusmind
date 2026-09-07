ALTER TABLE knowledge_document
    ADD COLUMN index_status VARCHAR(32) NOT NULL DEFAULT 'NOT_INDEXED' AFTER error_message,
    ADD COLUMN index_error_message VARCHAR(2000) NULL AFTER index_status,
    ADD COLUMN indexed_at DATETIME(3) NULL AFTER index_error_message,
    ADD CONSTRAINT chk_knowledge_document_index_status
        CHECK (index_status IN ('NOT_INDEXED', 'INDEXING', 'INDEXED', 'FAILED'));
