-- R1 制造准备增量，仅在独立演练库运行；历史标准保留 LEGACY。
SET NAMES utf8mb4;
CREATE TABLE IF NOT EXISTS `mdm_product_parameter` (
  `parameter_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `product_id` BIGINT UNSIGNED NOT NULL,
  `parameter_code` VARCHAR(64) NOT NULL,
  `parameter_name` VARCHAR(128) NOT NULL,
  `value_type` VARCHAR(16) NOT NULL DEFAULT 'TEXT',
  `parameter_value` VARCHAR(255) NULL,
  `unit_label` VARCHAR(32) NULL,
  `source_reference` VARCHAR(500) NULL,
  `required_flag` TINYINT NOT NULL DEFAULT 1,
  `status` VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  `version` INT NOT NULL DEFAULT 0,
  `created_by` BIGINT UNSIGNED NULL,
  `created_time` DATETIME NULL,
  `updated_by` BIGINT UNSIGNED NULL,
  `updated_time` DATETIME NULL,
  `deleted_flag` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`parameter_id`),
  UNIQUE KEY `uk_product_parameter_code` (`product_id`,`parameter_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='受控产品规格参数';
SET @r1_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='mdm_product' AND column_name='preparation_controlled'),'DO 0','ALTER TABLE `mdm_product` ADD COLUMN `preparation_controlled` TINYINT NOT NULL DEFAULT 0 COMMENT ''是否启用制造准备受控流程，0兼容历史1受控''');
PREPARE r1_stmt FROM @r1_ddl;
EXECUTE r1_stmt;
DEALLOCATE PREPARE r1_stmt;
SET @r1_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='process_route_item' AND column_name='resource_type'),'DO 0','ALTER TABLE `process_route_item` ADD COLUMN `resource_type` VARCHAR(32) NOT NULL DEFAULT ''LABOR'' COMMENT ''LABOR人工节拍，BATCH_EQUIPMENT批量设备占用''');
PREPARE r1_stmt FROM @r1_ddl;
EXECUTE r1_stmt;
DEALLOCATE PREPARE r1_stmt;
SET @r1_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='process_route_item' AND column_name='batch_capacity'),'DO 0','ALTER TABLE `process_route_item` ADD COLUMN `batch_capacity` DECIMAL(18,4) NULL COMMENT ''批量设备每批容量，离散整数''');
PREPARE r1_stmt FROM @r1_ddl;
EXECUTE r1_stmt;
DEALLOCATE PREPARE r1_stmt;
SET @r1_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='process_route_item' AND column_name='batch_duration_minutes'),'DO 0','ALTER TABLE `process_route_item` ADD COLUMN `batch_duration_minutes` DECIMAL(10,2) NULL COMMENT ''每批设备占用分钟，非人工单件工时''');
PREPARE r1_stmt FROM @r1_ddl;
EXECUTE r1_stmt;
DEALLOCATE PREPARE r1_stmt;
SET @r1_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='process_route_item' AND column_name='time_source_reference'),'DO 0','ALTER TABLE `process_route_item` ADD COLUMN `time_source_reference` VARCHAR(500) NULL COMMENT ''工时或设备占用参数依据''');
PREPARE r1_stmt FROM @r1_ddl;
EXECUTE r1_stmt;
DEALLOCATE PREPARE r1_stmt;
SET @r1_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='qms_inspection_standard' AND column_name='confirmation_status'),'DO 0','ALTER TABLE `qms_inspection_standard` ADD COLUMN `confirmation_status` VARCHAR(32) NOT NULL DEFAULT ''LEGACY'' COMMENT ''LEGACY历史兼容，DRAFT草稿，CONFIRMED已确认设计标准''');
PREPARE r1_stmt FROM @r1_ddl;
EXECUTE r1_stmt;
DEALLOCATE PREPARE r1_stmt;
SET @r1_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='qms_inspection_standard' AND column_name='source_reference'),'DO 0','ALTER TABLE `qms_inspection_standard` ADD COLUMN `source_reference` VARCHAR(500) NULL COMMENT ''检验标准依据及版本''');
PREPARE r1_stmt FROM @r1_ddl;
EXECUTE r1_stmt;
DEALLOCATE PREPARE r1_stmt;
START TRANSACTION;
-- R1 permission rename for the local pre-release iteration
UPDATE sys_menu m LEFT JOIN sys_menu n ON n.permission_code='mdm:product-parameter:update' SET m.permission_code='mdm:product-parameter:update' WHERE m.permission_code='mdm:product-parameter:edit' AND m.remark='LAMP_R1' AND n.menu_id IS NULL;
INSERT INTO sys_menu(menu_name,menu_type,permission_code,route_path,remark,created_time) SELECT '制造准备检查','MENU','mdm:manufacturing-preparation:view','/mdm/manufacturing-preparation','LAMP_R1',NOW() WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE permission_code='mdm:manufacturing-preparation:view');
INSERT INTO sys_role_menu(role_id,menu_id,created_time,deleted_flag) SELECT r.role_id,m.menu_id,NOW(),0 FROM sys_role r JOIN sys_menu m ON m.permission_code='mdm:manufacturing-preparation:view' AND m.deleted_flag=0 WHERE r.role_code IN ('SUPER_ADMIN','MES_MANAGER','PLAN_SCHEDULER','QUALITY_ENGINEER') AND r.deleted_flag=0 AND NOT EXISTS(SELECT 1 FROM sys_role_menu x WHERE x.role_id=r.role_id AND x.menu_id=m.menu_id);
INSERT INTO sys_menu(menu_name,menu_type,permission_code,route_path,remark,created_time) SELECT '维护产品参数','BUTTON','mdm:product-parameter:update',NULL,'LAMP_R1',NOW() WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE permission_code='mdm:product-parameter:update');
INSERT INTO sys_role_menu(role_id,menu_id,created_time,deleted_flag) SELECT r.role_id,m.menu_id,NOW(),0 FROM sys_role r JOIN sys_menu m ON m.permission_code='mdm:product-parameter:update' AND m.deleted_flag=0 WHERE r.role_code IN ('SUPER_ADMIN','MES_MANAGER') AND r.deleted_flag=0 AND NOT EXISTS(SELECT 1 FROM sys_role_menu x WHERE x.role_id=r.role_id AND x.menu_id=m.menu_id);
INSERT INTO sys_menu(menu_name,menu_type,permission_code,route_path,remark,created_time) SELECT '确认及退回产品参数','BUTTON','mdm:product-parameter:confirm',NULL,'LAMP_R1',NOW() WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE permission_code='mdm:product-parameter:confirm');
INSERT INTO sys_role_menu(role_id,menu_id,created_time,deleted_flag) SELECT r.role_id,m.menu_id,NOW(),0 FROM sys_role r JOIN sys_menu m ON m.permission_code='mdm:product-parameter:confirm' AND m.deleted_flag=0 WHERE r.role_code IN ('SUPER_ADMIN','MES_MANAGER') AND r.deleted_flag=0 AND NOT EXISTS(SELECT 1 FROM sys_role_menu x WHERE x.role_id=r.role_id AND x.menu_id=m.menu_id);
INSERT INTO sys_menu(menu_name,menu_type,permission_code,route_path,remark,created_time) SELECT '确认及退回检验标准','BUTTON','qms:inspection-standard:confirm',NULL,'LAMP_R1',NOW() WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE permission_code='qms:inspection-standard:confirm');
INSERT INTO sys_role_menu(role_id,menu_id,created_time,deleted_flag) SELECT r.role_id,m.menu_id,NOW(),0 FROM sys_role r JOIN sys_menu m ON m.permission_code='qms:inspection-standard:confirm' AND m.deleted_flag=0 WHERE r.role_code IN ('SUPER_ADMIN','MES_MANAGER','QUALITY_ENGINEER') AND r.deleted_flag=0 AND NOT EXISTS(SELECT 1 FROM sys_role_menu x WHERE x.role_id=r.role_id AND x.menu_id=m.menu_id);
COMMIT;

SET @r1_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='qms_inspection_scheme_item' AND column_name='sampling_mode'),'DO 0','ALTER TABLE qms_inspection_scheme_item ADD COLUMN sampling_mode VARCHAR(16) NOT NULL DEFAULT ''FIXED'' COMMENT ''FIXED固定抽样，ALL按实际批量全检''');
PREPARE r1_stmt FROM @r1_ddl;
EXECUTE r1_stmt;
DEALLOCATE PREPARE r1_stmt;
