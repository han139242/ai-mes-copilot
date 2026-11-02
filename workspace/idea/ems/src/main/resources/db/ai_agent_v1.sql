-- AI_AGENT_V1：AI 自然语言写命令的预览、确认、执行与审计。
--
-- 背景：智能助手已具备只读查询能力，本迁移补齐"自然语言操控 MES"的写操作底座。
-- 每条写命令先落一张预览凭据（confirmation_id），确认执行时按凭据回放，
-- 全过程留命令记录与步骤流水，便于审计与幂等重放。
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

CREATE TABLE IF NOT EXISTS `ai_command_execution` (
  `command_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '命令执行ID',
  `confirmation_id` VARCHAR(64) NOT NULL COMMENT '预览确认凭据，UUID去横线',
  `idempotency_key` VARCHAR(96) DEFAULT NULL COMMENT '调用方幂等键',
  `request_hash` VARCHAR(64) NOT NULL COMMENT '规范化参数摘要，用于识别同凭据改参数',
  `conversation_id` VARCHAR(64) DEFAULT NULL COMMENT '来源会话ID',
  `user_id` BIGINT DEFAULT NULL COMMENT '发起用户ID',
  `raw_text` VARCHAR(500) DEFAULT NULL COMMENT '原始自然语言命令',
  `intent` VARCHAR(48) DEFAULT NULL COMMENT '意图编码',
  `action` VARCHAR(48) NOT NULL COMMENT '写动作编码',
  `action_label` VARCHAR(64) DEFAULT NULL COMMENT '写动作中文名',
  `target_type` VARCHAR(32) DEFAULT NULL COMMENT '目标单据类型',
  `target_no` VARCHAR(96) DEFAULT NULL COMMENT '目标单号',
  `target_id` BIGINT DEFAULT NULL COMMENT '目标主键',
  `params_json` TEXT COMMENT '规范化命令参数',
  `preview_json` TEXT COMMENT '预览返回体',
  `impacts_json` TEXT COMMENT '影响说明',
  `preconditions_json` TEXT COMMENT '前置条件校验结果',
  `status` VARCHAR(24) NOT NULL COMMENT 'PREVIEW/CONFIRMED/EXECUTING/SUCCEEDED/FAILED/PARTIAL_FAILED/CANCELLED/EXPIRED/REJECTED',
  `result_json` TEXT COMMENT '执行结果',
  `error_message` VARCHAR(500) DEFAULT NULL COMMENT '失败原因',
  `source` VARCHAR(24) DEFAULT NULL COMMENT 'WORKBENCH/DOCK/API',
  `elapsed_ms` BIGINT DEFAULT NULL COMMENT '执行耗时毫秒',
  `expires_at` DATETIME DEFAULT NULL COMMENT '预览过期时间',
  `executed_at` DATETIME DEFAULT NULL COMMENT '执行完成时间',
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `created_by` BIGINT DEFAULT NULL,
  `created_time` DATETIME DEFAULT NULL,
  `updated_by` BIGINT DEFAULT NULL,
  `updated_time` DATETIME DEFAULT NULL,
  `deleted_flag` INT NOT NULL DEFAULT 0,
  PRIMARY KEY (`command_id`),
  UNIQUE KEY `uk_ai_command_confirmation` (`confirmation_id`),
  KEY `idx_ai_command_user_time` (`user_id`, `created_time`),
  KEY `idx_ai_command_status` (`status`),
  KEY `idx_ai_command_action` (`action`),
  KEY `idx_ai_command_idempotency` (`idempotency_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AI写命令执行记录';

CREATE TABLE IF NOT EXISTS `ai_command_step` (
  `step_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '步骤ID',
  `command_id` BIGINT NOT NULL COMMENT '所属命令ID',
  `step_no` INT DEFAULT NULL COMMENT '步骤序号，从1开始',
  `capability` VARCHAR(48) DEFAULT NULL COMMENT '步骤能力：PRECHECK/DOMAIN_CALL',
  `input_digest` VARCHAR(500) DEFAULT NULL COMMENT '本步骤输入摘要',
  `status` VARCHAR(24) DEFAULT NULL COMMENT 'PENDING/RUNNING/SUCCEEDED/FAILED/SKIPPED',
  `biz_type` VARCHAR(32) DEFAULT NULL COMMENT '产出单据类型',
  `biz_no` VARCHAR(96) DEFAULT NULL COMMENT '产出单据号',
  `biz_id` BIGINT DEFAULT NULL COMMENT '产出单据主键',
  `error_message` VARCHAR(500) DEFAULT NULL COMMENT '失败原因',
  `started_at` DATETIME DEFAULT NULL,
  `ended_at` DATETIME DEFAULT NULL,
  `elapsed_ms` BIGINT DEFAULT NULL,
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `created_by` BIGINT DEFAULT NULL,
  `created_time` DATETIME DEFAULT NULL,
  `updated_by` BIGINT DEFAULT NULL,
  `updated_time` DATETIME DEFAULT NULL,
  `deleted_flag` INT NOT NULL DEFAULT 0,
  PRIMARY KEY (`step_id`),
  KEY `idx_ai_command_step_command` (`command_id`, `step_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AI写命令步骤流水';
