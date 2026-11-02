-- 智能台灯阶段1：兼容旧场景的增量迁移。执行前备份，连接时明确指定目标库。
-- 不修改已有物料的数量类型，不改历史业务数量；可以重复执行。
SET NAMES utf8mb4;
SET @lamp_schema=DATABASE();

SET @lamp_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=@lamp_schema AND table_name='mdm_material' AND column_name='quantity_mode'),'DO 0','ALTER TABLE `mdm_material` ADD COLUMN `quantity_mode` VARCHAR(16) NOT NULL DEFAULT ''CONTINUOUS'' COMMENT ''DISCRETE:离散整数，CONTINUOUS:连续数量''');
PREPARE lamp_stmt FROM @lamp_ddl;
EXECUTE lamp_stmt;
DEALLOCATE PREPARE lamp_stmt;

SET @lamp_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=@lamp_schema AND table_name='mdm_material' AND column_name='issue_step'),'DO 0','ALTER TABLE `mdm_material` ADD COLUMN `issue_step` DECIMAL(18,4) NOT NULL DEFAULT 0.0001 COMMENT ''合并理论需求后的最小发料粒度''');
PREPARE lamp_stmt FROM @lamp_ddl;
EXECUTE lamp_stmt;
DEALLOCATE PREPARE lamp_stmt;

SET @lamp_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=@lamp_schema AND table_name='mdm_bom' AND column_name='effective_to'),'DO 0','ALTER TABLE `mdm_bom` ADD COLUMN `effective_to` DATE NULL COMMENT ''BOM失效日期，含当日''');
PREPARE lamp_stmt FROM @lamp_ddl;
EXECUTE lamp_stmt;
DEALLOCATE PREPARE lamp_stmt;

SET @lamp_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=@lamp_schema AND table_name='mdm_bom_item' AND column_name='child_bom_id'),'DO 0','ALTER TABLE `mdm_bom_item` ADD COLUMN `child_bom_id` BIGINT UNSIGNED NULL COMMENT ''明确引用的自制半成品BOM版本''');
PREPARE lamp_stmt FROM @lamp_ddl;
EXECUTE lamp_stmt;
DEALLOCATE PREPARE lamp_stmt;

SET @lamp_ddl=IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=@lamp_schema AND table_name='mdm_bom_item' AND index_name='idx_mdm_bom_item_child_bom_id'),'DO 0','ALTER TABLE mdm_bom_item ADD INDEX idx_mdm_bom_item_child_bom_id (child_bom_id)');
PREPARE lamp_stmt FROM @lamp_ddl;
EXECUTE lamp_stmt;
DEALLOCATE PREPARE lamp_stmt;

START TRANSACTION;
INSERT INTO sys_menu(parent_id,menu_name,menu_type,permission_code,route_path,remark,created_time)
SELECT (SELECT menu_id FROM (SELECT menu_id FROM sys_menu WHERE permission_code='mdm:bom:view' AND deleted_flag=0 LIMIT 1) existing_menu),'BOM需求分析','MENU','mdm:bom:expand','/mdm/bom-explorer','LAMP_FOUNDATION_V1: 递归需求与组件库存抵扣，只读',NOW()
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE permission_code='mdm:bom:expand');
INSERT INTO sys_role_menu(role_id,menu_id,created_time,deleted_flag)
SELECT r.role_id,m.menu_id,NOW(),0 FROM sys_role r JOIN sys_menu m ON m.permission_code='mdm:bom:expand' AND m.deleted_flag=0
WHERE r.role_code IN ('SUPER_ADMIN','MES_MANAGER','PLAN_SCHEDULER') AND r.deleted_flag=0
AND NOT EXISTS(SELECT 1 FROM sys_role_menu x WHERE x.role_id=r.role_id AND x.menu_id=m.menu_id);
COMMIT;
