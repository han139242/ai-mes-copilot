-- AI_AGENT_V2：一句话多步办业务（分步执行、结果可见、失败可续）。
--
-- 背景：单步写命令只能办一件事。用户一句「给 WO-xxx 生成领料单然后转到线边仓」是两步业务，
-- 需要把整句解析成一个序列，逐步骤预览、逐步骤执行、某步失败后从失败步骤续跑。
--
-- 设计：序列头单独一张表（ai_command_sequence），每个步骤仍复用 ai_command_execution 一行，
-- 用 sequence_id + sequence_step_no 串起来。这样单步链路完全不受影响（sequence_id 为空即可）。
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

CREATE TABLE IF NOT EXISTS `ai_command_sequence` (
  `sequence_id` VARCHAR(64) NOT NULL COMMENT '序列ID，也是预览返回的 planId',
  `idempotency_key` VARCHAR(96) DEFAULT NULL COMMENT '整序列幂等键',
  `request_hash` VARCHAR(64) DEFAULT NULL COMMENT '规范化序列入参摘要，用于识别同键不同入参',
  `conversation_id` VARCHAR(64) DEFAULT NULL COMMENT '来源会话ID',
  `user_id` BIGINT DEFAULT NULL COMMENT '发起用户ID',
  `raw_text` VARCHAR(500) DEFAULT NULL COMMENT '原始自然语言命令',
  `status` VARCHAR(24) NOT NULL COMMENT 'PREVIEW/EXECUTING/SUCCEEDED/PARTIAL_FAILED/EXPIRED/REJECTED',
  `total_steps` INT NOT NULL DEFAULT 0 COMMENT '总步数',
  `executed_steps` INT NOT NULL DEFAULT 0 COMMENT '已成功步数',
  `current_step_no` INT DEFAULT NULL COMMENT '当前或失败所在步序号',
  `plan_json` TEXT COMMENT '多步计划（每步动作、目标、参数）',
  `result_json` TEXT COMMENT '各步产出单号与结论',
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
  PRIMARY KEY (`sequence_id`),
  KEY `idx_ai_sequence_user_time` (`user_id`, `created_time`),
  KEY `idx_ai_sequence_status` (`status`),
  KEY `idx_ai_sequence_idempotency` (`idempotency_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AI多步命令序列';

-- 步骤行仍落在 ai_command_execution，只补两个可空列；单步命令这两列为空，查询口径不变。
SET @has_sequence_id = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @lamp_schema AND TABLE_NAME = 'ai_command_execution'
      AND COLUMN_NAME = 'sequence_id');
SET @add_sequence_id = IF(@has_sequence_id = 0,
    'ALTER TABLE `ai_command_execution` ADD COLUMN `sequence_id` VARCHAR(64) DEFAULT NULL COMMENT ''所属多步序列ID，单步命令为空''',
    'DO 0');
PREPARE add_sequence_id_stmt FROM @add_sequence_id;
EXECUTE add_sequence_id_stmt;
DEALLOCATE PREPARE add_sequence_id_stmt;

SET @has_sequence_step_no = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @lamp_schema AND TABLE_NAME = 'ai_command_execution'
      AND COLUMN_NAME = 'sequence_step_no');
SET @add_sequence_step_no = IF(@has_sequence_step_no = 0,
    'ALTER TABLE `ai_command_execution` ADD COLUMN `sequence_step_no` INT DEFAULT NULL COMMENT ''序列内步骤序号，从1开始''',
    'DO 0');
PREPARE add_sequence_step_no_stmt FROM @add_sequence_step_no;
EXECUTE add_sequence_step_no_stmt;
DEALLOCATE PREPARE add_sequence_step_no_stmt;

SET @has_sequence_index = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = @lamp_schema AND TABLE_NAME = 'ai_command_execution'
      AND INDEX_NAME = 'idx_ai_command_sequence');
SET @add_sequence_index = IF(@has_sequence_index = 0,
    'ALTER TABLE `ai_command_execution` ADD INDEX `idx_ai_command_sequence` (`sequence_id`, `sequence_step_no`)',
    'DO 0');
PREPARE add_sequence_index_stmt FROM @add_sequence_index;
EXECUTE add_sequence_index_stmt;
DEALLOCATE PREPARE add_sequence_index_stmt;
