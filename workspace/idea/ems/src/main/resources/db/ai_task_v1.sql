-- AI_TASK_V1：多步任务定义、任务实例与步骤流水。
--
-- 背景：单次办理只能覆盖"一句话一个动作"。真实车间还需要"一件事办完接着办下一件"，
-- 例如报工审核通过后自动生成入库单、入库单待确认时自动提交来料检验。
-- 本迁移补齐多步任务的底座：定义、实例、步骤三层，加幂等键、重试、断点与循环防护。
--
-- 幂等：同一触发源对同一定义只产生一个实例，靠 instance_key 唯一索引在库层拦住。
-- 恢复：进程重启时把残留 RUNNING 的实例标记为可续跑，从 resume_step_no 继续。
--
-- 隔离要求：只在演练库执行，不得作用于 mes_local 或共享 mes。
SET NAMES utf8mb4;

SET @lamp_scope_sql = IF(DATABASE() REGEXP '^mes_lamp_[A-Za-z0-9_]+$',
    'DO 0',
    'SELECT * FROM LAMP_ERROR_ISOLATED_DATABASE_REQUIRED');
PREPARE lamp_scope_stmt FROM @lamp_scope_sql;
EXECUTE lamp_scope_stmt;
DEALLOCATE PREPARE lamp_scope_stmt;

CREATE TABLE IF NOT EXISTS `ai_task_definition` (
  `definition_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '任务定义ID',
  `definition_no` VARCHAR(64) NOT NULL COMMENT '任务编号',
  `definition_name` VARCHAR(128) NOT NULL COMMENT '任务名称',
  `trigger_type` VARCHAR(32) NOT NULL DEFAULT 'MANUAL' COMMENT '触发方式：EVENT事件/SCHEDULE定时/MANUAL手动',
  `trigger_event_key` VARCHAR(128) DEFAULT NULL COMMENT '触发事件键，触发方式为EVENT时必填',
  `interval_minutes` INT NOT NULL DEFAULT 10 COMMENT '定时触发间隔分钟',
  `status` VARCHAR(32) NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT/ENABLED/PAUSED/DISABLED',
  `max_attempts` INT NOT NULL DEFAULT 2 COMMENT '单步最大尝试次数，含首次',
  `max_instances_per_hour` INT NOT NULL DEFAULT 50 COMMENT '每小时最多实例数，用于循环防护',
  `steps_json` LONGTEXT COMMENT '步骤定义JSON数组',
  `last_run_time` DATETIME DEFAULT NULL COMMENT '上次触发时间',
  `success_count` INT NOT NULL DEFAULT 0 COMMENT '成功实例数',
  `fail_count` INT NOT NULL DEFAULT 0 COMMENT '失败实例数',
  `remark` VARCHAR(512) DEFAULT NULL COMMENT '备注',
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `created_by` BIGINT DEFAULT NULL COMMENT '创建人',
  `created_time` DATETIME DEFAULT NULL COMMENT '创建时间',
  `updated_by` BIGINT DEFAULT NULL COMMENT '更新人',
  `updated_time` DATETIME DEFAULT NULL COMMENT '更新时间',
  `deleted_flag` INT NOT NULL DEFAULT 0 COMMENT '删除标记',
  PRIMARY KEY (`definition_id`),
  UNIQUE KEY `uk_ai_task_definition_no` (`definition_no`),
  KEY `idx_ai_task_definition_status` (`status`, `deleted_flag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 多步任务定义';

CREATE TABLE IF NOT EXISTS `ai_task_instance` (
  `instance_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '实例ID',
  `instance_no` VARCHAR(64) NOT NULL COMMENT '实例编号',
  `instance_key` VARCHAR(191) NOT NULL COMMENT '幂等键，同一触发源只允许一个实例',
  `definition_id` BIGINT NOT NULL COMMENT '任务定义ID',
  `definition_no` VARCHAR(64) NOT NULL COMMENT '任务编号',
  `definition_name` VARCHAR(128) NOT NULL COMMENT '任务名称',
  `trigger_type` VARCHAR(32) NOT NULL COMMENT '触发方式',
  `trigger_source` VARCHAR(255) DEFAULT NULL COMMENT '触发源描述，例如事件键或分钟窗口',
  `status` VARCHAR(32) NOT NULL DEFAULT 'RUNNING' COMMENT '状态：RUNNING/SUCCEEDED/PARTIAL_FAILED/FAILED/SKIPPED',
  `current_step_no` INT NOT NULL DEFAULT 0 COMMENT '当前执行到的步骤序号',
  `resume_step_no` INT NOT NULL DEFAULT 1 COMMENT '续跑起始步骤',
  `total_steps` INT NOT NULL DEFAULT 0 COMMENT '步骤总数',
  `attempt_count` INT NOT NULL DEFAULT 0 COMMENT '整体尝试次数',
  `started_time` DATETIME DEFAULT NULL COMMENT '开始时间',
  `finished_time` DATETIME DEFAULT NULL COMMENT '结束时间',
  `elapsed_ms` BIGINT DEFAULT NULL COMMENT '耗时毫秒',
  `summary` VARCHAR(512) DEFAULT NULL COMMENT '执行摘要',
  `error_message` VARCHAR(512) DEFAULT NULL COMMENT '失败原因',
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `created_by` BIGINT DEFAULT NULL COMMENT '创建人',
  `created_time` DATETIME DEFAULT NULL COMMENT '创建时间',
  `updated_by` BIGINT DEFAULT NULL COMMENT '更新人',
  `updated_time` DATETIME DEFAULT NULL COMMENT '更新时间',
  `deleted_flag` INT NOT NULL DEFAULT 0 COMMENT '删除标记',
  PRIMARY KEY (`instance_id`),
  UNIQUE KEY `uk_ai_task_instance_no` (`instance_no`),
  UNIQUE KEY `uk_ai_task_instance_key` (`instance_key`),
  KEY `idx_ai_task_instance_def` (`definition_id`, `started_time`),
  KEY `idx_ai_task_instance_status` (`status`, `deleted_flag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 多步任务实例';

CREATE TABLE IF NOT EXISTS `ai_task_step` (
  `step_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '步骤ID',
  `instance_id` BIGINT NOT NULL COMMENT '实例ID',
  `step_no` INT NOT NULL COMMENT '步骤序号',
  `step_label` VARCHAR(128) DEFAULT NULL COMMENT '步骤名称',
  `capability` VARCHAR(64) DEFAULT NULL COMMENT '步骤能力，动作编码或查询编码',
  `target_type` VARCHAR(64) DEFAULT NULL COMMENT '目标单据类型',
  `target_no` VARCHAR(64) DEFAULT NULL COMMENT '目标单据号',
  `status` VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING/RUNNING/SUCCEEDED/FAILED/SKIPPED',
  `attempt_count` INT NOT NULL DEFAULT 0 COMMENT '本步尝试次数',
  `biz_no` VARCHAR(64) DEFAULT NULL COMMENT '结果单据号',
  `biz_id` BIGINT DEFAULT NULL COMMENT '结果单据ID',
  `message` VARCHAR(1024) DEFAULT NULL COMMENT '结果或失败原因',
  `elapsed_ms` BIGINT DEFAULT NULL COMMENT '耗时毫秒',
  `created_time` DATETIME DEFAULT NULL COMMENT '创建时间',
  `updated_time` DATETIME DEFAULT NULL COMMENT '更新时间',
  `deleted_flag` INT NOT NULL DEFAULT 0 COMMENT '删除标记',
  PRIMARY KEY (`step_id`),
  KEY `idx_ai_task_step_instance` (`instance_id`, `step_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 多步任务步骤流水';

-- 演示定义：入库单待确认事件 → 自动提交来料检验 → 通知仓管。
-- 默认 PAUSED，由人在页面上启用，保持"规则由人把关"的口径。
INSERT INTO `ai_task_definition`
  (`definition_no`, `definition_name`, `trigger_type`, `trigger_event_key`, `interval_minutes`,
   `status`, `max_attempts`, `max_instances_per_hour`, `steps_json`, `remark`,
   `created_time`, `updated_time`, `deleted_flag`)
SELECT 'TASK-INBOUND-IQC', '入库单待确认自动来料检验', 'EVENT', 'WMS_INBOUND_PENDING', 10,
       'PAUSED', 2, 50,
       '[{"stepNo":1,"label":"读取触发入库单","kind":"query","capability":"inbound_lookup"},{"stepNo":2,"label":"生成来料检验通知","kind":"notify","capability":"notify_iqc_pending"},{"stepNo":3,"label":"通知仓管","kind":"notify","capability":"notify_warehouse"}]',
       '入库单进入待确认时，自动核对批次并登记来料检验待办，再通知仓管；同一条入库单只执行一次。',
       NOW(), NOW(), 0
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `ai_task_definition` WHERE `definition_no` = 'TASK-INBOUND-IQC');
