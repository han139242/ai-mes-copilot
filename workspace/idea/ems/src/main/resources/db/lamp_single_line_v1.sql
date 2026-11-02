-- 可选演练配置：组件在整机线上生产。按业务编码匹配，兼容不同机器的自增主键。
-- 日产能系数是演练假设：灯头/灯臂3、L100底座2.4、L200底座2；现场应维护实际能力。
-- 只有唯一已发布路线的启用组件产品参与；不自动变更已有能力，不属于结构迁移链。
SET NAMES utf8mb4;
SET @lamp_scope_sql = IF(DATABASE() REGEXP '^mes_lamp_[A-Za-z0-9_]+$',
    'DO 0', 'SELECT * FROM LAMP_ERROR_ISOLATED_DATABASE_REQUIRED');
PREPARE lamp_scope_stmt FROM @lamp_scope_sql;
EXECUTE lamp_scope_stmt;
DEALLOCATE PREPARE lamp_scope_stmt;

START TRANSACTION;
INSERT INTO mfg_line_product_capability
    (line_id, product_id, route_id, priority_level, capacity_factor, status, remark,
     version, created_time, deleted_flag)
SELECT l.line_id, p.product_id, routes.route_id,
       CASE l.line_code WHEN 'LAMP-LINE-L200' THEN 10 WHEN 'LAMP-LINE-L100' THEN 20
            WHEN 'LAMP-LINE-FINAL' THEN 30 ELSE 40 END,
       CASE p.product_code WHEN 'LAMP-BASE-L100' THEN 2.4 WHEN 'LAMP-BASE-L200' THEN 2 ELSE 3 END,
       'ENABLE', 'LAMP_SINGLE_LINE_V1: 组件在整机线上生产（演练能力）', 0, NOW(), 0
FROM factory_line l
JOIN mdm_product p ON p.product_code IN ('LAMP-HEAD','LAMP-ARM','LAMP-BASE-L100','LAMP-BASE-L200')
    AND p.deleted_flag=0 AND p.status='ENABLE'
JOIN (SELECT product_id, MIN(route_id) AS route_id FROM process_route
      WHERE deleted_flag=0 AND status='RELEASED' GROUP BY product_id HAVING COUNT(*)=1) routes
    ON routes.product_id=p.product_id
WHERE l.deleted_flag=0 AND l.status IN ('ENABLE','RUNNING')
  AND l.line_code IN ('LAMP-LINE-FINAL','LAMP-LINE-L100','LAMP-LINE-L200','LAMP-LINE-FLEX')
  AND NOT EXISTS (SELECT 1 FROM mfg_line_product_capability c
                  WHERE c.line_id=l.line_id AND c.product_id=p.product_id AND c.deleted_flag=0);
COMMIT;
