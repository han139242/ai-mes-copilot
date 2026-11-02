-- 台灯供给分配 v3：把"合格库存分析"升级为可承诺的库存预留。
-- 只增量加列/建表，不改历史业务数量；可重复执行。
SET NAMES utf8mb4;
-- 仅限独立台灯库；请通过受保护入口执行，禁止使用 mysql --force 忽略错误。
SET @lamp_scope_sql=IF(DATABASE() REGEXP '^mes_lamp_[A-Za-z0-9_]+$', 'DO 0', 'SELECT * FROM LAMP_ERROR_ISOLATED_DATABASE_REQUIRED');
PREPARE lamp_scope_stmt FROM @lamp_scope_sql;
EXECUTE lamp_scope_stmt;
DEALLOCATE PREPARE lamp_scope_stmt;

SET @lamp_schema=DATABASE();

-- ---------- 1. 工单用料：预留数量与缺口口径分离 ----------
-- shortage_quantity 表示"尚未被有效供给覆盖的数量"，reserved_quantity 表示"已承诺但未发到工单的数量"。
-- 两者都不代表已经发料；只有 issued_quantity 才代表实物已到工单。
SET @lamp_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=@lamp_schema AND table_name='mfg_work_order_material' AND column_name='reserved_quantity'),'DO 0','ALTER TABLE `mfg_work_order_material` ADD COLUMN `reserved_quantity` DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT ''已承诺的库存预留数量；不等于已发料''');
PREPARE lamp_stmt FROM @lamp_ddl; EXECUTE lamp_stmt; DEALLOCATE PREPARE lamp_stmt;

-- ---------- 2. 供给分配：一条需求可拆多批次、多来源 ----------
-- 替代"单一 source_work_order_id 承担完整分配"的做法；同一需求可同时存在
-- 多条库存预留（RESERVED）和一条组件补产工单（由 source_work_order_id 表达）。
CREATE TABLE IF NOT EXISTS `mfg_supply_allocation` (
  `allocation_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '供给分配主键',
  `allocation_no` VARCHAR(96) NOT NULL COMMENT '分配编号：需求+库存余额唯一，重复请求复用',
  `requirement_id` BIGINT UNSIGNED NOT NULL COMMENT '工单用料需求行',
  `work_order_id` BIGINT UNSIGNED NOT NULL COMMENT '需求所属工单',
  `material_id` BIGINT UNSIGNED NOT NULL COMMENT '物料',
  `warehouse_id` BIGINT UNSIGNED NOT NULL COMMENT '来源仓库',
  `location_id` BIGINT UNSIGNED NOT NULL COMMENT '来源库位',
  `batch_id` BIGINT UNSIGNED NOT NULL COMMENT '来源批次',
  `inventory_id` BIGINT UNSIGNED NOT NULL COMMENT '来源公共库存余额行',
  `allocated_quantity` DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '分配数量，成功后不再变化',
  `reserved_quantity` DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '仍处于预留状态的数量',
  `transferred_quantity` DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '已转为线边工单占用的数量',
  `consumed_quantity` DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '已实际消耗的数量',
  `released_quantity` DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '已释放回公共可用量的数量',
  `status` VARCHAR(24) NOT NULL DEFAULT 'RESERVED' COMMENT 'RESERVED:预留中，TRANSFERRED:已转线边，CONSUMED:已消耗，RELEASED:已释放',
  `request_key` VARCHAR(96) DEFAULT NULL COMMENT '预留业务幂等键：需求+请求键',
  `remark` VARCHAR(500) DEFAULT NULL COMMENT '备注',
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `created_by` BIGINT UNSIGNED DEFAULT NULL,
  `created_time` DATETIME DEFAULT NULL,
  `updated_by` BIGINT UNSIGNED DEFAULT NULL,
  `updated_time` DATETIME DEFAULT NULL,
  `deleted_flag` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`allocation_id`),
  UNIQUE KEY `uk_mfg_supply_allocation_no` (`allocation_no`),
  KEY `idx_mfg_supply_allocation_requirement` (`requirement_id`),
  KEY `idx_mfg_supply_allocation_work_order` (`work_order_id`),
  KEY `idx_mfg_supply_allocation_inventory` (`inventory_id`),
  KEY `idx_mfg_supply_allocation_material` (`material_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='供给分配与库存预留：合格库存只分配一次';

-- ---------- 3. 生成请求：同幂等键同请求返回原结果 ----------
CREATE TABLE IF NOT EXISTS `mfg_generate_request` (
  `request_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '生成请求主键',
  `request_key` VARCHAR(96) NOT NULL COMMENT '生成命令幂等键',
  `plan_id` BIGINT UNSIGNED NOT NULL COMMENT '来源生产计划',
  `command` VARCHAR(48) NOT NULL DEFAULT 'GENERATE_WORK_ORDER' COMMENT '命令名',
  `request_digest` VARCHAR(64) NOT NULL COMMENT '请求摘要，参数变化时拒绝复用',
  `root_work_order_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '本次生成的根工单',
  `operator_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '调用人',
  `status` VARCHAR(24) NOT NULL DEFAULT 'COMPLETED' COMMENT 'COMPLETED:已完成',
  `remark` VARCHAR(500) DEFAULT NULL COMMENT '备注',
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `created_by` BIGINT UNSIGNED DEFAULT NULL,
  `created_time` DATETIME DEFAULT NULL,
  `updated_by` BIGINT UNSIGNED DEFAULT NULL,
  `updated_time` DATETIME DEFAULT NULL,
  `deleted_flag` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`request_id`),
  UNIQUE KEY `uk_mfg_generate_request_key` (`request_key`),
  KEY `idx_mfg_generate_request_plan` (`plan_id`),
  KEY `idx_mfg_generate_request_root` (`root_work_order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='生产工单生成命令幂等记录';

-- ---------- 4. 接口权限（PC页面尚未提供，不发布无法打开的导航菜单） ----------
START TRANSACTION;
INSERT INTO sys_menu(parent_id,menu_name,menu_type,permission_code,route_path,remark,created_time)
SELECT NULL,x.label,'BUTTON',x.code,NULL,'LAMP_SUPPLY_V3: 接口权限，库存预留仍受执行门禁保护',NOW()
FROM (
 SELECT '供给分配查询' label,'mfg:supply-allocation:view' code UNION ALL
 SELECT '供给分配预留','mfg:supply-allocation:reserve' UNION ALL
 SELECT '供给分配释放','mfg:supply-allocation:release'
) x
WHERE NOT EXISTS(SELECT 1 FROM sys_menu m WHERE m.permission_code=x.code);
INSERT INTO sys_role_menu(role_id,menu_id,created_time,deleted_flag)
SELECT r.role_id,m.menu_id,NOW(),0 FROM sys_role r JOIN sys_menu m
 ON m.remark LIKE 'LAMP_SUPPLY_V3:%' AND m.deleted_flag=0
WHERE r.deleted_flag=0 AND r.role_code IN ('SUPER_ADMIN','MES_MANAGER','PLAN_SCHEDULER','WAREHOUSE_MANAGER')
AND NOT EXISTS(SELECT 1 FROM sys_role_menu x WHERE x.role_id=r.role_id AND x.menu_id=m.menu_id);
COMMIT;
