DROP TABLE IF EXISTS workflow_node_runs;
DROP TABLE IF EXISTS workflow_runs;
DROP TABLE IF EXISTS workflow_template_nodes;
DROP TABLE IF EXISTS workflow_templates;

CREATE TABLE workflow_templates (
    id VARCHAR(48) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(500) NOT NULL,
    version INT NOT NULL,
    created_at_ms BIGINT NOT NULL
);

CREATE TABLE workflow_template_nodes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    template_id VARCHAR(48) NOT NULL,
    node_key VARCHAR(64) NOT NULL,
    node_type VARCHAR(40) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    node_order INT NOT NULL,
    max_attempts INT NOT NULL,
    timeout_seconds INT NOT NULL,
    CONSTRAINT uq_template_node_key UNIQUE (template_id, node_key),
    CONSTRAINT uq_template_node_order UNIQUE (template_id, node_order)
);

CREATE TABLE workflow_runs (
    id VARCHAR(48) PRIMARY KEY,
    template_id VARCHAR(48) NOT NULL,
    status VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    title VARCHAR(200) NOT NULL,
    source VARCHAR(120) NOT NULL,
    content CLOB NOT NULL,
    output_json CLOB,
    current_node VARCHAR(64),
    error_message VARCHAR(1000),
    created_at_ms BIGINT NOT NULL,
    started_at_ms BIGINT,
    finished_at_ms BIGINT,
    updated_at_ms BIGINT NOT NULL,
    CONSTRAINT uq_run_idempotency UNIQUE (idempotency_key)
);

CREATE TABLE workflow_node_runs (
    id VARCHAR(48) PRIMARY KEY,
    run_id VARCHAR(48) NOT NULL,
    node_key VARCHAR(64) NOT NULL,
    node_type VARCHAR(40) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    node_order INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempts INT NOT NULL,
    output_json CLOB,
    error_message VARCHAR(1000),
    prompt_tokens INT NOT NULL DEFAULT 0,
    completion_tokens INT NOT NULL DEFAULT 0,
    started_at_ms BIGINT,
    finished_at_ms BIGINT,
    duration_ms BIGINT,
    CONSTRAINT uq_node_run_key UNIQUE (run_id, node_key)
);

INSERT INTO workflow_templates (id, name, description, version, created_at_ms)
VALUES ('tpl_media_review_v1', '媒体内容评审流水线', '测试用媒体工作流', 1, 1763000000000);

INSERT INTO workflow_template_nodes
    (template_id, node_key, node_type, display_name, node_order, max_attempts, timeout_seconds)
VALUES
    ('tpl_media_review_v1', 'validate', 'VALIDATE', '校验与清洗', 1, 1, 10),
    ('tpl_media_review_v1', 'summary', 'SUMMARIZE', '生成摘要', 2, 2, 30),
    ('tpl_media_review_v1', 'titles', 'TITLE', '生成标题', 3, 2, 30),
    ('tpl_media_review_v1', 'quality', 'QUALITY', '质量评分', 4, 2, 30),
    ('tpl_media_review_v1', 'review', 'HUMAN_REVIEW', '人工复核', 5, 1, 60);
