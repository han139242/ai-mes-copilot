-- AI_MESSAGE_CLIENT_ID_V1：给对话消息补一列 client_message_id，让重试不再重复落库。
--
-- 背景：前端把同一条提问重发（用户点重试、网络抖动后自动重发、回答生成超时再试一次）时，
-- 后端原先只按 seq_no 递增追加，重发几次就多出几条一模一样的 user 消息，历史记录里越积越多。
-- 现在前端给每条提问生成一个 clientMessageId，重试时复用同一个值，后端按
-- (conversation_id, client_message_id) 判重：命中就回放既有记录，不再新增。
--
-- 幂等：加列、建索引前都先查 information_schema，存在就跳过。
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
           AND column_name = 'client_message_id'),
  'SELECT 1',
  'ALTER TABLE `ai_conversation_message` ADD COLUMN `client_message_id` VARCHAR(64) NULL COMMENT ''客户端消息标识，重试复用，用于去重'' AFTER `sequence_json`'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.statistics
         WHERE table_schema = @lamp_schema AND table_name = 'ai_conversation_message'
           AND index_name = 'uk_ai_message_client'),
  'SELECT 1',
  'ALTER TABLE `ai_conversation_message` ADD UNIQUE KEY `uk_ai_message_client` (`conversation_id`,`client_message_id`)'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 校验：列与索引都在时返回 1 / 1。
SELECT COUNT(*) AS `client_message_id_column`
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'ai_conversation_message'
  AND column_name = 'client_message_id';

SELECT COUNT(*) AS `uk_ai_message_client_index`
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'ai_conversation_message'
  AND index_name = 'uk_ai_message_client';
