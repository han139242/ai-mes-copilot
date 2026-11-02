-- 线边库位与产线的明确关联；不移动已有库存，不推断历史归属。
SET NAMES utf8mb4;
SET @ls_guard=IF(DATABASE() REGEXP '^mes_lamp_[A-Za-z0-9_]+$','DO 0','SELECT * FROM LAMP_ERROR_ISOLATED_DATABASE_REQUIRED');
PREPARE ls_stmt FROM @ls_guard; EXECUTE ls_stmt; DEALLOCATE PREPARE ls_stmt;
SET @ls_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='wms_location' AND column_name='line_id'),
 'DO 0','ALTER TABLE wms_location ADD COLUMN line_id BIGINT UNSIGNED NULL COMMENT ''线边库位所属产线；空为共享'' AFTER warehouse_id');
PREPARE ls_stmt FROM @ls_ddl; EXECUTE ls_stmt; DEALLOCATE PREPARE ls_stmt;
SET @ls_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='mfg_task' AND column_name='defect_resolution_task_id'),
 'DO 0','ALTER TABLE mfg_task ADD COLUMN defect_resolution_task_id BIGINT UNSIGNED NULL COMMENT ''工序报工不良处置来源任务'' AFTER source_ncr_id');
PREPARE ls_stmt FROM @ls_ddl; EXECUTE ls_stmt; DEALLOCATE PREPARE ls_stmt;
