-- 可选演练配置：在整机线已有的生产/检验工位上补组件工序关联。
-- 不使用本机自增ID，不新建虚构工位；缺少对应类型工位时由派工预检提示维护。
-- 装配工序共用生产工位，自检工序共用检验工位；共享工位仍受任务开工互斥保护。
SET NAMES utf8mb4;
SET @lamp_scope_sql = IF(DATABASE() REGEXP '^mes_lamp_[A-Za-z0-9_]+$',
    'DO 0', 'SELECT * FROM LAMP_ERROR_ISOLATED_DATABASE_REQUIRED');
PREPARE lamp_scope_stmt FROM @lamp_scope_sql;
EXECUTE lamp_scope_stmt;
DEALLOCATE PREPARE lamp_scope_stmt;

START TRANSACTION;
INSERT INTO factory_station_operation (station_id, operation_id, is_default, status, created_time, deleted_flag)
SELECT target.station_id, o.operation_id, 1, 'ENABLE', NOW(), 0
FROM process_operation o
JOIN (
    SELECT st.line_id, st.station_type, MIN(st.station_id) AS station_id
    FROM factory_station st JOIN factory_line l ON l.line_id=st.line_id
    WHERE st.deleted_flag=0 AND st.status='RUNNING' AND l.deleted_flag=0
      AND l.line_code IN ('LAMP-LINE-HEAD','LAMP-LINE-ARM','LAMP-LINE-BASE','LAMP-LINE-FINAL',
                          'LAMP-LINE-L100','LAMP-LINE-L200','LAMP-LINE-FLEX')
    GROUP BY st.line_id,st.station_type
) target ON target.station_type=CASE
    WHEN o.operation_code IN ('LAMP-OP-H20','LAMP-OP-A20','LAMP-OP-B30') THEN 'INSPECTION'
    ELSE 'PRODUCTION' END
WHERE o.deleted_flag=0 AND o.status='ENABLE'
  AND o.operation_code IN ('LAMP-OP-H10','LAMP-OP-H20','LAMP-OP-A10','LAMP-OP-A20','LAMP-OP-B10','LAMP-OP-B20','LAMP-OP-B30')
  AND NOT EXISTS (SELECT 1 FROM factory_station_operation existing
                 WHERE existing.station_id=target.station_id AND existing.operation_id=o.operation_id AND existing.deleted_flag=0);
COMMIT;
