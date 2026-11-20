-- MediaFlow AI schema and seeded media review workflow.
CREATE TABLE workflow_templates (
    id VARCHAR(48) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(500) NOT NULL,
    version INT NOT NULL,
    created_at_ms BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE workflow_template_nodes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    template_id VARCHAR(48) NOT NULL,
    node_key VARCHAR(64) NOT NULL,
    node_type VARCHAR(40) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    node_order INT NOT NULL,
    max_attempts INT NOT NULL,
    timeout_seconds INT NOT NULL,
    CONSTRAINT fk_template_node_template
        FOREIGN KEY (template_id) REFERENCES workflow_templates(id),
    CONSTRAINT uq_template_node_key UNIQUE (template_id, node_key),
    CONSTRAINT uq_template_node_order UNIQUE (template_id, node_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE workflow_runs (
    id VARCHAR(48) PRIMARY KEY,
    template_id VARCHAR(48) NOT NULL,
    status VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    title VARCHAR(200) NOT NULL,
    source VARCHAR(120) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    output_json MEDIUMTEXT NULL,
    current_node VARCHAR(64) NULL,
    error_message VARCHAR(1000) NULL,
    created_at_ms BIGINT NOT NULL,
    started_at_ms BIGINT NULL,
    finished_at_ms BIGINT NULL,
    updated_at_ms BIGINT NOT NULL,
    CONSTRAINT fk_run_template
        FOREIGN KEY (template_id) REFERENCES workflow_templates(id),
    CONSTRAINT uq_run_idempotency UNIQUE (idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_run_status_created ON workflow_runs (status, created_at_ms DESC);
CREATE INDEX idx_run_template_created ON workflow_runs (template_id, created_at_ms DESC);

CREATE TABLE workflow_node_runs (
    id VARCHAR(48) PRIMARY KEY,
    run_id VARCHAR(48) NOT NULL,
    node_key VARCHAR(64) NOT NULL,
    node_type VARCHAR(40) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    node_order INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempts INT NOT NULL,
    output_json MEDIUMTEXT NULL,
    error_message VARCHAR(1000) NULL,
    prompt_tokens INT NOT NULL DEFAULT 0,
    completion_tokens INT NOT NULL DEFAULT 0,
    started_at_ms BIGINT NULL,
    finished_at_ms BIGINT NULL,
    duration_ms BIGINT NULL,
    CONSTRAINT fk_node_run_run
        FOREIGN KEY (run_id) REFERENCES workflow_runs(id) ON DELETE CASCADE,
    CONSTRAINT uq_node_run_key UNIQUE (run_id, node_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_node_run_run_order ON workflow_node_runs (run_id, node_order);

INSERT INTO workflow_templates (id, name, description, version, created_at_ms)
VALUES (
    'tpl_media_review_v1',
    '媒体内容评审流水线',
    '面向媒体内容的摘要、标题生成、质量评分和人工复核工作流。',
    1,
    UNIX_TIMESTAMP() * 1000
);

INSERT INTO workflow_template_nodes
    (template_id, node_key, node_type, display_name, node_order, max_attempts, timeout_seconds)
VALUES
    ('tpl_media_review_v1', 'validate', 'VALIDATE', '校验与清洗', 1, 1, 10),
    ('tpl_media_review_v1', 'summary', 'SUMMARIZE', '生成摘要', 2, 2, 30),
    ('tpl_media_review_v1', 'titles', 'TITLE', '生成标题', 3, 2, 30),
    ('tpl_media_review_v1', 'quality', 'QUALITY', '质量评分', 4, 2, 30),
    ('tpl_media_review_v1', 'review', 'HUMAN_REVIEW', '人工复核', 5, 1, 60);
