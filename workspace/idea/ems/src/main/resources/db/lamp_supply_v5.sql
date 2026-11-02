-- LAMP_SUPPLY_V5：生产与仓储命令的统一幂等登记。
--
-- 背景：P2B 已为"生成工单"建立按请求键的幂等登记（mfg_generate_request）。
-- 领料单生成、领料转线边确认仍是明细级重放，无法做到"同键同参数返回原结果、
-- 同键改参数必须拒绝"。本迁移新增通用命令登记表，让这三类命令走同一口径。
--
-- 隔离要求：只在演练库执行，不得作用于 mes_local 或共享 mes。
SET NAMES utf8mb4;

SET @lamp_scope_sql = IF(DATABASE() REGEXP '^mes_lamp_[A-Za-z0-9_]+$',
    'DO 0',
    'SELECT * FROM LAMP_ERROR_ISOLATED_DATABASE_REQUIRED');
PREPARE lamp_scope_stmt FROM @lamp_scope_sql;
EXECUTE lamp_scope_stmt;
DEALLOCATE PREPARE lamp_scope_stmt;

SET @lamp_schema = DATABASE();

CREATE TABLE IF NOT EXISTS `mfg_command_request` (
  `request_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '命令请求ID',
  `request_key` VARCHAR(96) NOT NULL COMMENT '调用方提供的幂等请求键',
  `command` VARCHAR(48) NOT NULL COMMENT '命令类型：MATERIAL_ISSUE_GENERATE/LINE_SIDE_TRANSFER',
  `aggregate_type` VARCHAR(32) NOT NULL COMMENT '聚合类型：MFG_WORK_ORDER/WMS_OUTBOUND_ORDER',
  `aggregate_id` BIGINT NOT NULL COMMENT '命令作用的聚合ID',
  `request_digest` VARCHAR(64) NOT NULL COMMENT '请求参数摘要，用于识别同键改参数',
  `result_id` BIGINT NOT NULL COMMENT '命令结果主键，重放时返回',
  `operator_id` BIGINT DEFAULT NULL COMMENT '发起命令的操作人',
  `status` VARCHAR(24) NOT NULL DEFAULT 'COMPLETED' COMMENT '命令状态：COMPLETED',
  `remark` VARCHAR(500) DEFAULT NULL COMMENT '备注',
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `created_by` BIGINT DEFAULT NULL,
  `created_time` DATETIME DEFAULT NULL,
  `updated_by` BIGINT DEFAULT NULL,
  `updated_time` DATETIME DEFAULT NULL,
  `deleted_flag` INT NOT NULL DEFAULT 0,
  PRIMARY KEY (`request_id`),
  UNIQUE KEY `uk_mfg_command_request_key` (`request_key`, `command`),
  KEY `idx_mfg_command_request_aggregate` (`aggregate_type`, `aggregate_id`),
  KEY `idx_mfg_command_request_result` (`result_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='生产与仓储命令幂等登记';

-- 权限：命令登记表只随业务命令写入，这里只开放只读查询权限。
START TRANSACTION;

INSERT INTO sys_menu (parent_id, menu_name, menu_type, permission_code, route_path, remark, created_time)
SELECT NULL, '命令请求幂等查询', 'BUTTON', 'mfg:command-request:view', NULL,
       'LAMP_SUPPLY_V5: 命令请求幂等登记只读查询', NOW()
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM sys_menu WHERE permission_code = 'mfg:command-request:view' AND deleted_flag = 0
);

INSERT INTO sys_role_menu (role_id, menu_id, created_time, deleted_flag)
SELECT r.role_id, m.menu_id, NOW(), 0
FROM sys_role r
JOIN sys_menu m ON m.permission_code = 'mfg:command-request:view' AND m.deleted_flag = 0
WHERE r.role_code IN ('SUPER_ADMIN', 'MES_MANAGER', 'PLAN_SCHEDULER', 'WAREHOUSE_MANAGER')
  AND r.deleted_flag = 0
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_menu rm
      WHERE rm.role_id = r.role_id AND rm.menu_id = m.menu_id AND rm.deleted_flag = 0
  );

COMMIT;
