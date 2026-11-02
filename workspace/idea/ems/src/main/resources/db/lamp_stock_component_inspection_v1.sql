-- 半成品库存领用复验关联，保留原批次与出库明细，不虚构生产产出。可重复执行。
SET @stock_scope = IF(DATABASE() REGEXP '^mes_lamp_[A-Za-z0-9_]+$', 'DO 0',
 'SELECT * FROM LAMP_ERROR_ISOLATED_DATABASE_REQUIRED');
PREPARE stock_scope_stmt FROM @stock_scope;
EXECUTE stock_scope_stmt;
DEALLOCATE PREPARE stock_scope_stmt;
SET @stock_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns
 WHERE table_schema=DATABASE() AND table_name='wms_outbound_detail' AND column_name='inspection_id'),
 'DO 0', 'ALTER TABLE wms_outbound_detail ADD COLUMN inspection_id BIGINT UNSIGNED NULL COMMENT ''库存半成品领用复验单'' AFTER location_id');
PREPARE stock_stmt FROM @stock_ddl;
EXECUTE stock_stmt;
DEALLOCATE PREPARE stock_stmt;
SET @stock_index_ddl = IF(EXISTS(SELECT 1 FROM information_schema.statistics
 WHERE table_schema=DATABASE() AND table_name='wms_outbound_detail' AND index_name='idx_wms_outbound_detail_inspection_id'),
 'DO 0', 'ALTER TABLE wms_outbound_detail ADD INDEX idx_wms_outbound_detail_inspection_id (inspection_id)');
PREPARE stock_index_stmt FROM @stock_index_ddl;
EXECUTE stock_index_stmt;
DEALLOCATE PREPARE stock_index_stmt;
