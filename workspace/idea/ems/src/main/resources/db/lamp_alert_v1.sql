-- LAMP_ALERT_V1：主动预警中心（规则 / 事件 / 自动动作 / 扫描台账）。
--
-- 背景：现有 ai_anomaly（检验超期、工单停滞、设备故障）与 ai_automation（库存低于安全值）
-- 各自维护一套硬编码阈值，既不能覆盖"原料、维修任务"等更多会影响生产进度的指标，
-- 也不能由使用者自行修改限度。本迁移把"监控什么、限度多少、命中后自动做什么"统一成
-- 可配置规则，并让同一事件在条件恢复后自动复核关闭。
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

-- 1) 预警规则：一条规则 = 一个指标 + 一个适用范围 + 一组限度 + 一组自动动作。
CREATE TABLE IF NOT EXISTS `ai_alert_rule` (
  `rule_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '规则ID',
  `rule_no` VARCHAR(64) NOT NULL COMMENT '规则编号',
  `rule_name` VARCHAR(128) NOT NULL COMMENT '规则名称',
  `metric_code` VARCHAR(48) NOT NULL COMMENT '指标编码，见 AlertMetricCatalog',
  `scope_type` VARCHAR(24) NOT NULL DEFAULT 'ALL' COMMENT '适用范围：ALL/MATERIAL/WAREHOUSE_TYPE/EQUIPMENT/LINE',
  `scope_value` VARCHAR(128) DEFAULT NULL COMMENT '适用范围取值：物料ID或编码、仓库类型、设备ID或产线ID',
  `comparator` VARCHAR(8) NOT NULL DEFAULT 'LT' COMMENT '比较方式：LT/LTE/GT/GTE',
  `warning_threshold` DECIMAL(18,4) DEFAULT NULL COMMENT '预警限度（达到即 WARNING）',
  `critical_threshold` DECIMAL(18,4) DEFAULT NULL COMMENT '严重限度（达到即 CRITICAL）',
  `threshold_unit` VARCHAR(16) DEFAULT NULL COMMENT '限度单位：QUANTITY/HOURS/COUNT/PERCENT/DAYS',
  `auto_actions` VARCHAR(500) DEFAULT NULL COMMENT '命中后自动动作，逗号分隔：NOTIFY/REPLENISH/EXCEPTION/REPAIR',
  `acknowledgment_required` TINYINT NOT NULL DEFAULT 1 COMMENT '是否要求责任人确认',
  `interval_minutes` INT NOT NULL DEFAULT 5 COMMENT '扫描间隔（分钟）',
  `cooldown_minutes` INT NOT NULL DEFAULT 30 COMMENT '同一事件重复提醒冷却（分钟）',
  `status` VARCHAR(24) NOT NULL DEFAULT 'ENABLED' COMMENT '状态：ENABLED/PAUSED/DISABLED',
  `last_scan_time` DATETIME DEFAULT NULL COMMENT '上次扫描时间',
  `hit_count` INT NOT NULL DEFAULT 0 COMMENT '累计命中次数',
  `remark` VARCHAR(500) DEFAULT NULL COMMENT '备注',
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `created_by` BIGINT DEFAULT NULL,
  `created_time` DATETIME DEFAULT NULL,
  `updated_by` BIGINT DEFAULT NULL,
  `updated_time` DATETIME DEFAULT NULL,
  `deleted_flag` TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
  PRIMARY KEY (`rule_id`),
  UNIQUE KEY `uk_ai_alert_rule_no` (`rule_no`),
  KEY `idx_ai_alert_rule_metric` (`metric_code`, `status`, `deleted_flag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='预警规则';

-- 2) 预警事件：同一规则 + 同一对象只保留一条未关闭事件，条件恢复后自动关闭。
CREATE TABLE IF NOT EXISTS `ai_alert_event` (
  `alert_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '事件ID',
  `alert_no` VARCHAR(96) NOT NULL COMMENT '事件编号（规则+对象幂等键）',
  `rule_id` BIGINT DEFAULT NULL COMMENT '来源规则',
  `metric_code` VARCHAR(48) NOT NULL COMMENT '指标编码',
  `scope_type` VARCHAR(24) DEFAULT NULL COMMENT '适用范围类型',
  `scope_key` VARCHAR(128) DEFAULT NULL COMMENT '对象键：物料ID、设备ID等',
  `severity` VARCHAR(16) NOT NULL DEFAULT 'WARNING' COMMENT '级别：INFO/WARNING/CRITICAL',
  `status` VARCHAR(24) NOT NULL DEFAULT 'OPEN' COMMENT '状态：OPEN/ACKNOWLEDGED/CLOSED',
  `title` VARCHAR(160) NOT NULL COMMENT '标题',
  `summary` VARCHAR(1000) DEFAULT NULL COMMENT '说明',
  `current_value` DECIMAL(18,4) DEFAULT NULL COMMENT '当前指标值',
  `threshold_value` DECIMAL(18,4) DEFAULT NULL COMMENT '触发时使用的限度',
  `threshold_unit` VARCHAR(16) DEFAULT NULL COMMENT '限度单位',
  `biz_type` VARCHAR(48) DEFAULT NULL COMMENT '业务对象类型',
  `biz_id` BIGINT DEFAULT NULL COMMENT '业务对象ID',
  `biz_no` VARCHAR(64) DEFAULT NULL COMMENT '业务对象编号',
  `route_path` VARCHAR(200) DEFAULT NULL COMMENT '前端处理入口',
  `first_seen_time` DATETIME DEFAULT NULL COMMENT '首次发现时间',
  `last_seen_time` DATETIME DEFAULT NULL COMMENT '最近一次仍然命中时间',
  `last_notified_time` DATETIME DEFAULT NULL COMMENT '最近一次发出提醒的时间，用于冷却去重',
  `occurrence_count` INT NOT NULL DEFAULT 1 COMMENT '累计命中轮次',
  `acknowledged_by` BIGINT DEFAULT NULL COMMENT '确认人',
  `acknowledged_time` DATETIME DEFAULT NULL COMMENT '确认时间',
  `closed_by` BIGINT DEFAULT NULL COMMENT '关闭人',
  `closed_time` DATETIME DEFAULT NULL COMMENT '关闭时间',
  `close_reason` VARCHAR(500) DEFAULT NULL COMMENT '关闭原因',
  `handled_note` VARCHAR(500) DEFAULT NULL COMMENT '人工处理说明',
  `remark` VARCHAR(500) DEFAULT NULL COMMENT '备注',
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `created_by` BIGINT DEFAULT NULL,
  `created_time` DATETIME DEFAULT NULL,
  `updated_by` BIGINT DEFAULT NULL,
  `updated_time` DATETIME DEFAULT NULL,
  `deleted_flag` TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
  PRIMARY KEY (`alert_id`),
  UNIQUE KEY `uk_ai_alert_event_no` (`alert_no`),
  KEY `idx_ai_alert_event_status` (`status`, `severity`, `deleted_flag`),
  KEY `idx_ai_alert_event_rule` (`rule_id`, `status`),
  KEY `idx_ai_alert_event_metric` (`metric_code`, `status`),
  KEY `idx_ai_alert_event_biz` (`biz_type`, `biz_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='预警事件';

-- 3) 自动动作流水：一条事件命中的每个自动步骤都留痕，便于回答"系统替我做了什么"。
CREATE TABLE IF NOT EXISTS `ai_alert_action` (
  `action_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '动作ID',
  `alert_id` BIGINT NOT NULL COMMENT '所属事件',
  `step_no` INT NOT NULL DEFAULT 1 COMMENT '步骤序号',
  `action_type` VARCHAR(48) NOT NULL COMMENT '动作类型：NOTIFY/REPLENISH/EXCEPTION/REPAIR/VERIFY',
  `action_status` VARCHAR(24) NOT NULL DEFAULT 'SUCCEEDED' COMMENT '执行结果：SUCCEEDED/FAILED/SKIPPED',
  `target_type` VARCHAR(48) DEFAULT NULL COMMENT '目标对象类型',
  `target_no` VARCHAR(96) DEFAULT NULL COMMENT '目标对象编号',
  `message` VARCHAR(500) DEFAULT NULL COMMENT '执行说明',
  `biz_no` VARCHAR(64) DEFAULT NULL COMMENT '产生的业务单号',
  `remark` VARCHAR(500) DEFAULT NULL COMMENT '备注',
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `created_by` BIGINT DEFAULT NULL,
  `created_time` DATETIME DEFAULT NULL,
  `updated_by` BIGINT DEFAULT NULL,
  `updated_time` DATETIME DEFAULT NULL,
  `deleted_flag` TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
  PRIMARY KEY (`action_id`),
  KEY `idx_ai_alert_action_alert` (`alert_id`, `step_no`),
  KEY `idx_ai_alert_action_type` (`action_type`, `action_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='预警自动动作流水';

-- 4) 扫描台账：每一轮扫描一条，记录评估了多少规则、开了多少事件、自动关了多少。
CREATE TABLE IF NOT EXISTS `ai_alert_scan` (
  `scan_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '扫描ID',
  `scan_no` VARCHAR(96) NOT NULL COMMENT '扫描编号（按分钟窗口幂等）',
  `trigger_type` VARCHAR(24) NOT NULL DEFAULT 'SCHEDULE' COMMENT '触发方式：SCHEDULE/MANUAL',
  `status` VARCHAR(24) NOT NULL DEFAULT 'RUNNING' COMMENT '状态：RUNNING/SUCCEEDED/FAILED',
  `rule_count` INT NOT NULL DEFAULT 0 COMMENT '本轮评估的规则数',
  `evaluated_count` INT NOT NULL DEFAULT 0 COMMENT '评估的对象数',
  `opened_count` INT NOT NULL DEFAULT 0 COMMENT '新开事件数',
  `escalated_count` INT NOT NULL DEFAULT 0 COMMENT '升级事件数',
  `repeated_count` INT NOT NULL DEFAULT 0 COMMENT '重复命中事件数',
  `closed_count` INT NOT NULL DEFAULT 0 COMMENT '自动关闭事件数',
  `elapsed_ms` BIGINT NOT NULL DEFAULT 0 COMMENT '耗时毫秒',
  `summary` VARCHAR(1000) DEFAULT NULL COMMENT '本轮摘要',
  `error_message` VARCHAR(1000) DEFAULT NULL COMMENT '失败原因',
  `remark` VARCHAR(500) DEFAULT NULL COMMENT '备注',
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `created_by` BIGINT DEFAULT NULL,
  `created_time` DATETIME DEFAULT NULL,
  `updated_by` BIGINT DEFAULT NULL,
  `updated_time` DATETIME DEFAULT NULL,
  `deleted_flag` TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
  PRIMARY KEY (`scan_id`),
  UNIQUE KEY `uk_ai_alert_scan_no` (`scan_no`),
  KEY `idx_ai_alert_scan_time` (`created_time`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='预警扫描台账';

-- 权限：预警中心属于智能助手能力，沿用 ai:assistant:use 作为入口权限，
-- 另外单独登记配置与处置权限，避免所有能看 AI 的人都能改限度。
START TRANSACTION;

INSERT INTO sys_menu (parent_id, menu_name, menu_type, permission_code, route_path, remark, created_time)
SELECT NULL, '预警规则配置', 'BUTTON', 'ai:alert:configure', NULL,
       'LAMP_ALERT_V1: 新增/修改/删除预警规则与限度', NOW()
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM sys_menu WHERE permission_code = 'ai:alert:configure' AND deleted_flag = 0
);

INSERT INTO sys_menu (parent_id, menu_name, menu_type, permission_code, route_path, remark, created_time)
SELECT NULL, '预警事件处置', 'BUTTON', 'ai:alert:handle', NULL,
       'LAMP_ALERT_V1: 确认、关闭、手动扫描预警事件', NOW()
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM sys_menu WHERE permission_code = 'ai:alert:handle' AND deleted_flag = 0
);

INSERT INTO sys_role_menu (role_id, menu_id, created_time, deleted_flag)
SELECT r.role_id, m.menu_id, NOW(), 0
FROM sys_role r
JOIN sys_menu m ON m.permission_code IN ('ai:alert:configure', 'ai:alert:handle') AND m.deleted_flag = 0
WHERE r.role_code IN ('SUPER_ADMIN', 'MES_MANAGER', 'PLAN_SCHEDULER', 'WAREHOUSE_MANAGER', 'QUALITY_ENGINEER')
  AND r.deleted_flag = 0
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_menu rm
      WHERE rm.role_id = r.role_id AND rm.menu_id = m.menu_id AND rm.deleted_flag = 0
  );

COMMIT;
