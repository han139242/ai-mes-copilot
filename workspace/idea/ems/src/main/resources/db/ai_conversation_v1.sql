-- AI_CONVERSATION_V1：对话历史与消息持久化。
--
-- 背景：智能助手的聊天记录原先只存在浏览器里（工作台 localStorage 最多 24 个会话、
-- 每会话 60 条，悬浮窗放 sessionStorage），换台设备就丢；存储 key 又没带账号，
-- 同一浏览器切换账号还能看到上一个人的记录。这里把会话与消息正式落到后端，
-- 所有读写都按 user_id 校验归属。
--
-- 一条消息除了文字，还要能复原当时的展示：blocks_json 存表格与图表，
-- query_time 标记这批数据是什么时候查的，filters_json 存筛选条件，
-- command_id 指向 ai_command_execution，让页面重进后能按后台记录恢复执行状态，
-- 而不是依赖卡片组件的内存。
--
-- 幂等：建表用 IF NOT EXISTS，索引与字段变更前先查 information_schema。
-- 隔离要求：只在演练库执行，不得作用于 mes_local 或共享 mes。
SET NAMES utf8mb4;

SET @lamp_scope_sql = IF(DATABASE() REGEXP '^mes_lamp_[A-Za-z0-9_]+$',
    'DO 0',
    'SELECT * FROM LAMP_ERROR_ISOLATED_DATABASE_REQUIRED');
PREPARE lamp_scope_stmt FROM @lamp_scope_sql;
EXECUTE lamp_scope_stmt;
DEALLOCATE PREPARE lamp_scope_stmt;

CREATE TABLE IF NOT EXISTS `ai_conversation` (
  `conversation_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '会话ID',
  `conversation_no` VARCHAR(64) NOT NULL COMMENT '会话编号，全局唯一',
  `user_id` BIGINT NOT NULL COMMENT '归属用户ID，所有读写按它校验',
  `title` VARCHAR(128) DEFAULT NULL COMMENT '会话标题，默认取首条用户消息前24字',
  `capability` VARCHAR(32) NOT NULL DEFAULT 'general' COMMENT '所属能力：general/dashboard/scheduling/maintenance/quality/inventory/writing',
  `message_count` INT NOT NULL DEFAULT 0 COMMENT '消息条数，追加消息时同步维护',
  `last_message_time` DATETIME DEFAULT NULL COMMENT '最后一条消息时间，会话列表按它倒序',
  `archived` TINYINT NOT NULL DEFAULT 0 COMMENT '归档标记：0 正常 / 1 已归档',
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `created_by` BIGINT DEFAULT NULL COMMENT '创建人',
  `created_time` DATETIME DEFAULT NULL COMMENT '创建时间',
  `updated_by` BIGINT DEFAULT NULL COMMENT '更新人',
  `updated_time` DATETIME DEFAULT NULL COMMENT '更新时间',
  `deleted_flag` INT NOT NULL DEFAULT 0 COMMENT '删除标记：0 正常 / 1 已删除',
  PRIMARY KEY (`conversation_id`),
  UNIQUE KEY `uk_ai_conversation_no` (`conversation_no`),
  KEY `idx_ai_conversation_user` (`user_id`, `archived`, `last_message_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 对话会话';

CREATE TABLE IF NOT EXISTS `ai_conversation_message` (
  `message_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '消息ID',
  `conversation_id` BIGINT NOT NULL COMMENT '所属会话ID',
  `seq_no` INT NOT NULL COMMENT '会话内序号，从1开始连续递增',
  `role` VARCHAR(16) NOT NULL COMMENT '角色：user 用户 / assistant 助手',
  `content` LONGTEXT COMMENT '消息正文',
  `blocks_json` LONGTEXT COMMENT '结构化展示块JSON：表格与图表数据',
  `suggestions_json` TEXT COMMENT '追问建议JSON数组',
  `command_json` LONGTEXT COMMENT '业务命令卡片JSON快照',
  `command_id` BIGINT DEFAULT NULL COMMENT '关联 ai_command_execution.command_id，用于恢复真实执行状态',
  `intent` VARCHAR(48) DEFAULT NULL COMMENT '命中的意图编码',
  `query_time` DATETIME DEFAULT NULL COMMENT '本条回答对应数据的查询时间',
  `filters_json` TEXT COMMENT '查询筛选条件JSON，例如日期范围、物料、仓库',
  `model` VARCHAR(48) DEFAULT NULL COMMENT '产生该回答的模型或链路标识',
  `elapsed_ms` BIGINT DEFAULT NULL COMMENT '该轮处理耗时毫秒',
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `created_by` BIGINT DEFAULT NULL COMMENT '创建人',
  `created_time` DATETIME DEFAULT NULL COMMENT '创建时间',
  `updated_by` BIGINT DEFAULT NULL COMMENT '更新人',
  `updated_time` DATETIME DEFAULT NULL COMMENT '更新时间',
  `deleted_flag` INT NOT NULL DEFAULT 0 COMMENT '删除标记：0 正常 / 1 已删除',
  PRIMARY KEY (`message_id`),
  UNIQUE KEY `uk_ai_conversation_message_seq` (`conversation_id`, `seq_no`),
  KEY `idx_ai_conversation_message_cmd` (`command_id`),
  KEY `idx_ai_conversation_message_time` (`conversation_id`, `created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 对话消息';

-- 校验：两张表存在，且消息表的会话序号唯一索引已生效。
SELECT COUNT(*) AS `ai_conversation_tables`
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN ('ai_conversation', 'ai_conversation_message');

SELECT `table_name`, `index_name`, `non_unique`, `column_name`, `seq_in_index`
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name IN ('ai_conversation', 'ai_conversation_message')
ORDER BY `table_name`, `index_name`, `seq_in_index`;
