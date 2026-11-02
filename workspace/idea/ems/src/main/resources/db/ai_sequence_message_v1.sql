-- AI_SEQUENCE_MESSAGE_V1：给对话消息补一列 sequence_json，保存多步办理计划快照。
--
-- 背景：一句话连着办几件事时，后端会返回 sequence（planId、各步目标、前置条件、是否可执行）。
-- 之前只把回执内容拼成正文存下来，sequence 本身没落库，页面重进或换设备后这条回答就退化成
-- 一段文字，多步卡片渲染不出来，「预览→确认→执行→失败续跑」这条体验在历史消息里断了。
--
-- 幂等：加列前先查 information_schema，存在就跳过。
-- 隔离要求：只在演练库执行，不得作用于 mes_local 或共享 mes。
SET NAMES utf8mb4;

SET @lamp_scope_sql = IF(DATABASE() REGEXP '^mes_lamp_[A-Za-z0-9_]+$',
    'DO 0',
    'SELECT * FROM LAMP_ERROR_ISOLATED_DATABASE_REQUIRED');
PREPARE lamp_scope_stmt FROM @lamp_scope_sql;
EXECUTE lamp_scope_stmt;
DEALLOCATE PREPARE lamp_scope_stmt;

SET @lamp_schema = DATABASE();

SET @ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns
         WHERE table_schema = @lamp_schema AND table_name = 'ai_conversation_message'
           AND column_name = 'sequence_json'),
  'SELECT 1',
  'ALTER TABLE `ai_conversation_message` ADD COLUMN `sequence_json` LONGTEXT NULL COMMENT ''多步办理计划JSON快照，含planId与各步状态'' AFTER `command_id`'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 校验：列已存在则返回 1。
SELECT COUNT(*) AS `sequence_json_column`
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'ai_conversation_message'
  AND column_name = 'sequence_json';
