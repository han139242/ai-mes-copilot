-- 可选演练期初调整：只为 LAMP-WH-RAW 公共合格原辅料库存补一次差额，并配套记流水。
-- 不创建真实收货或检验结果，不自动执行；已消耗的演练库存不会因重跑脚本再次补回。
-- 按物料总可用量补差额，避免多批次/多库位重复各补一份；不碰半成品、线边占用和锁定量。
SET NAMES utf8mb4;
SET @lamp_scope_sql = IF(DATABASE() REGEXP '^mes_lamp_[A-Za-z0-9_]+$',
    'DO 0', 'SELECT * FROM LAMP_ERROR_ISOLATED_DATABASE_REQUIRED');
PREPARE lamp_scope_stmt FROM @lamp_scope_sql;
EXECUTE lamp_scope_stmt;
DEALLOCATE PREPARE lamp_scope_stmt;

START TRANSACTION;
-- 锁住目标仓已有公共余额行，随后在同一事务计算、记流水和改余额。
SELECT i.inventory_id FROM wms_inventory i
JOIN wms_warehouse w ON w.warehouse_id=i.warehouse_id AND w.deleted_flag=0
WHERE w.warehouse_code='LAMP-WH-RAW' AND i.deleted_flag=0 AND i.work_order_id=0
ORDER BY i.inventory_id FOR UPDATE;

CREATE TEMPORARY TABLE lamp_demo_topup_delta AS
SELECT stock.inventory_id, stock.material_id,
       GREATEST((CASE stock.material_code WHEN 'LAMP-M17' THEN 4000 WHEN 'LAMP-M18' THEN 2000 ELSE 1000 END)
                - stock.total_available, 0) AS delta
FROM (
    SELECT m.material_id, m.material_code, MIN(i.inventory_id) AS inventory_id,
           SUM(i.available_quantity) AS total_available
    FROM wms_inventory i
    JOIN mdm_material m ON m.material_id=i.material_id AND m.deleted_flag=0 AND m.status='ENABLE'
    JOIN wms_warehouse w ON w.warehouse_id=i.warehouse_id AND w.deleted_flag=0 AND w.status='ENABLE'
    JOIN wms_location loc ON loc.location_id=i.location_id AND loc.warehouse_id=w.warehouse_id
        AND loc.deleted_flag=0 AND loc.status IN ('IDLE','OCCUPIED','ENABLE') AND loc.location_type='NORMAL'
    JOIN wms_material_batch b ON b.batch_id=i.batch_id AND b.material_id=i.material_id AND b.deleted_flag=0
        AND b.quality_status IN ('PASS','CONCESSION') AND b.status IN ('AVAILABLE','ENABLE')
    WHERE i.deleted_flag=0 AND i.work_order_id=0 AND w.warehouse_code='LAMP-WH-RAW' AND w.warehouse_type='RAW'
      AND m.material_type IN ('RAW','AUXILIARY')
      AND m.material_code REGEXP '^LAMP-M(0[1-9]|1[0-8])$'
    GROUP BY m.material_id,m.material_code
) stock
WHERE NOT EXISTS (SELECT 1 FROM wms_stock_movement movement
                  WHERE movement.movement_no=CONCAT('LAMP-DEMO-TOPUP-V1-',stock.material_id));

INSERT INTO wms_stock_movement
    (movement_no,movement_type,source_order_type,source_order_id,material_id,batch_id,
     warehouse_id,location_id,work_order_id,quantity,direction,operation_time,remark,version,created_time,deleted_flag)
SELECT CONCAT('LAMP-DEMO-TOPUP-V1-',d.material_id),'ADJUST','DEMO_SEED',i.inventory_id,
       i.material_id,i.batch_id,i.warehouse_id,i.location_id,0,d.delta,'IN',NOW(),
       '一次性演练期初调整；非采购收货、非真实生产产出',0,NOW(),0
FROM lamp_demo_topup_delta d JOIN wms_inventory i ON i.inventory_id=d.inventory_id
WHERE d.delta>0;

UPDATE wms_inventory i JOIN lamp_demo_topup_delta d ON d.inventory_id=i.inventory_id
SET i.available_quantity=i.available_quantity+d.delta,i.version=i.version+1,i.updated_time=NOW()
WHERE d.delta>0;
DROP TEMPORARY TABLE lamp_demo_topup_delta;
COMMIT;
