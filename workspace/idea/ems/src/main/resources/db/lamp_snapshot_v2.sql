-- 冻结快照v2需要显式投料工序。仅加结构，不改已发布BOM或已下达快照。
SET NAMES utf8mb4;
SET @lamp_scope_sql=IF(DATABASE() REGEXP '^mes_lamp_[A-Za-z0-9_]+$', 'DO 0', 'SELECT * FROM LAMP_ERROR_ISOLATED_DATABASE_REQUIRED');
PREPARE lamp_scope_stmt FROM @lamp_scope_sql;
EXECUTE lamp_scope_stmt;
DEALLOCATE PREPARE lamp_scope_stmt;
SET @lamp_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='mdm_bom_item' AND column_name='consumption_operation_id'), 'DO 0', 'ALTER TABLE mdm_bom_item ADD COLUMN consumption_operation_id BIGINT UNSIGNED NULL COMMENT ''本级直接物料的投料工序；历史空值表示尚未配置。''');
PREPARE lamp_stmt FROM @lamp_ddl;
EXECUTE lamp_stmt;
DEALLOCATE PREPARE lamp_stmt;
