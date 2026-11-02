-- AI_AUTOMATION_V1：AI 自动化规则的配置、执行记录、步骤流水与库存风险台账。
--
-- 背景：智能助手已具备查询与单次办理能力，本迁移补齐"持续自动化"的底座。
-- 一条规则描述"监控什么、阈值多少、命中后做什么"，每次执行落一条运行记录，
-- 每个动作落一条步骤流水；同一规则重复触发按 run_key 幂等，不重复建单扣账。
--
-- 隔离要求：只在演练库执行，不得作用于 mes_local 或共享 mes。
SET NAMES utf8mb4;

SET @lamp_scope_sql = IF(DATABASE() REGEXP '^mes_lamp_[A-Za-z0-9_]+$',
    'DO 0',
    'SELECT * FROM LAMP_ERROR_ISOLATED_DATABASE_REQUIRED');
PREPARE lamp_scope_stmt FROM @lamp_scope_sql;
EXECUTE lamp_scope_stmt;
DEALLOCATE PREPARE lamp_scope_stmt;

CREATE TABLE IF NOT EXISTS `ai_automation_rule` (
  `rule_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '规则ID',
  `rule_no` VARCHAR(64) NOT NULL COMMENT '规则编号',
  `rule_name` VARCHAR(128) NOT NULL COMMENT '规则名称',
  `rule_type` VARCHAR(48) NOT NULL COMMENT '规则类型：INVENTORY_BELOW_SAFETY',
  `status` VARCHAR(24) NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT/ENABLED/PAUSED/DISABLED',
  `default_threshold` DECIMAL(18,4) DEFAULT NULL COMMENT '默认安全库存阈值',
  `config_json` LONGTEXT DEFAULT NULL COMMENT '适用范围与阈值配置JSON',
  `interval_minutes` INT NOT NULL DEFAULT 10 COMMENT '定时执行间隔（分钟）',
  `last_run_time` DATETIME DEFAULT NULL COMMENT '上次执行时间',
  `next_run_time` DATETIME DEFAULT NULL COMMENT '下次计划执行时间',
  `success_count` INT NOT NULL DEFAULT 0 COMMENT '累计成功次数',
  `fail_count` INT NOT NULL DEFAULT 0 COMMENT '累计失败次数',
  `remark` VARCHAR(500) DEFAULT NULL COMMENT '备注',
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `created_by` BIGINT DEFAULT NULL,
  `created_time` DATETIME DEFAULT NULL,
  `updated_by` BIGINT DEFAULT NULL,
  `updated_time` DATETIME DEFAULT NULL,
  `deleted_flag` TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
  PRIMARY KEY (`rule_id`),
  UNIQUE KEY `uk_ai_automation_rule_no` (`rule_no`),
  KEY `idx_ai_automation_rule_status` (`status`, `deleted_flag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI自动化规则';

CREATE TABLE IF NOT EXISTS `ai_automation_run` (
  `run_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '运行ID',
  `run_no` VARCHAR(64) NOT NULL COMMENT '运行编号',
  `run_key` VARCHAR(128) NOT NULL COMMENT '幂等键，同一触发不重复执行',
  `rule_id` BIGINT NOT NULL COMMENT '规则ID',
  `rule_no` VARCHAR(64) DEFAULT NULL COMMENT '规则编号快照',
  `rule_name` VARCHAR(128) DEFAULT NULL COMMENT '规则名称快照',
  `trigger_type` VARCHAR(24) NOT NULL COMMENT '触发方式：SCHEDULE/MANUAL',
  `status` VARCHAR(24) NOT NULL COMMENT '状态：RUNNING/SUCCEEDED/FAILED/SKIPPED',
  `matched_count` INT NOT NULL DEFAULT 0 COMMENT '命中数量',
  `started_time` DATETIME DEFAULT NULL COMMENT '开始时间',
  `finished_time` DATETIME DEFAULT NULL COMMENT '结束时间',
  `elapsed_ms` BIGINT DEFAULT NULL COMMENT '耗时毫秒',
  `summary` VARCHAR(500) DEFAULT NULL COMMENT '结果摘要',
  `error_message` VARCHAR(500) DEFAULT NULL COMMENT '失败原因',
  `created_by` BIGINT DEFAULT NULL,
  `created_time` DATETIME DEFAULT NULL,
  `updated_by` BIGINT DEFAULT NULL,
  `updated_time` DATETIME DEFAULT NULL,
  `deleted_flag` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`run_id`),
  UNIQUE KEY `uk_ai_automation_run_no` (`run_no`),
  UNIQUE KEY `uk_ai_automation_run_key` (`run_key`),
  KEY `idx_ai_automation_run_rule` (`rule_id`, `started_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI自动化规则执行记录';

CREATE TABLE IF NOT EXISTS `ai_automation_step` (
  `step_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '步骤ID',
  `run_id` BIGINT NOT NULL COMMENT '运行ID',
  `step_no` INT NOT NULL COMMENT '步骤序号',
  `action_type` VARCHAR(48) NOT NULL COMMENT '动作类型',
  `target_type` VARCHAR(48) DEFAULT NULL COMMENT '目标对象类型',
  `target_no` VARCHAR(128) DEFAULT NULL COMMENT '目标对象编号',
  `status` VARCHAR(24) NOT NULL COMMENT '状态：SUCCEEDED/FAILED/SKIPPED',
  `message` VARCHAR(500) DEFAULT NULL COMMENT '结果说明',
  `biz_no` VARCHAR(64) DEFAULT NULL COMMENT '业务单据号',
  `elapsed_ms` BIGINT DEFAULT NULL COMMENT '耗时毫秒',
  `created_by` BIGINT DEFAULT NULL,
  `created_time` DATETIME DEFAULT NULL,
  `updated_by` BIGINT DEFAULT NULL,
  `updated_time` DATETIME DEFAULT NULL,
  `deleted_flag` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`step_id`),
  KEY `idx_ai_automation_step_run` (`run_id`, `step_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI自动化执行步骤流水';

CREATE TABLE IF NOT EXISTS `ai_inventory_risk` (
  `risk_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '风险ID',
  `risk_no` VARCHAR(64) NOT NULL COMMENT '风险编号',
  `rule_id` BIGINT DEFAULT NULL COMMENT '来源规则ID',
  `rule_no` VARCHAR(64) DEFAULT NULL COMMENT '来源规则编号',
  `material_id` BIGINT NOT NULL COMMENT '物料ID',
  `material_code` VARCHAR(64) DEFAULT NULL COMMENT '物料编码快照',
  `material_name` VARCHAR(128) DEFAULT NULL COMMENT '物料名称快照',
  `warehouse_type` VARCHAR(32) DEFAULT NULL COMMENT '统计的仓库类型',
  `available_quantity` DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '检出时可用量',
  `threshold_quantity` DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '阈值',
  `shortage_quantity` DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '缺口',
  `suggestion_id` BIGINT DEFAULT NULL COMMENT '关联补货建议ID',
  `status` VARCHAR(24) NOT NULL DEFAULT 'OPEN' COMMENT '状态：OPEN/CLOSED',
  `detected_time` DATETIME DEFAULT NULL COMMENT '发现时间',
  `closed_time` DATETIME DEFAULT NULL COMMENT '关闭时间',
  `remark` VARCHAR(500) DEFAULT NULL,
  `version` INT NOT NULL DEFAULT 0,
  `created_by` BIGINT DEFAULT NULL,
  `created_time` DATETIME DEFAULT NULL,
  `updated_by` BIGINT DEFAULT NULL,
  `updated_time` DATETIME DEFAULT NULL,
  `deleted_flag` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`risk_id`),
  UNIQUE KEY `uk_ai_inventory_risk_no` (`risk_no`),
  KEY `idx_ai_inventory_risk_material` (`material_id`, `status`, `deleted_flag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存风险台账';

-- 菜单登记：自动化规则页与预警中心共用 `ai:assistant:use` 作为入口权限
-- （该权限行由 lamp_rbac_v1.sql 登记，前端导航与接口鉴权也都用它）。
-- `sys_menu.permission_code` 有唯一键 uk_sys_menu_permission_code，这里不能再插一条同码记录，
-- 否则 ON DUPLICATE KEY UPDATE 会把 AI 工作台那行改成「自动化规则 /ai/automation」，
-- 两个扩展的入口就互相覆盖了。所以只登记一条不带权限码的路由记录，并按 route_path 幂等。
SET @auto_admin_user_id = (SELECT `user_id` FROM `sys_user` WHERE `username` = 'admin' AND `deleted_flag` = 0 LIMIT 1);
SET @auto_time = NOW();

INSERT INTO `sys_menu`
  (`parent_id`, `menu_name`, `menu_type`, `permission_code`, `route_path`, `remark`,
   `version`, `created_by`, `created_time`, `updated_by`, `updated_time`, `deleted_flag`)
SELECT (SELECT ai_dir.`menu_id` FROM `sys_menu` ai_dir
        WHERE ai_dir.`permission_code` = 'ai:assistant:use' AND ai_dir.`deleted_flag` = 0
        LIMIT 1),
       '自动化规则', 'MENU', NULL, '/ai/automation',
       'AI_AUTOMATION_V1: 安全库存预警自动化规则', 0,
       @auto_admin_user_id, @auto_time, @auto_admin_user_id, @auto_time, 0
FROM DUAL
WHERE NOT EXISTS (
  SELECT 1 FROM `sys_menu` existing
  WHERE existing.`route_path` = '/ai/automation' AND existing.`deleted_flag` = 0
);

-- 种子规则：原料仓安全库存低于阈值的监控（默认停用，演示时人工启用）
INSERT INTO `ai_automation_rule`
  (`rule_no`, `rule_name`, `rule_type`, `status`, `default_threshold`, `config_json`,
   `interval_minutes`, `remark`, `version`, `created_by`, `created_time`, `updated_by`, `updated_time`, `deleted_flag`)
SELECT 'RULE-SAFETY-RAW', '原料仓安全库存预警', 'INVENTORY_BELOW_SAFETY', 'PAUSED', 20.0000,
       '{"warehouseType":"RAW","materials":[]}', 10,
       '原料仓可用库存低于阈值时登记风险、生成补货建议并通知仓管；库存恢复后自动关闭风险',
       0, @auto_admin_user_id, @auto_time, @auto_admin_user_id, @auto_time, 0
FROM DUAL
WHERE NOT EXISTS (
  SELECT 1 FROM `ai_automation_rule` WHERE `rule_no` = 'RULE-SAFETY-RAW'
);
