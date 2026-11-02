-- 台灯演练库预警规则示例：人工按需执行，不加入自动迁移。
-- 初始规则暂停；启用后仅站内通知，不自动创建补货、维修或异常业务单。
SET @scope_sql = IF(DATABASE() REGEXP '^mes_lamp_[A-Za-z0-9_]+$', 'DO 0', 'SELECT * FROM LAMP_ERROR_ISOLATED_DATABASE_REQUIRED');
PREPARE scope_stmt FROM @scope_sql; EXECUTE scope_stmt; DEALLOCATE PREPARE scope_stmt;
START TRANSACTION;
INSERT INTO ai_alert_rule(rule_no,rule_name,metric_code,scope_type,scope_value,comparator,warning_threshold,critical_threshold,threshold_unit,auto_actions,acknowledgment_required,interval_minutes,cooldown_minutes,status,hit_count,remark,version,created_by,created_time,updated_by,updated_time,deleted_flag)
SELECT 'LAMP-BASE-SHELL-STOCK','灯头外壳可用库存不足','MATERIAL_AVAILABLE','MATERIAL','LAMP-M04','LT',100,20,'QUANTITY','NOTIFY',1,5,60,'PAUSED',0,'库存低于100件预警、低于20件严重；初始参考值，按采购周期和实际消耗调整。 用户可调整阈值；初始暂停。',0,1,NOW(),1,NOW(),0
WHERE NOT EXISTS (SELECT 1 FROM ai_alert_rule WHERE rule_no='LAMP-BASE-SHELL-STOCK')
 AND NOT EXISTS (SELECT 1 FROM ai_alert_rule WHERE metric_code='MATERIAL_AVAILABLE' AND scope_type='MATERIAL' AND scope_value <=> 'LAMP-M04' AND deleted_flag=0);
INSERT INTO ai_alert_rule(rule_no,rule_name,metric_code,scope_type,scope_value,comparator,warning_threshold,critical_threshold,threshold_unit,auto_actions,acknowledgment_required,interval_minutes,cooldown_minutes,status,hit_count,remark,version,created_by,created_time,updated_by,updated_time,deleted_flag)
SELECT 'LAMP-BASE-WO-SHORTAGE','工单缺料提醒','WORK_ORDER_SHORTAGE','ALL',NULL,'GT',0,3,'COUNT','NOTIFY',1,5,60,'PAUSED',0,'未覆盖物料超过0种预警、超过3种严重，预留不等于实际到位。 用户可调整阈值；初始暂停。',0,1,NOW(),1,NOW(),0
WHERE NOT EXISTS (SELECT 1 FROM ai_alert_rule WHERE rule_no='LAMP-BASE-WO-SHORTAGE')
 AND NOT EXISTS (SELECT 1 FROM ai_alert_rule WHERE metric_code='WORK_ORDER_SHORTAGE' AND scope_type='ALL' AND scope_value <=> NULL AND deleted_flag=0);
INSERT INTO ai_alert_rule(rule_no,rule_name,metric_code,scope_type,scope_value,comparator,warning_threshold,critical_threshold,threshold_unit,auto_actions,acknowledgment_required,interval_minutes,cooldown_minutes,status,hit_count,remark,version,created_by,created_time,updated_by,updated_time,deleted_flag)
SELECT 'LAMP-BASE-WO-IDLE','工单下达后未开工','WORK_ORDER_IDLE','ALL',NULL,'GT',12,36,'HOURS','NOTIFY',1,5,60,'PAUSED',0,'现有指标按已下达工单创建时间计算，并非精确下达时间；启用前核对排产窗口。 用户可调整阈值；初始暂停。',0,1,NOW(),1,NOW(),0
WHERE NOT EXISTS (SELECT 1 FROM ai_alert_rule WHERE rule_no='LAMP-BASE-WO-IDLE')
 AND NOT EXISTS (SELECT 1 FROM ai_alert_rule WHERE metric_code='WORK_ORDER_IDLE' AND scope_type='ALL' AND scope_value <=> NULL AND deleted_flag=0);
INSERT INTO ai_alert_rule(rule_no,rule_name,metric_code,scope_type,scope_value,comparator,warning_threshold,critical_threshold,threshold_unit,auto_actions,acknowledgment_required,interval_minutes,cooldown_minutes,status,hit_count,remark,version,created_by,created_time,updated_by,updated_time,deleted_flag)
SELECT 'LAMP-BASE-WO-OVERDUE','工单超期未完成','WORK_ORDER_OVERDUE','ALL',NULL,'GT',0,24,'HOURS','NOTIFY',1,5,60,'PAUSED',0,'现有指标以所属计划完工时间计算；超期即预警，超过24小时严重。 用户可调整阈值；初始暂停。',0,1,NOW(),1,NOW(),0
WHERE NOT EXISTS (SELECT 1 FROM ai_alert_rule WHERE rule_no='LAMP-BASE-WO-OVERDUE')
 AND NOT EXISTS (SELECT 1 FROM ai_alert_rule WHERE metric_code='WORK_ORDER_OVERDUE' AND scope_type='ALL' AND scope_value <=> NULL AND deleted_flag=0);
INSERT INTO ai_alert_rule(rule_no,rule_name,metric_code,scope_type,scope_value,comparator,warning_threshold,critical_threshold,threshold_unit,auto_actions,acknowledgment_required,interval_minutes,cooldown_minutes,status,hit_count,remark,version,created_by,created_time,updated_by,updated_time,deleted_flag)
SELECT 'LAMP-BASE-TASK-STALLED','生产任务长时间未更新','TASK_STALLED','ALL',NULL,'GT',8,24,'HOURS','NOTIFY',1,5,60,'PAUSED',0,'运行中任务距最后更新时间超过8/24小时；是系统更新间隔，不代表设备停机实测。 用户可调整阈值；初始暂停。',0,1,NOW(),1,NOW(),0
WHERE NOT EXISTS (SELECT 1 FROM ai_alert_rule WHERE rule_no='LAMP-BASE-TASK-STALLED')
 AND NOT EXISTS (SELECT 1 FROM ai_alert_rule WHERE metric_code='TASK_STALLED' AND scope_type='ALL' AND scope_value <=> NULL AND deleted_flag=0);
INSERT INTO ai_alert_rule(rule_no,rule_name,metric_code,scope_type,scope_value,comparator,warning_threshold,critical_threshold,threshold_unit,auto_actions,acknowledgment_required,interval_minutes,cooldown_minutes,status,hit_count,remark,version,created_by,created_time,updated_by,updated_time,deleted_flag)
SELECT 'LAMP-BASE-INSPECTION','检验单待处理超时','INSPECTION_OVERDUE','ALL',NULL,'GT',8,24,'HOURS','NOTIFY',1,5,60,'PAUSED',0,'草稿检验单创建后超过8/24小时仍未提交。 用户可调整阈值；初始暂停。',0,1,NOW(),1,NOW(),0
WHERE NOT EXISTS (SELECT 1 FROM ai_alert_rule WHERE rule_no='LAMP-BASE-INSPECTION')
 AND NOT EXISTS (SELECT 1 FROM ai_alert_rule WHERE metric_code='INSPECTION_OVERDUE' AND scope_type='ALL' AND scope_value <=> NULL AND deleted_flag=0);
INSERT INTO ai_alert_rule(rule_no,rule_name,metric_code,scope_type,scope_value,comparator,warning_threshold,critical_threshold,threshold_unit,auto_actions,acknowledgment_required,interval_minutes,cooldown_minutes,status,hit_count,remark,version,created_by,created_time,updated_by,updated_time,deleted_flag)
SELECT 'LAMP-BASE-NCR','不合格品处置超时','NONCONFORMING_OPEN','ALL',NULL,'GT',24,72,'HOURS','NOTIFY',1,5,60,'PAUSED',0,'未关闭不合格品单建单超过24/72小时。 用户可调整阈值；初始暂停。',0,1,NOW(),1,NOW(),0
WHERE NOT EXISTS (SELECT 1 FROM ai_alert_rule WHERE rule_no='LAMP-BASE-NCR')
 AND NOT EXISTS (SELECT 1 FROM ai_alert_rule WHERE metric_code='NONCONFORMING_OPEN' AND scope_type='ALL' AND scope_value <=> NULL AND deleted_flag=0);
INSERT INTO ai_alert_rule(rule_no,rule_name,metric_code,scope_type,scope_value,comparator,warning_threshold,critical_threshold,threshold_unit,auto_actions,acknowledgment_required,interval_minutes,cooldown_minutes,status,hit_count,remark,version,created_by,created_time,updated_by,updated_time,deleted_flag)
SELECT 'LAMP-BASE-ANDON','安灯告警响应超时','ANDON_UNRESPONDED','ALL',NULL,'GT',1,4,'HOURS','NOTIFY',1,5,60,'PAUSED',0,'告警中状态持续超过1/4小时。 用户可调整阈值；初始暂停。',0,1,NOW(),1,NOW(),0
WHERE NOT EXISTS (SELECT 1 FROM ai_alert_rule WHERE rule_no='LAMP-BASE-ANDON')
 AND NOT EXISTS (SELECT 1 FROM ai_alert_rule WHERE metric_code='ANDON_UNRESPONDED' AND scope_type='ALL' AND scope_value <=> NULL AND deleted_flag=0);
INSERT INTO ai_alert_rule(rule_no,rule_name,metric_code,scope_type,scope_value,comparator,warning_threshold,critical_threshold,threshold_unit,auto_actions,acknowledgment_required,interval_minutes,cooldown_minutes,status,hit_count,remark,version,created_by,created_time,updated_by,updated_time,deleted_flag)
SELECT 'LAMP-BASE-FAULT','设备故障持续超时','EQUIPMENT_FAULT','ALL',NULL,'GT',2,8,'HOURS','NOTIFY',1,5,60,'PAUSED',0,'故障或维修状态持续超过2/8小时。 用户可调整阈值；初始暂停。',0,1,NOW(),1,NOW(),0
WHERE NOT EXISTS (SELECT 1 FROM ai_alert_rule WHERE rule_no='LAMP-BASE-FAULT')
 AND NOT EXISTS (SELECT 1 FROM ai_alert_rule WHERE metric_code='EQUIPMENT_FAULT' AND scope_type='ALL' AND scope_value <=> NULL AND deleted_flag=0);
INSERT INTO ai_alert_rule(rule_no,rule_name,metric_code,scope_type,scope_value,comparator,warning_threshold,critical_threshold,threshold_unit,auto_actions,acknowledgment_required,interval_minutes,cooldown_minutes,status,hit_count,remark,version,created_by,created_time,updated_by,updated_time,deleted_flag)
SELECT 'LAMP-BASE-REPAIR','维修单接单超时','REPAIR_PENDING','ALL',NULL,'GT',2,8,'HOURS','NOTIFY',1,5,60,'PAUSED',0,'已提交维修单报修后超过2/8小时未接单。 用户可调整阈值；初始暂停。',0,1,NOW(),1,NOW(),0
WHERE NOT EXISTS (SELECT 1 FROM ai_alert_rule WHERE rule_no='LAMP-BASE-REPAIR')
 AND NOT EXISTS (SELECT 1 FROM ai_alert_rule WHERE metric_code='REPAIR_PENDING' AND scope_type='ALL' AND scope_value <=> NULL AND deleted_flag=0);
INSERT INTO ai_alert_rule(rule_no,rule_name,metric_code,scope_type,scope_value,comparator,warning_threshold,critical_threshold,threshold_unit,auto_actions,acknowledgment_required,interval_minutes,cooldown_minutes,status,hit_count,remark,version,created_by,created_time,updated_by,updated_time,deleted_flag)
SELECT 'LAMP-BASE-CONFLICT','排程存在冲突','SCHEDULE_CONFLICT','ALL',NULL,'GT',0,5,'COUNT','NOTIFY',1,5,60,'PAUSED',0,'场景冲突排程项超过0项预警、超过5项严重。 用户可调整阈值；初始暂停。',0,1,NOW(),1,NOW(),0
WHERE NOT EXISTS (SELECT 1 FROM ai_alert_rule WHERE rule_no='LAMP-BASE-CONFLICT')
 AND NOT EXISTS (SELECT 1 FROM ai_alert_rule WHERE metric_code='SCHEDULE_CONFLICT' AND scope_type='ALL' AND scope_value <=> NULL AND deleted_flag=0);
INSERT INTO ai_alert_rule(rule_no,rule_name,metric_code,scope_type,scope_value,comparator,warning_threshold,critical_threshold,threshold_unit,auto_actions,acknowledgment_required,interval_minutes,cooldown_minutes,status,hit_count,remark,version,created_by,created_time,updated_by,updated_time,deleted_flag)
SELECT 'LAMP-BASE-LOAD','产线未来七天负荷偏高','LINE_LOAD_RATIO','ALL',NULL,'GT',100,130,'PERCENT','NOTIFY',1,5,60,'PAUSED',0,'按现有日产能估算未来7天计划量占比，超过100%/130%；不替代班次日历精排。 用户可调整阈值；初始暂停。',0,1,NOW(),1,NOW(),0
WHERE NOT EXISTS (SELECT 1 FROM ai_alert_rule WHERE rule_no='LAMP-BASE-LOAD')
 AND NOT EXISTS (SELECT 1 FROM ai_alert_rule WHERE metric_code='LINE_LOAD_RATIO' AND scope_type='ALL' AND scope_value <=> NULL AND deleted_flag=0);
COMMIT;
