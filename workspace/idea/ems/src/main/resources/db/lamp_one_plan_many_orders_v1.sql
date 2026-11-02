-- ---------------------------------------------------------------------------
-- 一张订单一张计划：组件工单不再另建生产计划
--
-- 背景：改造前 createComponentWorkOrders 会为每个半成品缺口再插一条
--       PLN-C-* 的生产计划（plan_type=COMPONENT），于是订单详情里一个订单
--       挂着 SP-2-11 加三条 PLN-C-*，用户看到的就是"一个订单很多个计划"。
--       现在口径改为：订单在智能排产里确认一次，只产生一张生产计划；
--       组件半成品在【同一张计划】下生成 COMPONENT 工单。
--
-- 因此工单必须自带计划起止时间：进度与工单界面原来从 JOIN 的计划表取时间，
-- 组件工单不再有独立计划后，时间会退化成父计划的时间。
--
-- 幂等：列已存在则跳过。隔离要求：只在演练库执行，不得作用于 mes_local 或共享 mes。
-- ---------------------------------------------------------------------------
SET NAMES utf8mb4;

SET @lamp_scope_sql = IF(DATABASE() REGEXP '^mes_lamp_[A-Za-z0-9_]+$',
    'DO 0',
    'SELECT * FROM LAMP_ERROR_ISOLATED_DATABASE_REQUIRED');
PREPARE lamp_scope_stmt FROM @lamp_scope_sql;
EXECUTE lamp_scope_stmt;
DEALLOCATE PREPARE lamp_scope_stmt;

SET @lamp_schema=DATABASE();

-- ---------- 1. 工单自带计划起止时间 ----------
SET @lamp_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=@lamp_schema AND table_name='mfg_work_order' AND column_name='planned_start_time'),'DO 0','ALTER TABLE `mfg_work_order` ADD COLUMN `planned_start_time` DATETIME NULL COMMENT ''工单计划开始时间；组件工单是它在同一条产线上的窗口''');
PREPARE lamp_stmt FROM @lamp_ddl; EXECUTE lamp_stmt; DEALLOCATE PREPARE lamp_stmt;

SET @lamp_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=@lamp_schema AND table_name='mfg_work_order' AND column_name='planned_end_time'),'DO 0','ALTER TABLE `mfg_work_order` ADD COLUMN `planned_end_time` DATETIME NULL COMMENT ''工单计划结束时间；组件工单是它在同一条产线上的窗口''');
PREPARE lamp_stmt FROM @lamp_ddl; EXECUTE lamp_stmt; DEALLOCATE PREPARE lamp_stmt;

START TRANSACTION;

-- ---------- 2. 历史工单回填时间 ----------
-- 存量工单的时间仍可从它所属计划取到；组件工单回填的是组件计划的时间，
-- 正是它自己在那条产线上的窗口，语义与新口径一致。
UPDATE mfg_work_order wo
    JOIN mfg_production_plan pl ON pl.plan_id = wo.plan_id AND pl.deleted_flag = 0
   SET wo.planned_start_time = COALESCE(wo.planned_start_time, pl.planned_start_time),
       wo.planned_end_time   = COALESCE(wo.planned_end_time, pl.planned_end_time)
 WHERE wo.deleted_flag = 0
   AND (wo.planned_start_time IS NULL OR wo.planned_end_time IS NULL);

COMMIT;

SELECT COUNT(*) AS work_orders_without_time
  FROM mfg_work_order
 WHERE deleted_flag = 0 AND (planned_start_time IS NULL OR planned_end_time IS NULL);
