-- 业务短单号：客户前缀、每日流水及历史单号映射。只升级结构，不改写已有单号。
SET NAMES utf8mb4;
SET @number_guard=IF(DATABASE() REGEXP '^mes_lamp_[A-Za-z0-9_]+$','DO 0','SELECT * FROM LAMP_ERROR_ISOLATED_DATABASE_REQUIRED');
PREPARE number_stmt FROM @number_guard; EXECUTE number_stmt; DEALLOCATE PREPARE number_stmt;
SET @number_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='mdm_customer' AND column_name='number_prefix'),
    'DO 0','ALTER TABLE mdm_customer ADD COLUMN number_prefix VARCHAR(8) NULL COMMENT ''业务单号客户前缀'' AFTER customer_name');
PREPARE number_stmt FROM @number_ddl; EXECUTE number_stmt; DEALLOCATE PREPARE number_stmt;
UPDATE mdm_customer SET number_prefix=CONCAT('C',UPPER(CONV(customer_id,10,36))) WHERE number_prefix IS NULL;
SET @number_ddl=IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='mdm_customer' AND index_name='uk_mdm_customer_number_prefix'),
    'DO 0','ALTER TABLE mdm_customer ADD UNIQUE KEY uk_mdm_customer_number_prefix(number_prefix)');
PREPARE number_stmt FROM @number_ddl; EXECUTE number_stmt; DEALLOCATE PREPARE number_stmt;

CREATE TABLE IF NOT EXISTS `sys_document_sequence` (
  `sequence_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `scope_prefix` VARCHAR(8) NOT NULL,
  `document_type` VARCHAR(4) NOT NULL,
  `business_date` DATE NOT NULL,
  `current_value` BIGINT UNSIGNED NOT NULL,
  `updated_time` DATETIME NOT NULL,
  PRIMARY KEY (`sequence_id`),
  UNIQUE KEY `uk_document_sequence_scope` (`scope_prefix`,`document_type`,`business_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='业务单号每日流水；不回收已用序号';

CREATE TABLE IF NOT EXISTS `sys_document_number_alias` (
  `alias_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `document_type` VARCHAR(4) NOT NULL,
  `biz_id` BIGINT UNSIGNED NOT NULL,
  `original_number` VARCHAR(128) NOT NULL,
  `current_number` VARCHAR(64) NOT NULL,
  `changed_time` DATETIME NOT NULL,
  PRIMARY KEY (`alias_id`),
  UNIQUE KEY `uk_document_alias_original` (`document_type`,`original_number`),
  KEY `idx_document_alias_number` (`original_number`),
  KEY `idx_document_alias_biz` (`document_type`,`biz_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='旧单号到当前单号的兼容映射';
