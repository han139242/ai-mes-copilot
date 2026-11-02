-- ---------------------------------------------------------------------------
-- 终检方案绑定产出工序（幂等修正）
--
-- 背景：台灯受控产品的终检方案在 V1.8 基线里以 operation_id = NULL 建单，
--       而 ExecutionSnapshotPolicy 要求 PROCESS / FINAL 方案必须显式绑定冻结工序，
--       且 FINAL 必须绑定路线的唯一产出工序（序号最大的那一道）。
--       结果：工单下达时冻结快照被拒，报「过程检和终检方案必须显式绑定冻结工序」。
--
-- 本脚本只补 operation_id，不动方案状态、明细与标准，可重复执行。
-- ---------------------------------------------------------------------------
SET @lamp_scope_sql = IF(DATABASE() REGEXP '^mes_lamp_[A-Za-z0-9_]+$', 'DO 0', 'SELECT * FROM LAMP_ERROR_ISOLATED_DATABASE_REQUIRED');
PREPARE lamp_scope_stmt FROM @lamp_scope_sql;
EXECUTE lamp_scope_stmt;
DEALLOCATE PREPARE lamp_scope_stmt;

UPDATE qms_inspection_scheme s
   SET s.operation_id = (
        SELECT ri.operation_id
          FROM process_route_item ri
         WHERE ri.deleted_flag = 0
           AND ri.route_id = (
                SELECT r.route_id
                  FROM process_route r
                 WHERE r.product_id = s.product_id
                   AND r.status = 'RELEASED'
                   AND r.deleted_flag = 0
                 ORDER BY r.route_id DESC
                 LIMIT 1)
         ORDER BY ri.sequence_no DESC
         LIMIT 1),
       s.updated_time = NOW()
 WHERE s.inspection_type = 'FINAL'
   AND s.operation_id IS NULL
   AND s.deleted_flag = 0;

-- 核对：仍为空的终检方案应为 0 条
SELECT COUNT(*) AS final_scheme_without_operation
  FROM qms_inspection_scheme
 WHERE inspection_type = 'FINAL'
   AND operation_id IS NULL
   AND deleted_flag = 0;
