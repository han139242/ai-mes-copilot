-- 智能台灯 MES 执行链 v1：父子工单、工单快照、线边库占用、销售发运、日期班组排班。
-- 只增量加列/建表，不改历史业务数量；可重复执行。
SET NAMES utf8mb4;
-- 仅限独立台灯库；请通过受保护入口执行，禁止使用 mysql --force 忽略错误。
SET @lamp_scope_sql=IF(DATABASE() REGEXP '^mes_lamp_[A-Za-z0-9_]+$', 'DO 0', 'SELECT * FROM LAMP_ERROR_ISOLATED_DATABASE_REQUIRED');
PREPARE lamp_scope_stmt FROM @lamp_scope_sql;
EXECUTE lamp_scope_stmt;
DEALLOCATE PREPARE lamp_scope_stmt;

SET @lamp_schema=DATABASE();

-- ---------- 1. 生产计划：区分根计划与组件计划 ----------
SET @lamp_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=@lamp_schema AND table_name='mfg_production_plan' AND column_name='plan_type'),'DO 0','ALTER TABLE `mfg_production_plan` ADD COLUMN `plan_type` VARCHAR(16) NOT NULL DEFAULT ''ROOT'' COMMENT ''ROOT:整机根计划，COMPONENT:组件补产计划''');
PREPARE lamp_stmt FROM @lamp_ddl; EXECUTE lamp_stmt; DEALLOCATE PREPARE lamp_stmt;

SET @lamp_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=@lamp_schema AND table_name='mfg_production_plan' AND column_name='parent_plan_id'),'DO 0','ALTER TABLE `mfg_production_plan` ADD COLUMN `parent_plan_id` BIGINT UNSIGNED NULL COMMENT ''组件计划来源的父计划''');
PREPARE lamp_stmt FROM @lamp_ddl; EXECUTE lamp_stmt; DEALLOCATE PREPARE lamp_stmt;

SET @lamp_ddl=IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=@lamp_schema AND table_name='mfg_production_plan' AND index_name='idx_mfg_production_plan_parent_plan_id'),'DO 0','ALTER TABLE mfg_production_plan ADD INDEX idx_mfg_production_plan_parent_plan_id (parent_plan_id)');
PREPARE lamp_stmt FROM @lamp_ddl; EXECUTE lamp_stmt; DEALLOCATE PREPARE lamp_stmt;

-- ---------- 2. 生产工单：父子关系与冻结快照 ----------
SET @lamp_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=@lamp_schema AND table_name='mfg_work_order' AND column_name='work_order_type'),'DO 0','ALTER TABLE `mfg_work_order` ADD COLUMN `work_order_type` VARCHAR(16) NOT NULL DEFAULT ''ROOT'' COMMENT ''ROOT:整机根工单，COMPONENT:组件工单''');
PREPARE lamp_stmt FROM @lamp_ddl; EXECUTE lamp_stmt; DEALLOCATE PREPARE lamp_stmt;

SET @lamp_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=@lamp_schema AND table_name='mfg_work_order' AND column_name='parent_work_order_id'),'DO 0','ALTER TABLE `mfg_work_order` ADD COLUMN `parent_work_order_id` BIGINT UNSIGNED NULL COMMENT ''父工单：组件工单指向根工单''');
PREPARE lamp_stmt FROM @lamp_ddl; EXECUTE lamp_stmt; DEALLOCATE PREPARE lamp_stmt;

SET @lamp_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=@lamp_schema AND table_name='mfg_work_order' AND column_name='root_work_order_id'),'DO 0','ALTER TABLE `mfg_work_order` ADD COLUMN `root_work_order_id` BIGINT UNSIGNED NULL COMMENT ''根工单：生产树汇总口径''');
PREPARE lamp_stmt FROM @lamp_ddl; EXECUTE lamp_stmt; DEALLOCATE PREPARE lamp_stmt;

SET @lamp_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=@lamp_schema AND table_name='mfg_work_order' AND column_name='source_requirement_id'),'DO 0','ALTER TABLE `mfg_work_order` ADD COLUMN `source_requirement_id` BIGINT UNSIGNED NULL COMMENT ''组件工单来源的父工单用料需求行''');
PREPARE lamp_stmt FROM @lamp_ddl; EXECUTE lamp_stmt; DEALLOCATE PREPARE lamp_stmt;

SET @lamp_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=@lamp_schema AND table_name='mfg_work_order' AND column_name='snapshot_json'),'DO 0','ALTER TABLE `mfg_work_order` ADD COLUMN `snapshot_json` LONGTEXT NULL COMMENT ''下达时冻结的BOM/路线/参数/检验依据快照''');
PREPARE lamp_stmt FROM @lamp_ddl; EXECUTE lamp_stmt; DEALLOCATE PREPARE lamp_stmt;

SET @lamp_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=@lamp_schema AND table_name='mfg_work_order' AND column_name='snapshot_hash'),'DO 0','ALTER TABLE `mfg_work_order` ADD COLUMN `snapshot_hash` VARCHAR(64) NULL COMMENT ''快照摘要哈希''');
PREPARE lamp_stmt FROM @lamp_ddl; EXECUTE lamp_stmt; DEALLOCATE PREPARE lamp_stmt;

SET @lamp_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=@lamp_schema AND table_name='mfg_work_order' AND column_name='snapshot_time'),'DO 0','ALTER TABLE `mfg_work_order` ADD COLUMN `snapshot_time` DATETIME NULL COMMENT ''快照冻结时间''');
PREPARE lamp_stmt FROM @lamp_ddl; EXECUTE lamp_stmt; DEALLOCATE PREPARE lamp_stmt;

SET @lamp_ddl=IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=@lamp_schema AND table_name='mfg_work_order' AND index_name='idx_mfg_work_order_parent'),'DO 0','ALTER TABLE mfg_work_order ADD INDEX idx_mfg_work_order_parent (parent_work_order_id)');
PREPARE lamp_stmt FROM @lamp_ddl; EXECUTE lamp_stmt; DEALLOCATE PREPARE lamp_stmt;

SET @lamp_ddl=IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=@lamp_schema AND table_name='mfg_work_order' AND index_name='idx_mfg_work_order_root'),'DO 0','ALTER TABLE mfg_work_order ADD INDEX idx_mfg_work_order_root (root_work_order_id)');
PREPARE lamp_stmt FROM @lamp_ddl; EXECUTE lamp_stmt; DEALLOCATE PREPARE lamp_stmt;

-- ---------- 3. 工单用料：供给方式与组件工单关联 ----------
SET @lamp_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=@lamp_schema AND table_name='mfg_work_order_material' AND column_name='supply_type'),'DO 0','ALTER TABLE `mfg_work_order_material` ADD COLUMN `supply_type` VARCHAR(24) NOT NULL DEFAULT ''STOCK'' COMMENT ''STOCK:库存领用，COMPONENT_WORK_ORDER:组件工单补产，PURCHASE:采购补货''');
PREPARE lamp_stmt FROM @lamp_ddl; EXECUTE lamp_stmt; DEALLOCATE PREPARE lamp_stmt;

SET @lamp_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=@lamp_schema AND table_name='mfg_work_order_material' AND column_name='source_work_order_id'),'DO 0','ALTER TABLE `mfg_work_order_material` ADD COLUMN `source_work_order_id` BIGINT UNSIGNED NULL COMMENT ''该需求对应的组件工单''');
PREPARE lamp_stmt FROM @lamp_ddl; EXECUTE lamp_stmt; DEALLOCATE PREPARE lamp_stmt;

-- ---------- 4. 库存：线边库按工单占用 ----------
SET @lamp_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=@lamp_schema AND table_name='wms_inventory' AND column_name='work_order_id'),'DO 0','ALTER TABLE `wms_inventory` ADD COLUMN `work_order_id` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT ''线边库按工单占用；0 表示公共库存''');
PREPARE lamp_stmt FROM @lamp_ddl; EXECUTE lamp_stmt; DEALLOCATE PREPARE lamp_stmt;

SET @lamp_ddl=IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=@lamp_schema AND table_name='wms_inventory' AND index_name='uk_wms_inventory_whlmb'),'ALTER TABLE wms_inventory DROP INDEX uk_wms_inventory_whlmb','DO 0');
PREPARE lamp_stmt FROM @lamp_ddl; EXECUTE lamp_stmt; DEALLOCATE PREPARE lamp_stmt;

SET @lamp_ddl=IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=@lamp_schema AND table_name='wms_inventory' AND index_name='uk_wms_inventory_whlmb_wo'),'DO 0','ALTER TABLE wms_inventory ADD UNIQUE KEY uk_wms_inventory_whlmb_wo (warehouse_id, location_id, material_id, batch_id, work_order_id)');
PREPARE lamp_stmt FROM @lamp_ddl; EXECUTE lamp_stmt; DEALLOCATE PREPARE lamp_stmt;

SET @lamp_ddl=IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=@lamp_schema AND table_name='wms_inventory' AND index_name='idx_wms_inventory_work_order_id'),'DO 0','ALTER TABLE wms_inventory ADD INDEX idx_wms_inventory_work_order_id (work_order_id)');
PREPARE lamp_stmt FROM @lamp_ddl; EXECUTE lamp_stmt; DEALLOCATE PREPARE lamp_stmt;

-- ---------- 5. 库存流水：位置维度与配对转移 ----------
SET @lamp_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=@lamp_schema AND table_name='wms_stock_movement' AND column_name='warehouse_id'),'DO 0','ALTER TABLE `wms_stock_movement` ADD COLUMN `warehouse_id` BIGINT UNSIGNED NULL COMMENT ''发生仓库''');
PREPARE lamp_stmt FROM @lamp_ddl; EXECUTE lamp_stmt; DEALLOCATE PREPARE lamp_stmt;

SET @lamp_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=@lamp_schema AND table_name='wms_stock_movement' AND column_name='location_id'),'DO 0','ALTER TABLE `wms_stock_movement` ADD COLUMN `location_id` BIGINT UNSIGNED NULL COMMENT ''发生库位''');
PREPARE lamp_stmt FROM @lamp_ddl; EXECUTE lamp_stmt; DEALLOCATE PREPARE lamp_stmt;

SET @lamp_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=@lamp_schema AND table_name='wms_stock_movement' AND column_name='work_order_id'),'DO 0','ALTER TABLE `wms_stock_movement` ADD COLUMN `work_order_id` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT ''关联工单，0 表示公共''');
PREPARE lamp_stmt FROM @lamp_ddl; EXECUTE lamp_stmt; DEALLOCATE PREPARE lamp_stmt;

SET @lamp_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=@lamp_schema AND table_name='wms_stock_movement' AND column_name='pair_no'),'DO 0','ALTER TABLE `wms_stock_movement` ADD COLUMN `pair_no` VARCHAR(64) NULL COMMENT ''成对转移号：同批次移库的出入两笔共享''');
PREPARE lamp_stmt FROM @lamp_ddl; EXECUTE lamp_stmt; DEALLOCATE PREPARE lamp_stmt;

SET @lamp_ddl=IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=@lamp_schema AND table_name='wms_stock_movement' AND index_name='idx_wms_stock_movement_pair_no'),'DO 0','ALTER TABLE wms_stock_movement ADD INDEX idx_wms_stock_movement_pair_no (pair_no)');
PREPARE lamp_stmt FROM @lamp_ddl; EXECUTE lamp_stmt; DEALLOCATE PREPARE lamp_stmt;

SET @lamp_ddl=IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=@lamp_schema AND table_name='wms_stock_movement' AND index_name='idx_wms_stock_movement_work_order_id'),'DO 0','ALTER TABLE wms_stock_movement ADD INDEX idx_wms_stock_movement_work_order_id (work_order_id)');
PREPARE lamp_stmt FROM @lamp_ddl; EXECUTE lamp_stmt; DEALLOCATE PREPARE lamp_stmt;

-- ---------- 6. 出库单：销售发运关联经销商 ----------
SET @lamp_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=@lamp_schema AND table_name='wms_outbound_order' AND column_name='customer_id'),'DO 0','ALTER TABLE `wms_outbound_order` ADD COLUMN `customer_id` BIGINT UNSIGNED NULL COMMENT ''销售出库的经销商客户''');
PREPARE lamp_stmt FROM @lamp_ddl; EXECUTE lamp_stmt; DEALLOCATE PREPARE lamp_stmt;

SET @lamp_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=@lamp_schema AND table_name='wms_outbound_order' AND column_name='source_production_order_id'),'DO 0','ALTER TABLE `wms_outbound_order` ADD COLUMN `source_production_order_id` BIGINT UNSIGNED NULL COMMENT ''销售出库来源生产订单，用于全程追溯''');
PREPARE lamp_stmt FROM @lamp_ddl; EXECUTE lamp_stmt; DEALLOCATE PREPARE lamp_stmt;

SET @lamp_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=@lamp_schema AND table_name='wms_outbound_order' AND column_name='inspection_id'),'DO 0','ALTER TABLE `wms_outbound_order` ADD COLUMN `inspection_id` BIGINT UNSIGNED NULL COMMENT ''销售发运前出货检验单''');
PREPARE lamp_stmt FROM @lamp_ddl; EXECUTE lamp_stmt; DEALLOCATE PREPARE lamp_stmt;

-- ---------- 7. 班组日期排班：周历不等于人员排班 ----------
CREATE TABLE IF NOT EXISTS `org_team_schedule` (
  `schedule_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '排班主键',
  `schedule_no` VARCHAR(64) DEFAULT NULL COMMENT '排班编号',
  `team_id` BIGINT UNSIGNED NOT NULL COMMENT '班组',
  `line_id` BIGINT UNSIGNED NOT NULL COMMENT '产线',
  `shift_id` BIGINT UNSIGNED NOT NULL COMMENT '班次',
  `work_date` DATE NOT NULL COMMENT '排班日期',
  `planned_headcount` INT NOT NULL DEFAULT 1 COMMENT '计划出勤人数',
  `status` VARCHAR(32) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT:草稿，RELEASED:已生效，CLOSED:已关闭',
  `remark` VARCHAR(500) DEFAULT NULL COMMENT '备注',
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `created_by` BIGINT UNSIGNED DEFAULT NULL,
  `created_time` DATETIME DEFAULT NULL,
  `updated_by` BIGINT UNSIGNED DEFAULT NULL,
  `updated_time` DATETIME DEFAULT NULL,
  `deleted_flag` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`schedule_id`),
  UNIQUE KEY `uk_org_team_schedule` (`team_id`,`line_id`,`shift_id`,`work_date`),
  KEY `idx_org_team_schedule_date` (`work_date`),
  KEY `idx_org_team_schedule_line` (`line_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='班组到日期产线的岗位排班';

-- ---------- 8. 接口权限（PC页面尚未提供，不发布无法打开的导航菜单） ----------
START TRANSACTION;
INSERT INTO sys_menu(parent_id,menu_name,menu_type,permission_code,route_path,remark,created_time)
SELECT NULL,x.label,'BUTTON',x.code,NULL,'LAMP_EXECUTION_V1: 接口权限，业务执行仍受门禁保护',NOW()
FROM (
 SELECT '班组排班查询' label,'org:team-schedule:view' code UNION ALL
 SELECT '班组排班新增','org:team-schedule:create' UNION ALL
 SELECT '班组排班修改','org:team-schedule:update' UNION ALL
 SELECT '班组排班删除','org:team-schedule:delete' UNION ALL
 SELECT '班组排班生效','org:team-schedule:release' UNION ALL
 SELECT '销售发货查询','wms:sales-outbound:view' UNION ALL
 SELECT '销售发货生成','wms:sales-outbound:create' UNION ALL
 SELECT '销售发货确认','wms:sales-outbound:confirm' UNION ALL
 SELECT '销售出货检验','wms:sales-outbound:inspection'
) x
WHERE NOT EXISTS(SELECT 1 FROM sys_menu m WHERE m.permission_code=x.code);
UPDATE sys_menu SET menu_type='BUTTON',route_path=NULL
WHERE permission_code='org:team-schedule:view' AND remark LIKE 'LAMP_EXECUTION_V1:%';
INSERT INTO sys_role_menu(role_id,menu_id,created_time,deleted_flag)
SELECT r.role_id,m.menu_id,NOW(),0 FROM sys_role r JOIN sys_menu m
 ON m.remark LIKE 'LAMP_EXECUTION_V1:%' AND m.deleted_flag=0
WHERE r.deleted_flag=0 AND (
 r.role_code IN ('SUPER_ADMIN','MES_MANAGER')
 OR (r.role_code='PLAN_SCHEDULER' AND m.permission_code LIKE 'org:team-schedule:%')
 OR (r.role_code='WAREHOUSE_MANAGER' AND m.permission_code LIKE 'wms:sales-outbound:%' AND m.permission_code<>'wms:sales-outbound:inspection')
 OR (r.role_code='QUALITY_ENGINEER' AND m.permission_code IN ('wms:sales-outbound:view','wms:sales-outbound:inspection'))
)
AND NOT EXISTS(SELECT 1 FROM sys_role_menu x WHERE x.role_id=r.role_id AND x.menu_id=m.menu_id);
COMMIT;
