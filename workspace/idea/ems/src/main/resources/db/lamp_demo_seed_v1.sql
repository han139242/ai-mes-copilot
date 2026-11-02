-- ============================================================================
-- 台灯 MES 演示环境种子数据 V1
-- 目标：准备台灯组织、班次、能力和待检来料；执行链仍须单独完成验收，
--       补齐演练前基础资料，不代表 D1—D4 已完成演示验收。
-- 幂等：全部使用 INSERT ... ON DUPLICATE KEY UPDATE 或带条件的 UPDATE，
--       重复执行不会产生重复数据，也不会覆盖演示过程中产生的业务单据。
-- 前置：schema.sql + lamp_*_v1/v18 迁移已执行（87 张表）。
-- ============================================================================
SET NAMES utf8mb4;
-- 仅限独立台灯库；请通过受保护入口执行，禁止使用 mysql --force 忽略错误。
SET @lamp_scope_sql=IF(DATABASE() REGEXP '^mes_lamp_[A-Za-z0-9_]+$', 'DO 0', 'SELECT * FROM LAMP_ERROR_ISOLATED_DATABASE_REQUIRED');
PREPARE lamp_scope_stmt FROM @lamp_scope_sql;
EXECUTE lamp_scope_stmt;
DEALLOCATE PREPARE lamp_scope_stmt;

SET @now = NOW();

-- ---------------------------------------------------------------------------
-- 1. 车间 / 产线：启用并给出人工日产能
-- ---------------------------------------------------------------------------
UPDATE factory_workshop SET status = 'ENABLE', updated_time = @now WHERE deleted_flag = 0 AND workshop_code LIKE 'LAMP-%';

-- 灯头线按 F10 节拍 6min/台、单班 240min 折算 40 台/班，两班 80 台/日
UPDATE factory_line SET status = 'RUNNING', daily_capacity = 80, updated_time = @now WHERE deleted_flag=0 AND line_code = 'LAMP-LINE-HEAD';
UPDATE factory_line SET status = 'RUNNING', daily_capacity = 400, updated_time = @now WHERE deleted_flag=0 AND line_code = 'LAMP-LINE-ARM';
UPDATE factory_line SET status = 'RUNNING', daily_capacity = 300, updated_time = @now WHERE deleted_flag=0 AND line_code = 'LAMP-LINE-BASE';
UPDATE factory_line SET status = 'RUNNING', daily_capacity = 200, updated_time = @now WHERE deleted_flag=0 AND line_code = 'LAMP-LINE-FINAL';

-- ---------------------------------------------------------------------------
-- 2. 工位：全部启用（含 WS-* 共享作业资源）
-- ---------------------------------------------------------------------------
UPDATE factory_station SET status = 'RUNNING', updated_time = @now WHERE deleted_flag = 0 AND station_code LIKE 'LAMP-%';

-- 台灯产线上的共享作业资源（WS-*）同样是工序绑定的唯一工位：
-- 整机 F10 只绑 WS-FG-01、F20 只绑 WS-AGING-01，不启用它们时制造准备会判「缺少可用工位」。
UPDATE factory_station st
JOIN factory_line l ON l.line_id = st.line_id AND l.deleted_flag = 0 AND l.line_code LIKE 'LAMP-%'
   SET st.status = 'RUNNING', st.updated_time = @now
 WHERE st.deleted_flag = 0 AND st.station_code LIKE 'WS-%';

-- ---------------------------------------------------------------------------
-- 3. 产线产品能力：启用
-- ---------------------------------------------------------------------------
UPDATE mfg_line_product_capability c JOIN mdm_product p ON p.product_id=c.product_id AND p.product_code LIKE 'LAMP-%' SET c.status='ENABLE', c.updated_time=@now WHERE c.deleted_flag=0;

-- ---------------------------------------------------------------------------
-- 4. 产线周历：只启用已有台灯周历，不新增周末或班次
-- ---------------------------------------------------------------------------
UPDATE mfg_line_calendar_rule c JOIN factory_line l ON l.line_id=c.line_id AND l.line_code LIKE 'LAMP-%' SET c.status='ENABLE', c.updated_time=@now WHERE c.deleted_flag=0;

-- 周末和额外班次须经正式日历维护确认，本脚本不自动扩充可用工时。

-- ---------------------------------------------------------------------------
-- 5. 工序与工位绑定：仅启用台灯产线与台灯工艺关联项
-- ---------------------------------------------------------------------------
UPDATE factory_station_operation b
JOIN factory_station st ON st.station_id=b.station_id AND st.deleted_flag=0
JOIN factory_line l ON l.line_id=st.line_id AND l.line_code LIKE 'LAMP-%' AND l.deleted_flag=0
SET b.status='ENABLE'
WHERE b.deleted_flag=0 AND EXISTS (
 SELECT 1 FROM process_route_item ri JOIN process_route r ON r.route_id=ri.route_id AND r.deleted_flag=0
 JOIN mdm_product p ON p.product_id=r.product_id AND p.product_code LIKE 'LAMP-%' AND p.deleted_flag=0
 WHERE ri.operation_id=b.operation_id AND ri.deleted_flag=0
);

-- ---------------------------------------------------------------------------
-- 6. 工艺路线：6 条路线全部发布
-- ---------------------------------------------------------------------------
-- 参数、路线和检验依据须在管理页面逐项确认/发布，本脚本不代替审核。

-- ---------------------------------------------------------------------------
-- 7. 部门 / 员工 / 班组成员
-- ---------------------------------------------------------------------------
INSERT INTO org_department (department_code, department_name, parent_id, manager_id, remark, version, created_time, updated_time, deleted_flag)
VALUES ('LAMP-DEPT-PROD', '台灯生产部', 1, NULL, '台灯演练演示部门', 0, @now, @now, 0)
ON DUPLICATE KEY UPDATE department_name = VALUES(department_name), updated_time = @now, deleted_flag = 0;

SET @dept_prod = (SELECT department_id FROM org_department WHERE department_code = 'LAMP-DEPT-PROD' LIMIT 1);

INSERT INTO org_employee (employee_no, employee_name, department_id, position_name, skill_level, status, remark, version, created_time, updated_time, deleted_flag)
VALUES
 ('LAMP-EMP-001', '张明', @dept_prod, '灯头装配工', '高级', 'ENABLE', '演示操作工', 0, @now, @now, 0),
 ('LAMP-EMP-002', '李强', @dept_prod, '灯臂装配工', '中级', 'ENABLE', '演示操作工', 0, @now, @now, 0),
 ('LAMP-EMP-003', '王芳', @dept_prod, '底座装配工', '高级', 'ENABLE', '演示操作工', 0, @now, @now, 0),
 ('LAMP-EMP-004', '赵磊', @dept_prod, '整机总装工', '高级', 'ENABLE', '演示操作工', 0, @now, @now, 0),
 ('LAMP-EMP-005', '陈静', @dept_prod, '质量检验员', '中级', 'ENABLE', '演示质检员', 0, @now, @now, 0),
 ('LAMP-EMP-006', '刘洋', @dept_prod, '仓储管理员', '中级', 'ENABLE', '演示仓管员', 0, @now, @now, 0)
ON DUPLICATE KEY UPDATE employee_name = VALUES(employee_name), department_id = VALUES(department_id),
    position_name = VALUES(position_name), status = 'ENABLE', updated_time = @now, deleted_flag = 0;

SET @emp1 = (SELECT employee_id FROM org_employee WHERE employee_no = 'LAMP-EMP-001' LIMIT 1);
SET @emp2 = (SELECT employee_id FROM org_employee WHERE employee_no = 'LAMP-EMP-002' LIMIT 1);
SET @emp3 = (SELECT employee_id FROM org_employee WHERE employee_no = 'LAMP-EMP-003' LIMIT 1);
SET @emp4 = (SELECT employee_id FROM org_employee WHERE employee_no = 'LAMP-EMP-004' LIMIT 1);
SET @emp5 = (SELECT employee_id FROM org_employee WHERE employee_no = 'LAMP-EMP-005' LIMIT 1);
SET @emp6 = (SELECT employee_id FROM org_employee WHERE employee_no = 'LAMP-EMP-006' LIMIT 1);

UPDATE org_team SET leader_id = @emp1, updated_time = @now WHERE team_code = 'LAMP-TEAM-COMPONENT';
UPDATE org_team SET leader_id = @emp4, updated_time = @now WHERE team_code = 'LAMP-TEAM-FINAL';
UPDATE org_team SET leader_id = @emp5, updated_time = @now WHERE team_code = 'LAMP-TEAM-QUALITY';

SET @team_component = (SELECT team_id FROM org_team WHERE team_code = 'LAMP-TEAM-COMPONENT' LIMIT 1);
SET @team_final = (SELECT team_id FROM org_team WHERE team_code = 'LAMP-TEAM-FINAL' LIMIT 1);
SET @team_quality = (SELECT team_id FROM org_team WHERE team_code = 'LAMP-TEAM-QUALITY' LIMIT 1);

INSERT INTO org_team_member (team_id, employee_id, join_date, leave_date, status, created_time, deleted_flag)
SELECT @team_component, @emp1, '2026-01-01', NULL, 'ENABLE', @now, 0 FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM org_team_member WHERE team_id = @team_component AND employee_id = @emp1 AND deleted_flag = 0);
INSERT INTO org_team_member (team_id, employee_id, join_date, leave_date, status, created_time, deleted_flag)
SELECT @team_component, @emp2, '2026-01-01', NULL, 'ENABLE', @now, 0 FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM org_team_member WHERE team_id = @team_component AND employee_id = @emp2 AND deleted_flag = 0);
INSERT INTO org_team_member (team_id, employee_id, join_date, leave_date, status, created_time, deleted_flag)
SELECT @team_component, @emp3, '2026-01-01', NULL, 'ENABLE', @now, 0 FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM org_team_member WHERE team_id = @team_component AND employee_id = @emp3 AND deleted_flag = 0);
INSERT INTO org_team_member (team_id, employee_id, join_date, leave_date, status, created_time, deleted_flag)
SELECT @team_final, @emp4, '2026-01-01', NULL, 'ENABLE', @now, 0 FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM org_team_member WHERE team_id = @team_final AND employee_id = @emp4 AND deleted_flag = 0);
INSERT INTO org_team_member (team_id, employee_id, join_date, leave_date, status, created_time, deleted_flag)
SELECT @team_final, @emp3, '2026-01-01', NULL, 'ENABLE', @now, 0 FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM org_team_member WHERE team_id = @team_final AND employee_id = @emp3 AND deleted_flag = 0);
INSERT INTO org_team_member (team_id, employee_id, join_date, leave_date, status, created_time, deleted_flag)
SELECT @team_quality, @emp5, '2026-01-01', NULL, 'ENABLE', @now, 0 FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM org_team_member WHERE team_id = @team_quality AND employee_id = @emp5 AND deleted_flag = 0);

-- ---------------------------------------------------------------------------
-- 7.5 设备演练状态（只影响台灯设备）
-- ---------------------------------------------------------------------------
-- 设备状态通过设备管理流程维护，不由演示脚本解除停机。

-- 参数、工艺路线、检验标准及方案须在管理页面确认或发布，不由演示脚本代审。
-- ---------------------------------------------------------------------------
-- 9. 演示账号（密码与 admin 相同，复用 pbkdf2 摘要，不存明文）
-- ---------------------------------------------------------------------------
-- 复用 admin 的密码摘要。只装 schema + lamp_*_v1 迁移的新库里没有 admin 账号
-- （admin 来自企业基线），这里退化为固定摘要，免得演示账号拿到 NULL 摘要后全部登录失败。
SET @pwd_hash = COALESCE(
  (SELECT password_hash FROM sys_user WHERE username = 'admin' AND deleted_flag = 0 LIMIT 1),
  'pbkdf2-sha256$210000$M+wzpWI8RvMNb4qOmotGfA==$9FYIRACCEC8nnRKqf+SFGDlcr9wDhs2BNgm35rldXgo=');

-- 登录页演示账号第一位是 admin（系统管理员）。企业基线才建它，新库补齐并绑定超级管理员；
-- 已有摘要的行不覆盖密码，只把缺失的摘要补上、状态拉回启用。
INSERT INTO sys_user (username, password_hash, employee_id, status, login_fail_count, remark, version, created_time, updated_time, deleted_flag)
VALUES ('admin', @pwd_hash, @emp1, 'ENABLE', 0, '台灯演示超级管理员（全功能）', 0, @now, @now, 0)
ON DUPLICATE KEY UPDATE
  password_hash = IF(password_hash IS NULL OR password_hash = '' OR password_hash = '!', VALUES(password_hash), password_hash),
  status = 'ENABLE', login_fail_count = 0, locked_time = NULL, updated_time = @now, deleted_flag = 0;

INSERT INTO sys_user (username, password_hash, employee_id, status, remark, version, created_time, updated_time, deleted_flag)
VALUES
 ('lamp_operator', @pwd_hash, @emp1, 'ENABLE', '台灯演示操作工', 0, @now, @now, 0),
 ('lamp_quality', @pwd_hash, @emp5, 'ENABLE', '台灯演示质检员', 0, @now, @now, 0),
 ('lamp_warehouse', @pwd_hash, @emp6, 'ENABLE', '台灯演示仓管员', 0, @now, @now, 0),
 ('lamp_leader', @pwd_hash, @emp4, 'ENABLE', '台灯演示总装班组长', 0, @now, @now, 0)
ON DUPLICATE KEY UPDATE
  password_hash = IF(password_hash IS NULL OR password_hash = '' OR password_hash = '!', VALUES(password_hash), password_hash),
  employee_id = VALUES(employee_id), status = 'ENABLE', updated_time = @now, deleted_flag = 0;

-- 部门主管账号：预警通知按指标分类路由到 org_department.manager_id 对应的员工，
-- 而通知投递要求该员工有 ENABLE 账号并持有对应业务查看权限。
-- 主管员工来自共享的组织基线（DEPT-PMC/MFG/QMS/WMS/EAM 的 manager_id），
-- 这里按员工档案补登录账号，账号名固定为 mgr_<employee_id>，便于演示时按部门找人对账。
INSERT INTO sys_user (username, password_hash, employee_id, status, remark, version, created_time, updated_time, deleted_flag)
SELECT CONCAT('mgr_', d.manager_id), @pwd_hash, d.manager_id, 'ENABLE',
       CONCAT('部门主管演示账号（', d.department_name, '）'), 0, @now, @now, 0
  FROM org_department d
 WHERE d.deleted_flag = 0 AND d.manager_id IS NOT NULL
   AND d.department_code IN ('DEPT-PMC', 'DEPT-MFG', 'DEPT-QMS', 'DEPT-WMS', 'DEPT-EAM')
   AND NOT EXISTS (SELECT 1 FROM sys_user u WHERE u.username = CONCAT('mgr_', d.manager_id) AND u.deleted_flag = 0)
ON DUPLICATE KEY UPDATE employee_id = VALUES(employee_id), status = 'ENABLE', updated_time = @now, deleted_flag = 0;

-- 角色绑定：角色由 lamp_rbac_v1.sql 建立，这里把演示账号和部门主管接到对应角色。
-- 用角色码而不是硬编码 role_id，重复执行由 sys_user_role 唯一键吸收。
INSERT IGNORE INTO sys_user_role (user_id, role_id, assigned_time, created_time, deleted_flag)
SELECT u.user_id, r.role_id, @now, @now, 0
  FROM sys_user u
  JOIN sys_role r ON r.deleted_flag = 0 AND r.status = 'ENABLE'
  JOIN (
    SELECT 'admin' AS username, 'SUPER_ADMIN' AS role_code
    UNION ALL SELECT 'lamp_operator', 'PRODUCTION_OPERATOR'
    UNION ALL SELECT 'lamp_quality', 'QUALITY_ENGINEER'
    UNION ALL SELECT 'lamp_warehouse', 'WAREHOUSE_MANAGER'
    UNION ALL SELECT 'lamp_leader', 'PRODUCTION_SUPERVISOR'
    UNION ALL SELECT CONCAT('mgr_', d.manager_id), 'PLAN_SCHEDULER'
      FROM org_department d WHERE d.deleted_flag = 0 AND d.department_code = 'DEPT-PMC'
    UNION ALL SELECT CONCAT('mgr_', d.manager_id), 'PRODUCTION_SUPERVISOR'
      FROM org_department d WHERE d.deleted_flag = 0 AND d.department_code = 'DEPT-MFG'
    UNION ALL SELECT CONCAT('mgr_', d.manager_id), 'QUALITY_ENGINEER'
      FROM org_department d WHERE d.deleted_flag = 0 AND d.department_code = 'DEPT-QMS'
    UNION ALL SELECT CONCAT('mgr_', d.manager_id), 'WAREHOUSE_MANAGER'
      FROM org_department d WHERE d.deleted_flag = 0 AND d.department_code = 'DEPT-WMS'
    UNION ALL SELECT CONCAT('mgr_', d.manager_id), 'EQUIPMENT_ENGINEER'
      FROM org_department d WHERE d.deleted_flag = 0 AND d.department_code = 'DEPT-EAM'
  ) demo ON demo.username = u.username AND demo.role_code = r.role_code
 WHERE u.deleted_flag = 0 AND u.status = 'ENABLE';

-- ---------------------------------------------------------------------------
-- 10. 班组日期排班：通过正式排班服务维护
-- ---------------------------------------------------------------------------
-- 人员日期排班须通过排班服务创建和生效，本脚本不把同一班组同时铺满所有产线。

-- ---------------------------------------------------------------------------
-- 11. 演示经销商
-- ---------------------------------------------------------------------------
INSERT INTO mdm_customer (customer_code, customer_name, contact_name, status, remark, version, created_time, updated_time, deleted_flag)
VALUES ('LAMP-CUST-C', '华东照明经销中心', '周经理', 'ENABLE', '演示经销商', 0, @now, @now, 0)
ON DUPLICATE KEY UPDATE customer_name = VALUES(customer_name), status = 'ENABLE', updated_time = @now, deleted_flag = 0;

-- ---------------------------------------------------------------------------
-- 12. 原材料来料批次（等待真实来料检验与采购入库执行）
--     只建批次，不建库存；库存必须由采购入库单确认后真实增加，
--     保证 D2 演示里「入库前库存为 0、入库后库存正确增加」可现场核对。
-- ---------------------------------------------------------------------------
INSERT INTO wms_material_batch (batch_no, material_id, supplier_id, supplier_batch_no, production_date, received_date,
                                quality_status, status, source_order_type, source_order_id, remark, version, created_time, updated_time, deleted_flag)
SELECT CONCAT('LAMP-RM-', m.material_code, '-20261024'), m.material_id,
       CASE WHEN m.material_code IN ('LAMP-M05','LAMP-M06','LAMP-M07','LAMP-M16') THEN 1 ELSE 2 END,
       CONCAT('SUP-', m.material_code, '-01'), CURDATE(), CURDATE(),
       'PENDING', 'INSPECTION', 'PURCHASE_ARRIVAL', NULL, '演示来料批次（待检，不代表已放行）', 0, @now, @now, 0
  FROM mdm_material m
 WHERE m.deleted_flag = 0 AND m.material_code LIKE 'LAMP-M%' AND m.material_type IN ('RAW', 'AUXILIARY')
   AND NOT EXISTS (SELECT 1 FROM wms_material_batch b WHERE b.batch_no = CONCAT('LAMP-RM-', m.material_code, '-20261024') AND b.deleted_flag = 0);

-- ---------------------------------------------------------------------------
-- 13. 设备状态分布（让首页「设备状态」图表与设备台账体现真实运行结构）
--     只调整状态列，不动设备台账本身；老化架必须保持可执行状态，
--     因为 F20 整机老化是 BATCH_EQUIPMENT 工序，制造准备会校验工位有可用设备。
-- ---------------------------------------------------------------------------
UPDATE eam_equipment SET status = 'RUNNING', updated_time = @now
 WHERE deleted_flag = 0 AND equipment_code IN ('LAMP-EQ-TORQUE-HEAD','LAMP-EQ-TORQUE-ARM','LAMP-EQ-HEAD-BENCH','LAMP-EQ-BASE-BENCH','LAMP-EQ-AGING-RACK');
UPDATE eam_equipment SET status = 'IDLE', updated_time = @now
 WHERE deleted_flag = 0 AND equipment_code IN ('LAMP-EQ-SENSOR-BENCH','LAMP-EQ-FUNCTION-BENCH','LAMP-EQ-LUX-BENCH','LAMP-EQ-SPECTRO');
UPDATE eam_equipment SET status = 'MAINTAINING', updated_time = @now
 WHERE deleted_flag = 0 AND equipment_code = 'LAMP-EQ-TEMPERATURE';
