-- AI_TASK_GUARD_V1：给多步任务补「等待补充 / 取消 / 超时 / 调用量上限」四类保护。
--
-- 背景：ai_task_v1 已经能按事件、定时、手动触发，失败时记 PARTIAL_FAILED 并留下续跑点。
-- 但长期自动运行还缺四件事：
--   1. 需要人补数据的步骤（例如逐件全检缺实测值）应当停成「等待补充」，而不是直接算失败；
--   2. 人得能主动取消一个还在等或已经跑偏的实例；
--   3. 进程卡死或外部系统不回时，实例不能永远停在 RUNNING，要按超时收敛；
--   4. 单条规则每天能调多少次工具要设上限，避免一条配错的规则把领域服务刷爆。
--
-- 幂等：加列前先查 information_schema，存在就跳过；状态只放宽注释，不改枚举约束。
-- 隔离要求：只在演练库执行，不得作用于 mes_local 或共享 mes。
SET NAMES utf8mb4;

SET @lamp_scope_sql = IF(DATABASE() REGEXP '^mes_lamp_[A-Za-z0-9_]+$',
    'DO 0',
    'SELECT * FROM LAMP_ERROR_ISOLATED_DATABASE_REQUIRED');
PREPARE lamp_scope_stmt FROM @lamp_scope_sql;
EXECUTE lamp_scope_stmt;
DEALLOCATE PREPARE lamp_scope_stmt;

SET @lamp_schema = DATABASE();

-- 定义级：超时分钟数与每日步骤调用上限。
SET @ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns
         WHERE table_schema = @lamp_schema AND table_name = 'ai_task_definition'
           AND column_name = 'timeout_minutes'),
  'DO 0',
  'ALTER TABLE `ai_task_definition` ADD COLUMN `timeout_minutes` INT NOT NULL DEFAULT 30 COMMENT ''单实例最长执行分钟数，超时收敛为 TIMEOUT'' AFTER `max_instances_per_hour`'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns
         WHERE table_schema = @lamp_schema AND table_name = 'ai_task_definition'
           AND column_name = 'max_step_calls_per_day'),
  'DO 0',
  'ALTER TABLE `ai_task_definition` ADD COLUMN `max_step_calls_per_day` INT NOT NULL DEFAULT 500 COMMENT ''单个定义每日最多执行的步骤次数（调用量上限）'' AFTER `timeout_minutes`'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 实例级：等待补充的原因与截止时间，便于页面直接展示「在等谁补什么」。
SET @ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns
         WHERE table_schema = @lamp_schema AND table_name = 'ai_task_instance'
           AND column_name = 'waiting_reason'),
  'DO 0',
  'ALTER TABLE `ai_task_instance` ADD COLUMN `waiting_reason` VARCHAR(512) DEFAULT NULL COMMENT ''等待补充的具体原因，状态为 WAITING_INPUT 时填写'' AFTER `error_message`'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns
         WHERE table_schema = @lamp_schema AND table_name = 'ai_task_instance'
           AND column_name = 'deadline_time'),
  'DO 0',
  'ALTER TABLE `ai_task_instance` ADD COLUMN `deadline_time` DATETIME DEFAULT NULL COMMENT ''本实例的超时截止时间'' AFTER `waiting_reason`'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 校验：四列都已存在时返回 4。
SELECT COUNT(*) AS `guard_columns`
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND ((table_name = 'ai_task_definition'
        AND column_name IN ('timeout_minutes', 'max_step_calls_per_day'))
    OR (table_name = 'ai_task_instance'
        AND column_name IN ('waiting_reason', 'deadline_time')));
