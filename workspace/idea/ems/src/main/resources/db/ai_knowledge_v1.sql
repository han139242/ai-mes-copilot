-- 知识助手：制度与说明书章节索引（供带出处的问答使用）
SET @lamp_scope_sql = IF(DATABASE() REGEXP '^mes_lamp_[A-Za-z0-9_]+$', 'DO 0', 'SELECT * FROM LAMP_ERROR_ISOLATED_DATABASE_REQUIRED');
PREPARE lamp_scope_stmt FROM @lamp_scope_sql;
EXECUTE lamp_scope_stmt;
DEALLOCATE PREPARE lamp_scope_stmt;

CREATE TABLE IF NOT EXISTS ai_knowledge_section (
  section_id   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '章节主键',
  doc_code     VARCHAR(32)  NOT NULL COMMENT '文档代号：REQ 需求规格说明书 / DES 系统设计说明书',
  doc_name     VARCHAR(128) NOT NULL COMMENT '文档名称',
  seq_no       INT          NOT NULL DEFAULT 0 COMMENT '章节序号，按文档出现顺序',
  level_no     INT          NOT NULL DEFAULT 1 COMMENT '标题层级：1-4',
  title        VARCHAR(255) NOT NULL COMMENT '章节标题，含编号',
  page_no      INT          NULL COMMENT '在导出 PDF 中的页码',
  content      LONGTEXT     NULL COMMENT '章节正文，截取前若干字',
  version      INT          NOT NULL DEFAULT 0,
  created_by   BIGINT       NULL,
  created_time DATETIME     NULL,
  updated_by   BIGINT       NULL,
  updated_time DATETIME     NULL,
  deleted_flag TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (section_id),
  KEY idx_ai_knowledge_doc (doc_code, seq_no),
  KEY idx_ai_knowledge_title (title)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 知识助手章节索引';
