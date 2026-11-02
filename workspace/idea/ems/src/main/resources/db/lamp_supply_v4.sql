-- ============================================================================
-- 台灯 MES P2C：组件补产拆分与供给来源明细
-- 目标：一条组件需求在有效日历产能窗口内排不下时，可以拆成多张组件补产计划/工单，
--       每张补产单的供给数量必须能被追溯到原需求；不改变执行门禁，也不代表 P2 已验收。
-- 幂等：全部使用 information_schema 判断 + CREATE TABLE IF NOT EXISTS，可重复执行。
-- 前置：lamp_supply_v3.sql 已执行（89 张表）。
-- ============================================================================
SET NAMES utf8mb4;
-- 仅限独立台灯库；请通过受保护入口执行，禁止使用 mysql --force 忽略错误。
SET @lamp_scope_sql=IF(DATABASE() REGEXP '^mes_lamp_[A-Za-z0-9_]+$', 'DO 0', 'SELECT * FROM LAMP_ERROR_ISOLATED_DATABASE_REQUIRED');
PREPARE lamp_scope_stmt FROM @lamp_scope_sql;
EXECUTE lamp_scope_stmt;
DEALLOCATE PREPARE lamp_scope_stmt;

SET @lamp_schema = DATABASE();

-- ---------------------------------------------------------------------------
-- 1. 需求供给来源明细：一条需求可以对应多张组件补产单（多来源、多批次拆分）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `mfg_supply_source` (
  `source_id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '供给来源主键。',
  `source_no` varchar(96) NOT NULL COMMENT '供给来源单号，同一需求下的拆分序号。',
  `requirement_id` bigint unsigned NOT NULL COMMENT '被供给的工单用料需求 ID。',
  `work_order_id` bigint unsigned NOT NULL COMMENT '提供供给的组件工单 ID。',
  `plan_id` bigint unsigned DEFAULT NULL COMMENT '提供供给的组件补产计划 ID。',
  `material_id` bigint unsigned NOT NULL COMMENT '供给物料 ID。',
  `supply_type` varchar(24) NOT NULL DEFAULT 'COMPONENT_WORK_ORDER' COMMENT '供给类型：COMPONENT_WORK_ORDER。',
  `planned_quantity` decimal(18,4) NOT NULL DEFAULT '0.0000' COMMENT '该来源承诺的供给数量。',
  `sequence_no` int NOT NULL DEFAULT 1 COMMENT '同一需求下的拆分序号，从 1 开始。',
  `status` varchar(24) NOT NULL DEFAULT 'PLANNED' COMMENT 'PLANNED:已计划，RELEASED:已下达，CLOSED:已关闭。',
  `request_key` varchar(96) DEFAULT NULL COMMENT '生成请求键，用于命令幂等追溯。',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `created_by` bigint unsigned DEFAULT NULL COMMENT '创建人',
  `created_time` datetime DEFAULT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned DEFAULT NULL COMMENT '更新人',
  `updated_time` datetime DEFAULT NULL COMMENT '更新时间',
  `deleted_flag` tinyint NOT NULL DEFAULT '0' COMMENT '软删除标记 0未删 1已删',
  PRIMARY KEY (`source_id`),
  UNIQUE KEY `uk_mfg_supply_source_no` (`source_no`),
  KEY `idx_mfg_supply_source_requirement_id` (`requirement_id`),
  KEY `idx_mfg_supply_source_work_order_id` (`work_order_id`),
  KEY `idx_mfg_supply_source_plan_id` (`plan_id`),
  KEY `idx_mfg_supply_source_material_id` (`material_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='需求供给来源明细表';

-- ---------------------------------------------------------------------------
-- 2. 组件计划时间窗口依据：记录计划排产所依据的产线/路线与产能说明
-- ---------------------------------------------------------------------------
SET @lamp_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns
        WHERE table_schema=@lamp_schema AND table_name='mfg_production_plan' AND column_name='capacity_basis'),
    'DO 0',
    'ALTER TABLE `mfg_production_plan` ADD COLUMN `capacity_basis` varchar(500) DEFAULT NULL COMMENT ''可执行窗口计算依据：产线周历、班次、日产能与批次占用'' AFTER `remark`');
PREPARE lamp_stmt FROM @lamp_ddl;
EXECUTE lamp_stmt;
DEALLOCATE PREPARE lamp_stmt;

-- ---------------------------------------------------------------------------
-- 3. 接口权限（PC页面尚未提供，不发布无法打开的导航菜单）
-- ---------------------------------------------------------------------------
START TRANSACTION;
INSERT INTO sys_menu(parent_id,menu_name,menu_type,permission_code,route_path,remark,created_time)
SELECT NULL,'供给来源明细查询','BUTTON','mfg:supply-source:view',NULL,'LAMP_SUPPLY_V4: 接口权限，补产拆分仍受执行门禁保护',NOW()
FROM DUAL
WHERE NOT EXISTS(SELECT 1 FROM sys_menu m WHERE m.permission_code='mfg:supply-source:view');
INSERT INTO sys_role_menu(role_id,menu_id,created_time,deleted_flag)
SELECT r.role_id,m.menu_id,NOW(),0 FROM sys_role r JOIN sys_menu m
 ON m.remark LIKE 'LAMP_SUPPLY_V4:%' AND m.deleted_flag=0
WHERE r.deleted_flag=0 AND r.role_code IN ('SUPER_ADMIN','MES_MANAGER','PLAN_SCHEDULER','WAREHOUSE_MANAGER')
AND NOT EXISTS(SELECT 1 FROM sys_role_menu x WHERE x.role_id=r.role_id AND x.menu_id=m.menu_id);
COMMIT;
