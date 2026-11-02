-- 报工不良信息独立保存；历史记录留空，不虚构原因。可重复执行。
SET @has_defect_description = (SELECT COUNT(*) FROM information_schema.columns
 WHERE table_schema=DATABASE() AND table_name='mfg_work_report' AND column_name='defect_description');
SET @defect_description_ddl = IF(@has_defect_description=0,
 'ALTER TABLE mfg_work_report ADD COLUMN defect_description VARCHAR(1000) NULL COMMENT ''生产不良说明'' AFTER defect_quantity',
 'SELECT 1');
PREPARE defect_description_stmt FROM @defect_description_ddl;
EXECUTE defect_description_stmt;
DEALLOCATE PREPARE defect_description_stmt;
