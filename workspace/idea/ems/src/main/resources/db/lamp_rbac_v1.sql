-- LAMP_RBAC_V1：台灯演练库的角色、业务查看权限与角色授权基线。
--
-- 背景：演练库从 schema.sql 起库时 sys_role / sys_user_role / sys_role_menu 都是空的，
--       而 lamp_* 迁移里的授权语句都写成 "JOIN sys_role ... WHERE role_code IN (...)"，
--       角色为空时会静默跳过，所以问题一直被掩盖。后果之一是预警通知找不到收件人：
--       SysUserMapper.listNotificationRecipients 要求
--       "role.role_code='SUPER_ADMIN' OR menu.permission_code=#{requiredPermission}"，
--       角色和权限菜单任一为空，通知就会以 NO_VALID_NOTIFICATION_RECIPIENT 失败。
--
-- 本迁移只做三件事：补齐 8 个标准角色、补齐业务查看权限、把角色和权限连起来。
-- 人员账号与角色绑定属于演示数据，放在 lamp_demo_seed_v1.sql，不在这里造账号。
--
-- 隔离要求：只在演练库执行，不得作用于 mes_local 或共享 mes。
SET NAMES utf8mb4;

SET @lamp_scope_sql = IF(DATABASE() REGEXP '^mes_lamp_[A-Za-z0-9_]+$',
    'DO 0',
    'SELECT * FROM LAMP_ERROR_ISOLATED_DATABASE_REQUIRED');
PREPARE lamp_scope_stmt FROM @lamp_scope_sql;
EXECUTE lamp_scope_stmt;
DEALLOCATE PREPARE lamp_scope_stmt;

SET @lamp_schema = DATABASE();

-- ---------------------------------------------------------------------------
-- 1. 标准角色（与 renix_enterprise_system_baseline.sql 的定义保持一致）
--    sys_role.uk_sys_role_role_code 是唯一键，重复执行由 INSERT IGNORE 吸收。
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO sys_role
  (role_code, role_name, data_scope, status, remark, version, created_time, updated_time, deleted_flag)
VALUES
  ('SUPER_ADMIN', '超级管理员', 'ALL', 'ENABLE', 'LAMP_RBAC_V1: 系统、权限和全部业务的最终管理权限', 0, NOW(), NOW(), 0),
  ('MES_MANAGER', 'MES生产经理', 'ALL', 'ENABLE', 'LAMP_RBAC_V1: 全局运营查看、生产审批和跨域协调', 0, NOW(), NOW(), 0),
  ('PLAN_SCHEDULER', '生产计划员', 'ALL', 'ENABLE', 'LAMP_RBAC_V1: 订单、计划、产能和排程管理', 0, NOW(), NOW(), 0),
  ('PRODUCTION_SUPERVISOR', '生产班组长', 'TEAM', 'ENABLE', 'LAMP_RBAC_V1: 本班组派工、任务、报工和异常处理', 0, NOW(), NOW(), 0),
  ('PRODUCTION_OPERATOR', '生产操作员', 'SELF', 'ENABLE', 'LAMP_RBAC_V1: 本人任务开工、报工、完工和扫码', 0, NOW(), NOW(), 0),
  ('QUALITY_ENGINEER', '质量工程师', 'DEPARTMENT', 'ENABLE', 'LAMP_RBAC_V1: 检验、复检、不合格品和质量追溯', 0, NOW(), NOW(), 0),
  ('WAREHOUSE_MANAGER', '仓库主管', 'DEPARTMENT', 'ENABLE', 'LAMP_RBAC_V1: 收发存、退料、盘点和补货确认', 0, NOW(), NOW(), 0),
  ('EQUIPMENT_ENGINEER', '设备工程师', 'DEPARTMENT', 'ENABLE', 'LAMP_RBAC_V1: 点检、保养、维修和设备状态管理', 0, NOW(), NOW(), 0);

-- ---------------------------------------------------------------------------
-- 2. 业务查看权限
--    预警通知按指标分类路由到部门主管，并把一个查看权限作为收件人准入条件：
--      物料 -> wms:inventory:view      设备 -> eam:equipment:view
--      质量 -> qms:inspection-order:view  计划 -> mfg:production-plan:view
--      生产 -> mfg:work-order:view
--    仓储的责任通知另用 wms:replenishment:view（补货建议）与 wms:return-order:view（退料单）。
--    演练库此前没有这几个权限码，即使有账号也匹配不到收件人。
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO sys_menu
  (parent_id, menu_name, menu_type, permission_code, route_path, remark, version, created_time, updated_time, deleted_flag)
VALUES
  (NULL, '库存查询', 'BUTTON', 'wms:inventory:view', NULL, 'LAMP_RBAC_V1: 查看库存余额与批次可用量', 0, NOW(), NOW(), 0),
  (NULL, '设备台账查询', 'BUTTON', 'eam:equipment:view', NULL, 'LAMP_RBAC_V1: 查看设备台账与设备状态', 0, NOW(), NOW(), 0),
  (NULL, '检验单查询', 'BUTTON', 'qms:inspection-order:view', NULL, 'LAMP_RBAC_V1: 查看来料、过程与出货检验单', 0, NOW(), NOW(), 0),
  (NULL, '生产计划查询', 'BUTTON', 'mfg:production-plan:view', NULL, 'LAMP_RBAC_V1: 查看生产计划与排程结果', 0, NOW(), NOW(), 0),
  (NULL, '生产工单查询', 'BUTTON', 'mfg:work-order:view', NULL, 'LAMP_RBAC_V1: 查看生产工单与工单用料需求', 0, NOW(), NOW(), 0),
  (NULL, '补货建议查询', 'BUTTON', 'wms:replenishment:view', NULL, 'LAMP_RBAC_V1: 查看并确认补货建议（责任通知收件人准入）', 0, NOW(), NOW(), 0),
  (NULL, '退料单查询', 'BUTTON', 'wms:return-order:view', NULL, 'LAMP_RBAC_V1: 查看退料单（责任通知收件人准入）', 0, NOW(), NOW(), 0),
  (NULL, '消息中心', 'BUTTON', 'sys:notification:view', NULL, 'LAMP_RBAC_V1: 查看自己的站内消息（所有在岗账号必备）', 0, NOW(), NOW(), 0),
  (NULL, '消息标记已读', 'BUTTON', 'sys:notification:read', NULL, 'LAMP_RBAC_V1: 把自己收到的消息标记已读', 0, NOW(), NOW(), 0),
  (NULL, '消息确认收到', 'BUTTON', 'sys:notification:ack', NULL, 'LAMP_RBAC_V1: 确认需要回执的消息', 0, NOW(), NOW(), 0);

-- ---------------------------------------------------------------------------
-- 3. 角色授权
--    管理类角色拿全量已注册权限，专业角色只拿本职查看权限，
--    保证"通知能找到人"的同时不把全部权限摊平。
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO sys_role_menu (role_id, menu_id, created_time, deleted_flag)
SELECT r.role_id, m.menu_id, NOW(), 0
  FROM sys_role r
  JOIN sys_menu m ON m.deleted_flag = 0
 WHERE r.deleted_flag = 0 AND r.status = 'ENABLE'
   AND r.role_code IN ('SUPER_ADMIN', 'MES_MANAGER');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id, created_time, deleted_flag)
SELECT r.role_id, m.menu_id, NOW(), 0
  FROM sys_role r
  JOIN sys_menu m
    ON m.deleted_flag = 0
   AND m.permission_code IN ('mfg:production-plan:view', 'mfg:work-order:view',
                             'mfg:supply-allocation:view', 'mfg:supply-source:view',
                             'mfg:command-request:view', 'mdm:bom:expand',
                             'mdm:manufacturing-preparation:view',
                             'ai:alert:configure', 'ai:alert:handle')
 WHERE r.deleted_flag = 0 AND r.status = 'ENABLE' AND r.role_code = 'PLAN_SCHEDULER';

INSERT IGNORE INTO sys_role_menu (role_id, menu_id, created_time, deleted_flag)
SELECT r.role_id, m.menu_id, NOW(), 0
  FROM sys_role r
  JOIN sys_menu m
    ON m.deleted_flag = 0
   AND m.permission_code IN ('mfg:work-order:view', 'mdm:bom:expand',
                             'mdm:manufacturing-preparation:view',
                             'org:team-schedule:view', 'org:team-schedule:release',
                             'ai:alert:handle')
 WHERE r.deleted_flag = 0 AND r.status = 'ENABLE' AND r.role_code = 'PRODUCTION_SUPERVISOR';

INSERT IGNORE INTO sys_role_menu (role_id, menu_id, created_time, deleted_flag)
SELECT r.role_id, m.menu_id, NOW(), 0
  FROM sys_role r
  JOIN sys_menu m
    ON m.deleted_flag = 0
   AND m.permission_code IN ('mfg:work-order:view', 'mdm:bom:expand')
 WHERE r.deleted_flag = 0 AND r.status = 'ENABLE' AND r.role_code = 'PRODUCTION_OPERATOR';

INSERT IGNORE INTO sys_role_menu (role_id, menu_id, created_time, deleted_flag)
SELECT r.role_id, m.menu_id, NOW(), 0
  FROM sys_role r
  JOIN sys_menu m
    ON m.deleted_flag = 0
   AND m.permission_code IN ('qms:inspection-order:view', 'qms:inspection-standard:confirm',
                             'mdm:bom:expand', 'ai:alert:configure', 'ai:alert:handle')
 WHERE r.deleted_flag = 0 AND r.status = 'ENABLE' AND r.role_code = 'QUALITY_ENGINEER';

INSERT IGNORE INTO sys_role_menu (role_id, menu_id, created_time, deleted_flag)
SELECT r.role_id, m.menu_id, NOW(), 0
  FROM sys_role r
  JOIN sys_menu m
    ON m.deleted_flag = 0
   AND m.permission_code IN ('wms:inventory:view', 'wms:sales-outbound:view',
                             'wms:sales-outbound:confirm', 'wms:sales-outbound:inspection',
                             'wms:replenishment:view', 'wms:return-order:view',
                             'mfg:supply-allocation:view', 'mfg:supply-source:view',
                             'mdm:bom:expand', 'ai:alert:configure', 'ai:alert:handle')
 WHERE r.deleted_flag = 0 AND r.status = 'ENABLE' AND r.role_code = 'WAREHOUSE_MANAGER';

INSERT IGNORE INTO sys_role_menu (role_id, menu_id, created_time, deleted_flag)
SELECT r.role_id, m.menu_id, NOW(), 0
  FROM sys_role r
  JOIN sys_menu m
    ON m.deleted_flag = 0
   AND m.permission_code IN ('eam:equipment:view', 'ai:alert:configure', 'ai:alert:handle')
 WHERE r.deleted_flag = 0 AND r.status = 'ENABLE' AND r.role_code = 'EQUIPMENT_ENGINEER';

-- 站内消息是所有在岗角色的基础能力：收件人能看到并处理发给自己的通知，
-- 否则通知虽然投递成功，用户打开消息中心仍会被 403 挡住。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id, created_time, deleted_flag)
SELECT r.role_id, m.menu_id, NOW(), 0
  FROM sys_role r
  JOIN sys_menu m
    ON m.deleted_flag = 0
   AND m.permission_code IN ('sys:notification:view', 'sys:notification:read', 'sys:notification:ack')
 WHERE r.deleted_flag = 0 AND r.status = 'ENABLE';

-- ---------------------------------------------------------------------------
-- 4. 完整权限目录
--    演练库从 schema.sql 起库，sys_menu 里只有 lamp_* 迁移自己声明的几个码。
--    前端每个资源页、每个按钮都按"域:资源:动作"校验权限，缺码的后果不是报错，
--    而是用户登录后点任何页面都落到 403。这里按前端资源清单与后端
--    PermissionCodeResolver 的特殊规则把目录补全。
-- ---------------------------------------------------------------------------

-- 4a. PC 资源查看权限
DROP TEMPORARY TABLE IF EXISTS `lamp_rbac_menu`;
CREATE TEMPORARY TABLE `lamp_rbac_menu` (
  `permission_code` VARCHAR(128) PRIMARY KEY,
  `menu_name` VARCHAR(128) NOT NULL,
  `route_path` VARCHAR(255) NULL,
  `menu_type` VARCHAR(16) NOT NULL DEFAULT 'MENU',
  `remark` VARCHAR(255) NOT NULL
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

INSERT IGNORE INTO `lamp_rbac_menu` (permission_code, menu_name, route_path, menu_type, remark) VALUES
  ('eam:equipment-category:view', '设备分类', '/eam/equipment-categories', 'MENU', 'PC资源页'),
  ('eam:equipment:view', '设备台账', '/eam/equipment', 'MENU', 'PC资源页'),
  ('eam:equipment-status-log:view', '设备状态日志', '/eam/equipment-status', 'MENU', 'PC资源页'),
  ('eam:maintenance-plan:view', '保养计划', '/eam/maintenance-plans', 'MENU', 'PC资源页'),
  ('eam:maintenance-record:view', '保养记录', '/eam/maintenance-records', 'MENU', 'PC资源页'),
  ('eam:spot-check-plan:view', '点检计划', '/eam/spot-check-plans', 'MENU', 'PC资源页'),
  ('eam:spot-check-record:view', '点检记录', '/eam/spot-check-records', 'MENU', 'PC资源页'),
  ('eam:repair-order:view', '维修工单', '/eam/repair-orders', 'MENU', 'PC资源页'),
  ('factory:workshop:view', '车间管理', '/factory/workshops', 'MENU', 'PC资源页'),
  ('factory:line:view', '产线管理', '/factory/lines', 'MENU', 'PC资源页'),
  ('factory:station:view', '工位管理', '/factory/stations', 'MENU', 'PC资源页'),
  ('factory:station-operation:view', '工位工序', '/factory/station-operations', 'MENU', 'PC资源页'),
  ('mdm:unit:view', '计量单位', '/mdm/units', 'MENU', 'PC资源页'),
  ('mdm:supplier:view', '供应商管理', '/mdm/suppliers', 'MENU', 'PC资源页'),
  ('mdm:customer:view', '客户管理', '/mdm/customers', 'MENU', 'PC资源页'),
  ('mdm:material-category:view', '物料分类', '/mdm/material-categories', 'MENU', 'PC资源页'),
  ('mdm:material:view', '物料管理', '/mdm/materials', 'MENU', 'PC资源页'),
  ('mdm:product:view', '产品管理', '/mdm/products', 'MENU', 'PC资源页'),
  ('mdm:bom:view', '产品物料清单', '/mdm/boms', 'MENU', 'PC资源页'),
  ('mdm:bom-item:view', '物料清单明细', '/mdm/bom-items', 'MENU', 'PC资源页'),
  ('mfg:line-product-capability:view', '产线产品能力', '/mfg/line-product-capabilities', 'MENU', 'PC资源页'),
  ('mfg:line-calendar-rule:view', '产线周历规则', '/mfg/line-calendar-rules', 'MENU', 'PC资源页'),
  ('mfg:production-order:view', '生产订单', '/mfg/production-orders', 'MENU', 'PC资源页'),
  ('mfg:production-plan:view', '生产计划', '/mfg/production-plans', 'MENU', 'PC资源页'),
  ('mfg:work-order:view', '生产工单', '/mfg/work-orders', 'MENU', 'PC资源页'),
  ('mfg:work-order-material:view', '工单用料需求', '/mfg/work-order-materials', 'MENU', 'PC资源页'),
  ('mfg:dispatch-order:view', '派工管理', '/mfg/dispatch-orders', 'MENU', 'PC资源页'),
  ('mfg:task:view', '生产任务', '/mfg/tasks', 'MENU', 'PC资源页'),
  ('mfg:task-operator:view', '任务人员', '/mfg/task-operators', 'MENU', 'PC资源页'),
  ('mfg:work-report:view', '生产报工', '/mfg/work-reports', 'MENU', 'PC资源页'),
  ('mfg:report-material:view', '报工用料', '/mfg/report-materials', 'MENU', 'PC资源页'),
  ('mfg:exception:view', '生产异常', '/mfg/exceptions', 'MENU', 'PC资源页'),
  ('mfg:andon-alert:view', '安灯告警', '/mfg/andon-alerts', 'MENU', 'PC资源页'),
  ('org:department:view', '部门管理', '/org/departments', 'MENU', 'PC资源页'),
  ('org:employee:view', '员工管理', '/org/employees', 'MENU', 'PC资源页'),
  ('org:team:view', '班组管理', '/org/teams', 'MENU', 'PC资源页'),
  ('org:shift:view', '班次管理', '/org/shifts', 'MENU', 'PC资源页'),
  ('org:team-member:view', '班组成员', '/org/team-members', 'MENU', 'PC资源页'),
  ('org:team-schedule:view', '班组排班', '/org/team-schedules', 'MENU', 'PC资源页'),
  ('process:operation:view', '工序管理', '/process/operations', 'MENU', 'PC资源页'),
  ('process:parameter:view', '工艺参数', '/process/parameters', 'MENU', 'PC资源页'),
  ('process:route:view', '工艺路线', '/process/routes', 'MENU', 'PC资源页'),
  ('process:route-item:view', '路线工序明细', '/process/route-items', 'MENU', 'PC资源页'),
  ('process:work-instruction:view', '作业指导书', '/process/instructions', 'MENU', 'PC资源页'),
  ('qms:inspection-item:view', '检验项目', '/qms/inspection-items', 'MENU', 'PC资源页'),
  ('qms:inspection-standard:view', '检验标准', '/qms/standards', 'MENU', 'PC资源页'),
  ('qms:inspection-scheme:view', '检验方案', '/qms/schemes', 'MENU', 'PC资源页'),
  ('qms:inspection-scheme-item:view', '方案检验项目', '/qms/scheme-items', 'MENU', 'PC资源页'),
  ('qms:inspection-order:view', '检验单管理', '/qms/inspection-orders', 'MENU', 'PC资源页'),
  ('qms:inspection-result:view', '检验结果明细', '/qms/inspection-results', 'MENU', 'PC资源页'),
  ('qms:nonconforming-order:view', '不合格品处理', '/qms/nonconforming-orders', 'MENU', 'PC资源页'),
  ('qms:nonconforming-detail:view', '不合格品明细', '/qms/nonconforming-details', 'MENU', 'PC资源页'),
  ('sys:user:view', '用户管理', '/sys/users', 'MENU', 'PC资源页'),
  ('sys:role:view', '角色管理', '/sys/roles', 'MENU', 'PC资源页'),
  ('sys:menu:view', '菜单管理', '/sys/menus', 'MENU', 'PC资源页'),
  ('sys:dict-type:view', '字典类型', '/sys/dict-types', 'MENU', 'PC资源页'),
  ('sys:dict-data:view', '字典数据', '/sys/dict-data', 'MENU', 'PC资源页'),
  ('sys:system-param:view', '系统参数', '/sys/parameters', 'MENU', 'PC资源页'),
  ('sys:file:view', '附件文件', '/sys/files', 'MENU', 'PC资源页'),
  ('sys:operation-log:view', '操作日志', '/sys/operation-logs', 'MENU', 'PC资源页'),
  ('sys:status-change-log:view', '状态变更日志', '/sys/status-logs', 'MENU', 'PC资源页'),
  ('sys:approval-record:view', '审批记录', '/sys/approvals', 'MENU', 'PC资源页'),
  ('sys:user-role:view', '用户角色关联', '/sys/user-roles', 'MENU', 'PC资源页'),
  ('sys:role-menu:view', '角色菜单关联', '/sys/role-menus', 'MENU', 'PC资源页'),
  ('trace:barcode:view', '条码管理', '/trace/barcodes', 'MENU', 'PC资源页'),
  ('trace:product-lot:view', '产品批次追溯', '/trace/product-lots', 'MENU', 'PC资源页'),
  ('trace:material-usage:view', '物料使用追溯', '/trace/material-usages', 'MENU', 'PC资源页'),
  ('wms:warehouse:view', '仓库管理', '/wms/warehouses', 'MENU', 'PC资源页'),
  ('wms:location:view', '库位管理', '/wms/locations', 'MENU', 'PC资源页'),
  ('wms:material-batch:view', '物料批次', '/wms/material-batches', 'MENU', 'PC资源页'),
  ('wms:inventory:view', '库存余额', '/wms/inventory', 'MENU', 'PC资源页'),
  ('wms:stock-movement:view', '库存流水', '/wms/movements', 'MENU', 'PC资源页'),
  ('wms:inbound-order:view', '入库管理', '/wms/inbound-orders', 'MENU', 'PC资源页'),
  ('wms:inbound-detail:view', '入库明细', '/wms/inbound-details', 'MENU', 'PC资源页'),
  ('wms:outbound-order:view', '出库管理', '/wms/outbound-orders', 'MENU', 'PC资源页'),
  ('wms:outbound-detail:view', '出库明细', '/wms/outbound-details', 'MENU', 'PC资源页'),
  ('wms:return-order:view', '退料管理', '/wms/return-orders', 'MENU', 'PC资源页'),
  ('wms:return-detail:view', '退料明细', '/wms/return-details', 'MENU', 'PC资源页'),
  ('wms:stocktake-order:view', '盘点管理', '/wms/stocktakes', 'MENU', 'PC资源页'),
  ('wms:stocktake-detail:view', '盘点明细', '/wms/stocktake-details', 'MENU', 'PC资源页'),
  ('wms:replenishment:view', '补货建议', '/wms/replenishment', 'MENU', 'PC资源页'),
  ('wms:sales-outbound:view', '销售发货', '/wms/sales-outbound', 'MENU', 'PC资源页');

-- 4b. 业务域目录（前端 /module/:moduleKey 落地页）
INSERT IGNORE INTO `lamp_rbac_menu` (permission_code, menu_name, route_path, menu_type, remark) VALUES
  ('module:mfg:view', '生产制造', '/module/mfg', 'DIRECTORY', '业务域目录'),
  ('module:wms:view', '仓储库存', '/module/wms', 'DIRECTORY', '业务域目录'),
  ('module:qms:view', '质量管理', '/module/qms', 'DIRECTORY', '业务域目录'),
  ('module:mdm:view', '主数据', '/module/mdm', 'DIRECTORY', '业务域目录'),
  ('module:process:view', '工艺管理', '/module/process', 'DIRECTORY', '业务域目录'),
  ('module:factory:view', '工厂建模', '/module/factory', 'DIRECTORY', '业务域目录'),
  ('module:eam:view', '设备运维', '/module/eam', 'DIRECTORY', '业务域目录'),
  ('module:trace:view', '条码追溯', '/module/trace', 'DIRECTORY', '业务域目录'),
  ('module:org:view', '组织人事', '/module/org', 'DIRECTORY', '业务域目录'),
  ('module:sys:view', '系统管理', '/module/sys', 'DIRECTORY', '业务域目录');

-- 4c. 非资源表的固定能力
INSERT IGNORE INTO `lamp_rbac_menu` (permission_code, menu_name, route_path, menu_type, remark) VALUES
  ('dashboard:summary:view', '首页', '/dashboard', 'MENU', '首页看板'),
  ('dashboard:big-screen:view', '生产运营大屏', '/big-screen/production', 'MENU', '生产运营大屏'),
  ('ai:assistant:use', 'AI智能助手', NULL, 'MENU', '智能问答、自动化规则、异常闭环与预警中心'),
  ('mfg:schedule-scenario:view', '智能排产工作台', '/mfg/schedule-workbench', 'MENU', '排程场景与排产工作台'),
  ('mdm:bom:expand', 'BOM需求分析', '/mdm/bom-explorer', 'MENU', 'BOM 递归展开与需求分析'),
  ('mdm:manufacturing-preparation:view', '制造准备检查', '/mdm/manufacturing-preparation', 'MENU', '制造准备齐套检查'),
  ('mfg:supply-allocation:view', '供给预留查询', NULL, 'BUTTON', '工单合格库存预留明细'),
  ('mfg:supply-allocation:reserve', '供给预留', NULL, 'BUTTON', '工单合格库存预留'),
  ('mfg:supply-allocation:release', '供给预留释放', NULL, 'BUTTON', '撤回工单时释放预留'),
  ('mfg:supply-source:view', '供给来源明细查询', NULL, 'BUTTON', '组件多补产拆分来源'),
  ('mfg:command-request:view', '命令请求幂等查询', NULL, 'BUTTON', '命令请求幂等登记查询'),
  ('ai:alert:configure', '预警规则配置', NULL, 'BUTTON', '新增/修改/删除预警规则与限度'),
  ('ai:alert:handle', '预警事件处置', NULL, 'BUTTON', '确认、关闭、手动扫描预警事件'),
  ('org:employee:phone:view-full', '员工手机号完整查看', NULL, 'BUTTON', '受保护字段：查看完整手机号'),
  ('org:employee:phone:write', '员工手机号修改', NULL, 'BUTTON', '受保护字段：修改手机号'),
  ('mdm:customer:contact-phone:view-full', '客户电话完整查看', NULL, 'BUTTON', '受保护字段：查看完整联系电话'),
  ('mdm:customer:contact-phone:write', '客户电话修改', NULL, 'BUTTON', '受保护字段：修改联系电话'),
  ('mdm:supplier:contact-phone:view-full', '供应商电话完整查看', NULL, 'BUTTON', '受保护字段：查看完整联系电话'),
  ('mdm:supplier:contact-phone:write', '供应商电话修改', NULL, 'BUTTON', '受保护字段：修改联系电话');

-- 4d. 特殊业务动作权限（下达、确认、撤回、报工等）
INSERT IGNORE INTO `lamp_rbac_menu` (permission_code, menu_name, route_path, menu_type, remark) VALUES
  ('eam:repair-order:assign', 'repair-order', NULL, 'BUTTON', '业务动作权限'),
  ('eam:repair-order:close', 'repair-order', NULL, 'BUTTON', '业务动作权限'),
  ('mdm:product-parameter:confirm', 'product-parameter', NULL, 'BUTTON', '业务动作权限'),
  ('mfg:andon-alert:close', 'andon-alert', NULL, 'BUTTON', '业务动作权限'),
  ('mfg:andon-alert:trigger', 'andon-alert', NULL, 'BUTTON', '业务动作权限'),
  ('mfg:dispatch-order:generate', 'dispatch-order', NULL, 'BUTTON', '业务动作权限'),
  ('mfg:dispatch-order:release', 'dispatch-order', NULL, 'BUTTON', '业务动作权限'),
  ('mfg:dispatch-order:withdraw', 'dispatch-order', NULL, 'BUTTON', '业务动作权限'),
  ('mfg:exception:handle', 'exception', NULL, 'BUTTON', '业务动作权限'),
  ('mfg:production-order:release', 'production-order', NULL, 'BUTTON', '业务动作权限'),
  ('mfg:production-order:withdraw', 'production-order', NULL, 'BUTTON', '业务动作权限'),
  ('mfg:production-plan:generate', 'production-plan', NULL, 'BUTTON', '业务动作权限'),
  ('mfg:production-plan:release', 'production-plan', NULL, 'BUTTON', '业务动作权限'),
  ('mfg:production-plan:withdraw', 'production-plan', NULL, 'BUTTON', '业务动作权限'),
  ('mfg:schedule-scenario:ai-advice', 'schedule-scenario', NULL, 'BUTTON', '业务动作权限'),
  ('mfg:schedule-scenario:confirm', 'schedule-scenario', NULL, 'BUTTON', '业务动作权限'),
  ('mfg:schedule-scenario:validate', 'schedule-scenario', NULL, 'BUTTON', '业务动作权限'),
  ('mfg:task:controlled-close', 'task', NULL, 'BUTTON', '业务动作权限'),
  ('mfg:task:finish', 'task', NULL, 'BUTTON', '业务动作权限'),
  ('mfg:task:start', 'task', NULL, 'BUTTON', '业务动作权限'),
  ('mfg:task:withdraw', 'task', NULL, 'BUTTON', '业务动作权限'),
  ('mfg:work-order:generate', 'work-order', NULL, 'BUTTON', '业务动作权限'),
  ('mfg:work-order:material-issue', 'work-order', NULL, 'BUTTON', '业务动作权限'),
  ('mfg:work-order:release', 'work-order', NULL, 'BUTTON', '业务动作权限'),
  ('mfg:work-order:start', 'work-order', NULL, 'BUTTON', '业务动作权限'),
  ('mfg:work-order:withdraw', 'work-order', NULL, 'BUTTON', '业务动作权限'),
  ('mfg:work-report:audit', 'work-report', NULL, 'BUTTON', '业务动作权限'),
  ('mfg:work-report:submit', 'work-report', NULL, 'BUTTON', '业务动作权限'),
  ('mfg:work-report:withdraw', 'work-report', NULL, 'BUTTON', '业务动作权限'),
  ('org:team-schedule:release', 'team-schedule', NULL, 'BUTTON', '业务动作权限'),
  ('qms:inspection-order:submit', 'inspection-order', NULL, 'BUTTON', '业务动作权限'),
  ('qms:inspection-order:withdraw', 'inspection-order', NULL, 'BUTTON', '业务动作权限'),
  ('qms:inspection-result:batch-save', 'inspection-result', NULL, 'BUTTON', '业务动作权限'),
  ('qms:inspection-standard:confirm', 'inspection-standard', NULL, 'BUTTON', '业务动作权限'),
  ('qms:nonconforming-order:approve', 'nonconforming-order', NULL, 'BUTTON', '业务动作权限'),
  ('qms:nonconforming-order:complete', 'nonconforming-order', NULL, 'BUTTON', '业务动作权限'),
  ('qms:nonconforming-order:generate', 'nonconforming-order', NULL, 'BUTTON', '业务动作权限'),
  ('sys:file:download', 'file', NULL, 'BUTTON', '业务动作权限'),
  ('sys:file:upload', 'file', NULL, 'BUTTON', '业务动作权限'),
  ('sys:notification:ack', 'notification', NULL, 'BUTTON', '业务动作权限'),
  ('sys:notification:audit', 'notification', NULL, 'BUTTON', '业务动作权限'),
  ('sys:notification:read', 'notification', NULL, 'BUTTON', '业务动作权限'),
  ('sys:role:assign-menu', 'role', NULL, 'BUTTON', '业务动作权限'),
  ('sys:user:assign-role', 'user', NULL, 'BUTTON', '业务动作权限'),
  ('sys:user:reset-password', 'user', NULL, 'BUTTON', '业务动作权限'),
  ('trace:barcode:generate', 'barcode', NULL, 'BUTTON', '业务动作权限'),
  ('trace:barcode:scan', 'barcode', NULL, 'BUTTON', '业务动作权限'),
  ('trace:material-usage:reverse-trace', 'material-usage', NULL, 'BUTTON', '业务动作权限'),
  ('trace:product-lot:trace', 'product-lot', NULL, 'BUTTON', '业务动作权限'),
  ('wms:inbound-order:confirm', 'inbound-order', NULL, 'BUTTON', '业务动作权限'),
  ('wms:inbound-order:create', 'inbound-order', NULL, 'BUTTON', '业务动作权限'),
  ('wms:inbound-order:withdraw', 'inbound-order', NULL, 'BUTTON', '业务动作权限'),
  ('wms:outbound-order:confirm', 'outbound-order', NULL, 'BUTTON', '业务动作权限'),
  ('wms:outbound-order:withdraw', 'outbound-order', NULL, 'BUTTON', '业务动作权限'),
  ('wms:replenishment:generate', 'replenishment', NULL, 'BUTTON', '业务动作权限'),
  ('wms:return-order:confirm', 'return-order', NULL, 'BUTTON', '业务动作权限'),
  ('wms:return-order:withdraw', 'return-order', NULL, 'BUTTON', '业务动作权限'),
  ('wms:sales-outbound:confirm', 'sales-outbound', NULL, 'BUTTON', '业务动作权限'),
  ('wms:sales-outbound:inspection', 'sales-outbound', NULL, 'BUTTON', '业务动作权限'),
  ('wms:stocktake-order:finish', 'stocktake-order', NULL, 'BUTTON', '业务动作权限');

INSERT IGNORE INTO sys_menu
  (parent_id, menu_name, menu_type, permission_code, route_path, remark, version, created_time, updated_time, deleted_flag)
SELECT NULL, menu_name, menu_type, permission_code, route_path,
       CONCAT('LAMP_RBAC_V1: ', remark), 0, NOW(), NOW(), 0
  FROM `lamp_rbac_menu`;

-- 4e. 资源动作权限：为上面每个资源查看码补 新增/修改/删除/导出
DROP TEMPORARY TABLE IF EXISTS `lamp_rbac_action`;
CREATE TEMPORARY TABLE `lamp_rbac_action` (
  `action_code` VARCHAR(16) PRIMARY KEY,
  `action_name` VARCHAR(16) NOT NULL
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
INSERT INTO `lamp_rbac_action` VALUES
  ('create', '新增'), ('update', '修改'), ('delete', '删除'), ('export', '导出');

INSERT IGNORE INTO sys_menu
  (parent_id, menu_name, menu_type, permission_code, route_path, remark, version, created_time, updated_time, deleted_flag)
SELECT m.menu_id, CONCAT(m.menu_name, '-', a.action_name), 'BUTTON',
       CONCAT(SUBSTRING(m.permission_code, 1, CHAR_LENGTH(m.permission_code) - 5), ':', a.action_code),
       NULL, 'LAMP_RBAC_V1: 资源动作权限', 0, NOW(), NOW(), 0
  FROM sys_menu m
  CROSS JOIN `lamp_rbac_action` a
 WHERE m.deleted_flag = 0
   AND m.menu_type = 'MENU'
   AND m.permission_code LIKE '%:view'
   AND m.permission_code NOT LIKE 'module:%'
   AND m.remark LIKE 'LAMP_RBAC_V1:%';

-- 4f. 补漏：检验单是完整 CRUD 资源页（前端 qms.js 的 qms-inspection-order 走 /full 建单），
--     但它的查看码在第 2 节先以 BUTTON 形态插入（给责任通知做收件人准入），
--     到 4a 想再插 MENU 形态时被 permission_code 唯一键挡掉（INSERT IGNORE 丢弃），
--     于是菜单类型停在 BUTTON，4e 只扫 MENU 就匹配不到，create/update/delete/export 四个码全缺。
--     后果：除了超管，任何角色新建检验单都 403，来料检验与终检两条链都走不通。
--     这里按显式清单补回，不动 4e 的 MENU 判断——其余 BUTTON 形态的查看码是刻意只读的。
DROP TEMPORARY TABLE IF EXISTS `lamp_rbac_order_action`;
CREATE TEMPORARY TABLE `lamp_rbac_order_action` (
  `resource_code` VARCHAR(64) NOT NULL,
  `action_code` VARCHAR(16) NOT NULL,
  `action_name` VARCHAR(16) NOT NULL,
  PRIMARY KEY (`resource_code`, `action_code`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

INSERT INTO `lamp_rbac_order_action` VALUES
  ('qms:inspection-order', 'create', '新增'),
  ('qms:inspection-order', 'update', '修改'),
  ('qms:inspection-order', 'delete', '删除'),
  ('qms:inspection-order', 'export', '导出');

INSERT IGNORE INTO sys_menu
  (parent_id, menu_name, menu_type, permission_code, route_path, remark, version, created_time, updated_time, deleted_flag)
SELECT parent.menu_id, CONCAT('检验单管理-', a.action_name), 'BUTTON',
       CONCAT(a.resource_code, ':', a.action_code), NULL, 'LAMP_RBAC_V1: 资源动作权限补漏',
       0, NOW(), NOW(), 0
  FROM `lamp_rbac_order_action` a
  JOIN sys_menu parent
    ON parent.permission_code = CONCAT(a.resource_code, ':view')
   AND parent.deleted_flag = 0;

-- 4g. 同时把检验单的查看码从 BUTTON 提回 MENU 资源页：
--     它在第 2 节只是为了给责任通知当收件人准入而插成 BUTTON，但前端确实有
--     /qms/inspection-orders 这个页面，停在 BUTTON 会让侧边栏菜单项缺失。
--     只改类型和路由，权限码不变，已授权关系不受影响。
UPDATE sys_menu
   SET menu_type = 'MENU',
       route_path = '/qms/inspection-orders',
       menu_name = '检验单管理',
       updated_time = NOW()
 WHERE permission_code = 'qms:inspection-order:view'
   AND deleted_flag = 0
   AND menu_type = 'BUTTON';

-- ---------------------------------------------------------------------------
-- 5. 按角色授权
--    所有在岗角色先拿"看首页 + 用消息中心"，再按业务域拿本域权限。
--    部门主管之所以能收到并处理预警通知，依赖的就是这里的域权限。
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO sys_role_menu (role_id, menu_id, created_time, deleted_flag)
SELECT r.role_id, m.menu_id, NOW(), 0
  FROM sys_role r
  JOIN sys_menu m ON m.deleted_flag = 0
 WHERE r.deleted_flag = 0 AND r.status = 'ENABLE'
   AND m.permission_code IN ('dashboard:summary:view',
                             'sys:notification:view', 'sys:notification:read', 'sys:notification:ack');

-- 超级管理员与生产经理：全部已注册权限。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id, created_time, deleted_flag)
SELECT r.role_id, m.menu_id, NOW(), 0
  FROM sys_role r
  JOIN sys_menu m ON m.deleted_flag = 0
 WHERE r.deleted_flag = 0 AND r.status = 'ENABLE'
   AND r.role_code IN ('SUPER_ADMIN', 'MES_MANAGER');

-- 生产计划员：生产、主数据、工艺、工厂、组织、排程与 AI。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id, created_time, deleted_flag)
SELECT r.role_id, m.menu_id, NOW(), 0
  FROM sys_role r
  JOIN sys_menu m ON m.deleted_flag = 0
 WHERE r.deleted_flag = 0 AND r.status = 'ENABLE' AND r.role_code = 'PLAN_SCHEDULER'
   AND (m.permission_code LIKE 'mfg:%' OR m.permission_code LIKE 'mdm:%'
        OR m.permission_code LIKE 'process:%' OR m.permission_code LIKE 'factory:%'
        OR m.permission_code LIKE 'org:%' OR m.permission_code LIKE 'ai:%');

-- 生产班组长：生产、主数据、工艺、工厂、组织、质量与 AI。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id, created_time, deleted_flag)
SELECT r.role_id, m.menu_id, NOW(), 0
  FROM sys_role r
  JOIN sys_menu m ON m.deleted_flag = 0
 WHERE r.deleted_flag = 0 AND r.status = 'ENABLE' AND r.role_code = 'PRODUCTION_SUPERVISOR'
   AND (m.permission_code LIKE 'mfg:%' OR m.permission_code LIKE 'mdm:%'
        OR m.permission_code LIKE 'process:%' OR m.permission_code LIKE 'factory:%'
        OR m.permission_code LIKE 'org:%' OR m.permission_code LIKE 'qms:%'
        OR m.permission_code LIKE 'ai:%');

-- 生产操作员：本人任务与报工、主数据查询。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id, created_time, deleted_flag)
SELECT r.role_id, m.menu_id, NOW(), 0
  FROM sys_role r
  JOIN sys_menu m ON m.deleted_flag = 0
 WHERE r.deleted_flag = 0 AND r.status = 'ENABLE' AND r.role_code = 'PRODUCTION_OPERATOR'
   AND (m.permission_code LIKE 'mfg:task%' OR m.permission_code LIKE 'mfg:work-report%'
        OR m.permission_code IN ('mfg:work-order:view', 'mfg:work-order-material:view',
                                 'mdm:bom:view', 'mdm:bom-item:view', 'mdm:material:view',
                                 'mdm:product:view', 'process:route:view',
                                 'process:route-item:view', 'process:operation:view'));

-- 质量工程师：质量、主数据、工艺、生产工单与 AI。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id, created_time, deleted_flag)
SELECT r.role_id, m.menu_id, NOW(), 0
  FROM sys_role r
  JOIN sys_menu m ON m.deleted_flag = 0
 WHERE r.deleted_flag = 0 AND r.status = 'ENABLE' AND r.role_code = 'QUALITY_ENGINEER'
   AND (m.permission_code LIKE 'qms:%' OR m.permission_code LIKE 'mdm:%'
        OR m.permission_code LIKE 'process:%' OR m.permission_code LIKE 'ai:%'
        OR m.permission_code IN ('mfg:work-order:view', 'mfg:work-order-material:view'));

-- 仓库主管：仓储、追溯、主数据、工单查看与 AI。
-- 另需 qms:inspection-order:view：待检批次积压预警的"去处理"落在检验单页面，
-- 没有这个码点进去仍是 403，等于预警给了入口却打不开。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id, created_time, deleted_flag)
SELECT r.role_id, m.menu_id, NOW(), 0
  FROM sys_role r
  JOIN sys_menu m ON m.deleted_flag = 0
 WHERE r.deleted_flag = 0 AND r.status = 'ENABLE' AND r.role_code = 'WAREHOUSE_MANAGER'
   AND (m.permission_code LIKE 'wms:%' OR m.permission_code LIKE 'trace:%'
        OR m.permission_code LIKE 'mdm:%' OR m.permission_code LIKE 'ai:%'
        OR m.permission_code IN ('mfg:work-order:view', 'mfg:work-order-material:view',
                                 'mfg:supply-allocation:view', 'mfg:supply-source:view',
                                 'qms:inspection-order:view', 'qms:inspection-order:submit'));

-- 设备工程师：设备、工厂建模、主数据与 AI。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id, created_time, deleted_flag)
SELECT r.role_id, m.menu_id, NOW(), 0
  FROM sys_role r
  JOIN sys_menu m ON m.deleted_flag = 0
 WHERE r.deleted_flag = 0 AND r.status = 'ENABLE' AND r.role_code = 'EQUIPMENT_ENGINEER'
   AND (m.permission_code LIKE 'eam:%' OR m.permission_code LIKE 'factory:%'
        OR m.permission_code LIKE 'mdm:%' OR m.permission_code LIKE 'ai:%');

COMMIT;
