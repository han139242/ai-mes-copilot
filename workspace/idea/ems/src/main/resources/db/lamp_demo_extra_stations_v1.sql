-- 仅用于台灯演示环境：为现有装配、检测、老化工位各增加一个同产线备用工位。
-- 幂等新增；不修改历史任务、库存、默认工位或现有工序能力。
INSERT INTO factory_station (station_code, station_name, line_id, station_type, status, remark, deleted_flag)
SELECT cfg.new_code, cfg.new_name, src.line_id, cfg.station_type, 'RUNNING', 'DEMO_20261031_ALT_STATION', 0
FROM (
    SELECT 'WS-FG-01' AS source_code, 'LAMP-DEMO-ASSY-ALT-01' AS new_code, '演示备用组件装配工位' AS new_name, 'PRODUCTION' AS station_type
    UNION ALL SELECT 'LAMP-ST-FINAL-FUNCTION', 'LAMP-DEMO-TEST-ALT-01', '演示备用功能检测工位', 'INSPECTION'
    UNION ALL SELECT 'WS-AGING-01', 'LAMP-DEMO-AGE-ALT-01', '演示备用老化工位', 'PRODUCTION'
) cfg
JOIN factory_station src ON src.station_code = cfg.source_code AND src.deleted_flag = 0 AND src.status = 'RUNNING'
WHERE NOT EXISTS (SELECT 1 FROM factory_station existing WHERE existing.station_code = cfg.new_code);

INSERT INTO factory_station_operation (station_id, operation_id, is_default, status, deleted_flag)
SELECT dst.station_id, source_binding.operation_id, 0, 'ENABLE', 0
FROM (
    SELECT 'WS-FG-01' AS source_code, 'LAMP-DEMO-ASSY-ALT-01' AS new_code
    UNION ALL SELECT 'LAMP-ST-FINAL-FUNCTION', 'LAMP-DEMO-TEST-ALT-01'
    UNION ALL SELECT 'WS-AGING-01', 'LAMP-DEMO-AGE-ALT-01'
) cfg
JOIN factory_station src ON src.station_code = cfg.source_code AND src.deleted_flag = 0 AND src.status = 'RUNNING'
JOIN factory_station dst ON dst.station_code = cfg.new_code AND dst.deleted_flag = 0 AND dst.line_id = src.line_id
JOIN factory_station_operation source_binding ON source_binding.station_id = src.station_id
    AND source_binding.deleted_flag = 0 AND source_binding.status = 'ENABLE'
WHERE NOT EXISTS (SELECT 1 FROM factory_station_operation existing
    WHERE existing.station_id = dst.station_id AND existing.operation_id = source_binding.operation_id);
