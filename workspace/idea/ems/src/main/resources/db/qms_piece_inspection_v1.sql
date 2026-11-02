-- 逐件全检：给检验结果明细补上「件」的维度，支撑「每件产品每个必检项都要有结果」。
-- 只作用于台灯演练库，避免在非台灯库上误执行。
SET @lamp_scope_sql = IF(DATABASE() REGEXP '^mes_lamp_[A-Za-z0-9_]+$', 'DO 0',
    'SELECT * FROM LAMP_ERROR_ISOLATED_DATABASE_REQUIRED');
PREPARE lamp_scope_stmt FROM @lamp_scope_sql;
EXECUTE lamp_scope_stmt;
DEALLOCATE PREPARE lamp_scope_stmt;

-- 件号：同一张检验单里每件产品的唯一标识，例如 L200-001。
SET @has_piece_no = (SELECT COUNT(*) FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qms_inspection_result'
                       AND COLUMN_NAME = 'piece_no');
SET @sql = IF(@has_piece_no = 0,
    'ALTER TABLE qms_inspection_result ADD COLUMN piece_no VARCHAR(64) NULL COMMENT ''件号，逐件全检时必填'' AFTER inspection_id',
    'DO 0');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 同一张单、同一个检验项、同一件产品只允许一条结果，从库层挡住重复登记。
SET @has_idx = (SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qms_inspection_result'
                  AND INDEX_NAME = 'uk_qms_result_piece');
SET @sql = IF(@has_idx = 0,
    'ALTER TABLE qms_inspection_result ADD UNIQUE KEY uk_qms_result_piece (inspection_id, item_id, piece_no)',
    'DO 0');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
