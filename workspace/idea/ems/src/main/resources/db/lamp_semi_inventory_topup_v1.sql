-- 可选演练期初调整：只为 LAMP-WH-SEMI 半成品仓补一份组件库存，并配套记流水。
-- 不创建真实生产任务、不产生报工、不占用线边，也不自动执行；重复执行只补齐差额。
-- 使用独立的演示批次，不引用已直接交接给线边的真实组件批次，避免与生产批次对账互相干扰。
-- 批次标记 PASS/AVAILABLE 只是为了让它能进入领料候选；这不代表真实终检结论，
-- 领用前仍必须走「库存半成品领用复验」的检验单，检验合格后才允许出库或转线边。
SET NAMES utf8mb4;
SET @lamp_scope_sql = IF(DATABASE() REGEXP '^mes_lamp_[A-Za-z0-9_]+$',
    'DO 0', 'SELECT * FROM LAMP_ERROR_ISOLATED_DATABASE_REQUIRED');
PREPARE lamp_scope_stmt FROM @lamp_scope_sql;
EXECUTE lamp_scope_stmt;
DEALLOCATE PREPARE lamp_scope_stmt;

START TRANSACTION;
-- 先锁住半成品仓已有的公共余额行，随后在同一事务计算差额、记流水和改余额。
SELECT i.inventory_id FROM wms_inventory i
JOIN wms_warehouse w ON w.warehouse_id=i.warehouse_id AND w.deleted_flag=0
WHERE w.warehouse_code='LAMP-WH-SEMI' AND i.deleted_flag=0 AND i.work_order_id=0
ORDER BY i.inventory_id FOR UPDATE;

-- 目标数量按物料编码给出，避免写死自增主键；物料类型必须是半成品。
CREATE TEMPORARY TABLE lamp_demo_semi_target AS
SELECT m.material_id, m.material_code, t.target_quantity
FROM (
    SELECT 'LAMP-HEAD' AS material_code, 100.0000 AS target_quantity
    UNION ALL SELECT 'LAMP-ARM', 100.0000
    UNION ALL SELECT 'LAMP-BASE-L100', 100.0000
    UNION ALL SELECT 'LAMP-BASE-L200', 60.0000
) t
JOIN mdm_material m ON m.material_code=t.material_code
    AND m.deleted_flag=0 AND m.status='ENABLE' AND m.material_type='SEMI_FINISHED';

-- 演示批次：只在缺失时创建，批次号固定，保证重跑不会重复建批次。
INSERT INTO wms_material_batch
    (batch_no,material_id,production_date,received_date,quality_status,status,
     source_order_type,remark,version,created_time,updated_time,deleted_flag)
SELECT CONCAT('FG-DEMO-SEMI-',d.material_code),d.material_id,CURRENT_DATE,CURRENT_DATE,'PASS','AVAILABLE',
       'DEMO_SEED','演练半成品仓期初批次；非真实生产产出，不代表实测检验结论',
       0,NOW(),NOW(),0
FROM lamp_demo_semi_target d
WHERE NOT EXISTS (SELECT 1 FROM wms_material_batch b
                  WHERE b.batch_no=CONCAT('FG-DEMO-SEMI-',d.material_code));

-- 余额行：仓库取半成品仓、库位取该仓第一个普通可用库位，公共库存（work_order_id=0）。
INSERT INTO wms_inventory
    (warehouse_id,location_id,material_id,batch_id,available_quantity,locked_quantity,
     inspection_quantity,work_order_id,remark,version,created_time,updated_time,deleted_flag)
SELECT w.warehouse_id,loc.location_id,d.material_id,b.batch_id,0,0,0,0,
       '演练半成品仓期初库存；非真实生产产出',0,NOW(),NOW(),0
FROM lamp_demo_semi_target d
JOIN wms_material_batch b ON b.batch_no=CONCAT('FG-DEMO-SEMI-',d.material_code) AND b.deleted_flag=0
JOIN wms_warehouse w ON w.warehouse_code='LAMP-WH-SEMI' AND w.deleted_flag=0 AND w.status='ENABLE'
JOIN wms_location loc ON loc.location_id=(
        SELECT MIN(l.location_id) FROM wms_location l
        WHERE l.warehouse_id=w.warehouse_id AND l.deleted_flag=0 AND l.location_type='NORMAL'
          AND l.status IN ('IDLE','OCCUPIED','ENABLE'))
WHERE NOT EXISTS (SELECT 1 FROM wms_inventory i
                  WHERE i.warehouse_id=w.warehouse_id AND i.location_id=loc.location_id
                    AND i.material_id=d.material_id AND i.batch_id=b.batch_id
                    AND i.work_order_id=0 AND i.deleted_flag=0);

-- 差额：按「目标数量 − 当前可用量」计算，已消耗的演练库存不会因重跑再补回。
CREATE TEMPORARY TABLE lamp_demo_semi_delta AS
SELECT d.material_id,i.inventory_id,i.batch_id,i.warehouse_id,i.location_id,
       GREATEST(d.target_quantity-i.available_quantity,0) AS delta
FROM lamp_demo_semi_target d
JOIN wms_material_batch b ON b.batch_no=CONCAT('FG-DEMO-SEMI-',d.material_code) AND b.deleted_flag=0
JOIN wms_warehouse w ON w.warehouse_code='LAMP-WH-SEMI' AND w.deleted_flag=0
JOIN wms_inventory i ON i.material_id=d.material_id AND i.batch_id=b.batch_id
    AND i.warehouse_id=w.warehouse_id AND i.work_order_id=0 AND i.deleted_flag=0;

INSERT INTO wms_stock_movement
    (movement_no,movement_type,source_order_type,source_order_id,material_id,batch_id,
     warehouse_id,location_id,work_order_id,quantity,direction,operation_time,remark,
     version,created_time,deleted_flag)
SELECT CONCAT('LAMP-DEMO-TOPUP-SEMI-V1-',d.material_id),'ADJUST','DEMO_SEED',d.inventory_id,
       d.material_id,d.batch_id,d.warehouse_id,d.location_id,0,d.delta,'IN',NOW(),
       '一次性演练期初调整；非采购收货、非真实生产产出',0,NOW(),0
FROM lamp_demo_semi_delta d
WHERE d.delta>0
  AND NOT EXISTS (SELECT 1 FROM wms_stock_movement movement
                  WHERE movement.movement_no=CONCAT('LAMP-DEMO-TOPUP-SEMI-V1-',d.material_id));

UPDATE wms_inventory i JOIN lamp_demo_semi_delta d ON d.inventory_id=i.inventory_id
SET i.available_quantity=i.available_quantity+d.delta,i.version=i.version+1,i.updated_time=NOW()
WHERE d.delta>0;

DROP TEMPORARY TABLE lamp_demo_semi_delta;
DROP TEMPORARY TABLE lamp_demo_semi_target;
COMMIT;
