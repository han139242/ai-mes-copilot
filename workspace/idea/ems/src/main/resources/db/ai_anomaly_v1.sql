-- 主动异常闭环：扫描记录表。
-- 复用既有 mfg_exception 作为异常台账，本表只记录每轮扫描的执行情况，便于监控页追溯。
SET @lamp_scope_sql = IF(DATABASE() REGEXP '^mes_lamp_[A-Za-z0-9_]+$', 'DO 0', 'SELECT * FROM LAMP_ERROR_ISOLATED_DATABASE_REQUIRED');
PREPARE lamp_scope_stmt FROM @lamp_scope_sql;
EXECUTE lamp_scope_stmt;
DEALLOCATE PREPARE lamp_scope_stmt;

CREATE TABLE IF NOT EXISTS `ai_anomaly_scan` (
  `scan_id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '扫描主键。',
  `scan_no` varchar(64) NOT NULL COMMENT '扫描编号。',
  `trigger_type` varchar(32) NOT NULL DEFAULT 'SCHEDULE' COMMENT '触发方式：SCHEDULE 定时、MANUAL 手工。',
  `status` varchar(32) NOT NULL DEFAULT 'RUNNING' COMMENT 'SUCCEEDED 成功、FAILED 失败。',
  `detected_count` int NOT NULL DEFAULT 0 COMMENT '本轮检测到的异常条数。',
  `opened_count` int NOT NULL DEFAULT 0 COMMENT '本轮新登记异常条数。',
  `closed_count` int NOT NULL DEFAULT 0 COMMENT '本轮自动关闭异常条数。',
  `notified_count` int NOT NULL DEFAULT 0 COMMENT '本轮发出通知条数。',
  `elapsed_ms` bigint DEFAULT NULL COMMENT '耗时毫秒。',
  `summary` varchar(500) DEFAULT NULL COMMENT '扫描结论。',
  `error_message` varchar(500) DEFAULT NULL COMMENT '失败原因。',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `created_by` bigint unsigned DEFAULT NULL COMMENT '创建人',
  `created_time` datetime DEFAULT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned DEFAULT NULL COMMENT '更新人',
  `updated_time` datetime DEFAULT NULL COMMENT '更新时间',
  `deleted_flag` tinyint NOT NULL DEFAULT '0' COMMENT '软删除标记 0未删 1已删',
  PRIMARY KEY (`scan_id`),
  UNIQUE KEY `uk_ai_anomaly_scan_no` (`scan_no`),
  KEY `idx_ai_anomaly_scan_time` (`created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='主动异常扫描记录';

-- 异常台账补两个索引，扫描按异常编号幂等登记时会用到。
SET @idx_sql = IF(
  (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'mfg_exception' AND index_name = 'idx_mfg_exception_type_status') > 0,
  'DO 0',
  'ALTER TABLE `mfg_exception` ADD KEY `idx_mfg_exception_type_status` (`exception_type`, `status`, `deleted_flag`)'
);
PREPARE lamp_idx_stmt FROM @idx_sql;
EXECUTE lamp_idx_stmt;
DEALLOCATE PREPARE lamp_idx_stmt;
