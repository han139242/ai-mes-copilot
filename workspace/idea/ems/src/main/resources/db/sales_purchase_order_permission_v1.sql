-- =============================================================================
-- 销售订单 / 采购订单 权限种子脚本（可重复执行）
--
-- 作用：给新增的两类单据补上菜单权限与角色授权，让前端菜单能显示、接口能过权限校验。
-- 前置：先执行 db/sales_purchase_order_v1.sql 建表。
--
-- 结构说明：
--   mfg:sales-order:view / mfg:purchase-order:view 是 MENU，挂在生产制造域目录 module:mfg:view 下；
--   其余 create/update/delete/export/confirm/withdraw/release 是 BUTTON，挂各自 MENU 下。
--   权限码由后端 PermissionCodeResolver 按「域:资源:动作」自动推导，这里必须一字不差。
-- =============================================================================

-- 隔离保护：只允许在演练库 mes_lamp_* 上执行。
SET @lamp_scope_sql = IF(DATABASE() REGEXP '^mes_lamp_[A-Za-z0-9_]+$',
    'DO 0',
    'SELECT * FROM LAMP_ERROR_ISOLATED_DATABASE_REQUIRED');
PREPARE lamp_scope_stmt FROM @lamp_scope_sql;
EXECUTE lamp_scope_stmt;
DEALLOCATE PREPARE lamp_scope_stmt;

SET @order_permission_time = CURRENT_TIMESTAMP;
SET @order_admin_user_id = (
  SELECT `user_id` FROM `sys_user`
  WHERE `username` = 'admin' AND `deleted_flag` = 0
  ORDER BY `user_id` LIMIT 1
);

START TRANSACTION;

-- ---------------------------------------------------------------------------
-- 一、权限项：先两个页面菜单，再动作按钮
-- ---------------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS `tmp_order_resource`;
CREATE TEMPORARY TABLE `tmp_order_resource` (
  `parent_permission_code` VARCHAR(64) NOT NULL,
  `permission_code` VARCHAR(64) PRIMARY KEY,
  `permission_name` VARCHAR(128) NOT NULL,
  `route_path` VARCHAR(255) NULL
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

-- 两个页面菜单挂生产制造域目录；两个明细资源不建菜单（前端 menu:false），
-- 但子表接口 /api/mfg/sales-order-detail/page 与 purchase-order-detail/page 同样要做权限校验，
-- 所以仍要建 view 权限，父级指向各自的主单页面菜单。
INSERT INTO `tmp_order_resource` VALUES
  ('module:mfg:view',         'mfg:sales-order:view',         '销售订单', '/mfg/sales-orders'),
  ('module:mfg:view',         'mfg:purchase-order:view',      '采购订单', '/mfg/purchase-orders'),
  ('mfg:sales-order:view',    'mfg:sales-order-detail:view',  '销售订单明细', NULL),
  ('mfg:purchase-order:view', 'mfg:purchase-order-detail:view', '采购订单明细', NULL);

-- 父级菜单缺失时下面 JOIN 不到，脚本会自然跳过该行，不会报错。
-- 分两条语句：先插两个页面菜单（route_path 非空），再插两个明细权限（route_path 为空）。
-- 必须分开，因为明细权限的父级就是刚插入的页面菜单；同一条 INSERT 里子查询看不到本语句刚写的行，
-- 合成一条会导致首次执行漏掉明细权限、要跑第二遍才补上。
INSERT INTO `sys_menu`
  (`parent_id`, `menu_name`, `menu_type`, `permission_code`, `route_path`, `remark`,
   `version`, `created_by`, `created_time`, `updated_by`, `updated_time`, `deleted_flag`)
SELECT parent.`menu_id`, resource.`permission_name`, 'MENU', resource.`permission_code`,
       resource.`route_path`, 'SALES_PURCHASE_ORDER_PERMISSION_V1: 订单资源菜单', 0,
       @order_admin_user_id, @order_permission_time, @order_admin_user_id, @order_permission_time, 0
FROM `tmp_order_resource` resource
JOIN `sys_menu` parent
  ON parent.`permission_code` = resource.`parent_permission_code`
 AND parent.`deleted_flag` = 0
WHERE resource.`route_path` IS NOT NULL
ON DUPLICATE KEY UPDATE
  `parent_id` = VALUES(`parent_id`), `menu_name` = VALUES(`menu_name`), `menu_type` = 'MENU',
  `route_path` = VALUES(`route_path`), `remark` = VALUES(`remark`),
  `updated_by` = @order_admin_user_id, `updated_time` = @order_permission_time, `deleted_flag` = 0;

INSERT INTO `sys_menu`
  (`parent_id`, `menu_name`, `menu_type`, `permission_code`, `route_path`, `remark`,
   `version`, `created_by`, `created_time`, `updated_by`, `updated_time`, `deleted_flag`)
SELECT parent.`menu_id`, resource.`permission_name`, 'BUTTON', resource.`permission_code`,
       NULL, 'SALES_PURCHASE_ORDER_PERMISSION_V1: 订单明细查看', 0,
       @order_admin_user_id, @order_permission_time, @order_admin_user_id, @order_permission_time, 0
FROM `tmp_order_resource` resource
JOIN `sys_menu` parent
  ON parent.`permission_code` = resource.`parent_permission_code`
 AND parent.`deleted_flag` = 0
WHERE resource.`route_path` IS NULL
ON DUPLICATE KEY UPDATE
  `parent_id` = VALUES(`parent_id`), `menu_name` = VALUES(`menu_name`), `menu_type` = 'BUTTON',
  `remark` = VALUES(`remark`), `updated_by` = @order_admin_user_id,
  `updated_time` = @order_permission_time, `deleted_flag` = 0;

DROP TEMPORARY TABLE IF EXISTS `tmp_order_permission`;
CREATE TEMPORARY TABLE `tmp_order_permission` (
  `parent_permission_code` VARCHAR(64) NOT NULL,
  `permission_code` VARCHAR(64) PRIMARY KEY,
  `permission_name` VARCHAR(128) NOT NULL
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

INSERT INTO `tmp_order_permission` VALUES
  -- 销售订单动作
  ('mfg:sales-order:view', 'mfg:sales-order:create',   '新增销售订单'),
  ('mfg:sales-order:view', 'mfg:sales-order:update',   '修改销售订单'),
  ('mfg:sales-order:view', 'mfg:sales-order:delete',   '删除销售订单'),
  ('mfg:sales-order:view', 'mfg:sales-order:export',   '导出销售订单'),
  ('mfg:sales-order:view', 'mfg:sales-order:confirm',  '确认销售订单并生成生产订单'),
  ('mfg:sales-order:view', 'mfg:sales-order:withdraw', '撤回销售订单'),
  -- 采购订单动作
  ('mfg:purchase-order:view', 'mfg:purchase-order:create',   '新增采购订单'),
  ('mfg:purchase-order:view', 'mfg:purchase-order:update',   '修改采购订单'),
  ('mfg:purchase-order:view', 'mfg:purchase-order:delete',   '删除采购订单'),
  ('mfg:purchase-order:view', 'mfg:purchase-order:export',   '导出采购订单'),
  ('mfg:purchase-order:view', 'mfg:purchase-order:release',  '下达采购订单并生成入库单'),
  ('mfg:purchase-order:view', 'mfg:purchase-order:withdraw', '撤回采购订单');

INSERT INTO `sys_menu`
  (`parent_id`, `menu_name`, `menu_type`, `permission_code`, `route_path`, `remark`,
   `version`, `created_by`, `created_time`, `updated_by`, `updated_time`, `deleted_flag`)
SELECT parent.`menu_id`, permission.`permission_name`, 'BUTTON', permission.`permission_code`, NULL,
       'SALES_PURCHASE_ORDER_PERMISSION_V1: 订单业务动作', 0,
       @order_admin_user_id, @order_permission_time, @order_admin_user_id, @order_permission_time, 0
FROM `tmp_order_permission` permission
JOIN `sys_menu` parent
  ON parent.`permission_code` = permission.`parent_permission_code`
 AND parent.`deleted_flag` = 0
ON DUPLICATE KEY UPDATE
  `parent_id` = VALUES(`parent_id`), `menu_name` = VALUES(`menu_name`), `menu_type` = 'BUTTON',
  `remark` = VALUES(`remark`), `updated_by` = @order_admin_user_id,
  `updated_time` = @order_permission_time, `deleted_flag` = 0;

-- ---------------------------------------------------------------------------
-- 二、角色授权
--    查看/导出：全部业务角色都有，保证谁都能查；
--    建档改删：计划员、生产经理、仓储主管；
--    销售订单确认：计划员、生产经理；
--    采购订单下达：计划员、生产经理、仓储主管。
--
--    本脚本对这 16 个权限码是"权威口径"：清单外的授权会被软删。
--    这样重跑脚本不会因为之前跑过别的脚本（比如 lamp_rbac_v1.sql 用 'mfg:%' 通配授权）
--    而残留多余授权，保证多次执行结果完全一致。
-- ---------------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS `tmp_order_role_permission`;
CREATE TEMPORARY TABLE `tmp_order_role_permission` (
  `role_code` VARCHAR(64) NOT NULL,
  `permission_code` VARCHAR(64) NOT NULL,
  PRIMARY KEY (`role_code`, `permission_code`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

INSERT INTO `tmp_order_role_permission` VALUES
  -- 全部角色：只读
  ('SUPER_ADMIN','mfg:sales-order:view'),('SUPER_ADMIN','mfg:sales-order:export'),
  ('SUPER_ADMIN','mfg:purchase-order:view'),('SUPER_ADMIN','mfg:purchase-order:export'),
  ('MES_MANAGER','mfg:sales-order:view'),('MES_MANAGER','mfg:sales-order:export'),
  ('MES_MANAGER','mfg:purchase-order:view'),('MES_MANAGER','mfg:purchase-order:export'),
  ('PLAN_SCHEDULER','mfg:sales-order:view'),('PLAN_SCHEDULER','mfg:sales-order:export'),
  ('PLAN_SCHEDULER','mfg:purchase-order:view'),('PLAN_SCHEDULER','mfg:purchase-order:export'),
  -- 生产班组长：lamp_rbac_v1.sql 用 'mfg:%' 通配给过全部生产制造权限，
  -- 这里必须给同一套，否则先跑 rbac 再跑本脚本时他只有只读，重跑 rbac 又变全权，
  -- 授权结果会随执行顺序漂移。
  ('PRODUCTION_SUPERVISOR','mfg:sales-order:view'),
  ('PRODUCTION_SUPERVISOR','mfg:sales-order:export'),
  ('PRODUCTION_SUPERVISOR','mfg:sales-order:create'),
  ('PRODUCTION_SUPERVISOR','mfg:sales-order:update'),
  ('PRODUCTION_SUPERVISOR','mfg:sales-order:delete'),
  ('PRODUCTION_SUPERVISOR','mfg:sales-order:confirm'),
  ('PRODUCTION_SUPERVISOR','mfg:sales-order:withdraw'),
  ('PRODUCTION_SUPERVISOR','mfg:purchase-order:view'),
  ('PRODUCTION_SUPERVISOR','mfg:purchase-order:export'),
  ('PRODUCTION_SUPERVISOR','mfg:purchase-order:create'),
  ('PRODUCTION_SUPERVISOR','mfg:purchase-order:update'),
  ('PRODUCTION_SUPERVISOR','mfg:purchase-order:delete'),
  ('PRODUCTION_SUPERVISOR','mfg:purchase-order:release'),
  ('PRODUCTION_SUPERVISOR','mfg:purchase-order:withdraw'),
  ('WAREHOUSE_MANAGER','mfg:sales-order:view'),
  ('WAREHOUSE_MANAGER','mfg:purchase-order:view'),('WAREHOUSE_MANAGER','mfg:purchase-order:export'),
  ('QUALITY_ENGINEER','mfg:sales-order:view'),('QUALITY_ENGINEER','mfg:purchase-order:view'),
  -- 建档改删
  ('SUPER_ADMIN','mfg:sales-order:create'),('SUPER_ADMIN','mfg:sales-order:update'),
  ('SUPER_ADMIN','mfg:sales-order:delete'),
  ('SUPER_ADMIN','mfg:purchase-order:create'),('SUPER_ADMIN','mfg:purchase-order:update'),
  ('SUPER_ADMIN','mfg:purchase-order:delete'),
  ('MES_MANAGER','mfg:sales-order:create'),('MES_MANAGER','mfg:sales-order:update'),
  ('MES_MANAGER','mfg:sales-order:delete'),
  ('MES_MANAGER','mfg:purchase-order:create'),('MES_MANAGER','mfg:purchase-order:update'),
  ('MES_MANAGER','mfg:purchase-order:delete'),
  ('PLAN_SCHEDULER','mfg:sales-order:create'),('PLAN_SCHEDULER','mfg:sales-order:update'),
  ('PLAN_SCHEDULER','mfg:sales-order:delete'),
  ('PLAN_SCHEDULER','mfg:purchase-order:create'),('PLAN_SCHEDULER','mfg:purchase-order:update'),
  ('PLAN_SCHEDULER','mfg:purchase-order:delete'),
  ('WAREHOUSE_MANAGER','mfg:purchase-order:create'),('WAREHOUSE_MANAGER','mfg:purchase-order:update'),
  ('WAREHOUSE_MANAGER','mfg:purchase-order:delete'),
  -- 销售确认：生成生产订单，属于计划职责；采购下达：生成入库单，属计划与仓储职责
  ('SUPER_ADMIN','mfg:sales-order:confirm'),('SUPER_ADMIN','mfg:sales-order:withdraw'),
  ('MES_MANAGER','mfg:sales-order:confirm'),('MES_MANAGER','mfg:sales-order:withdraw'),
  ('PLAN_SCHEDULER','mfg:sales-order:confirm'),('PLAN_SCHEDULER','mfg:sales-order:withdraw'),
  ('SUPER_ADMIN','mfg:purchase-order:release'),('SUPER_ADMIN','mfg:purchase-order:withdraw'),
  ('MES_MANAGER','mfg:purchase-order:release'),('MES_MANAGER','mfg:purchase-order:withdraw'),
  ('PLAN_SCHEDULER','mfg:purchase-order:release'),('PLAN_SCHEDULER','mfg:purchase-order:withdraw'),
  ('WAREHOUSE_MANAGER','mfg:purchase-order:release'),('WAREHOUSE_MANAGER','mfg:purchase-order:withdraw');

-- 明细子表的查看权限跟随主单：谁能看到销售订单，就要能看到它的明细，否则详情页子表会 403。
-- 用独立临时表中转，避免 INSERT ... SELECT 读同一张表。
DROP TEMPORARY TABLE IF EXISTS `tmp_order_detail_grant`;
CREATE TEMPORARY TABLE `tmp_order_detail_grant` (
  `role_code` VARCHAR(64) NOT NULL,
  `permission_code` VARCHAR(64) NOT NULL,
  PRIMARY KEY (`role_code`, `permission_code`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

INSERT IGNORE INTO `tmp_order_detail_grant` (`role_code`, `permission_code`)
SELECT `role_code`, 'mfg:sales-order-detail:view'
FROM `tmp_order_role_permission` WHERE `permission_code` = 'mfg:sales-order:view';

INSERT IGNORE INTO `tmp_order_detail_grant` (`role_code`, `permission_code`)
SELECT `role_code`, 'mfg:purchase-order-detail:view'
FROM `tmp_order_role_permission` WHERE `permission_code` = 'mfg:purchase-order:view';

INSERT IGNORE INTO `tmp_order_role_permission` (`role_code`, `permission_code`)
SELECT `role_code`, `permission_code` FROM `tmp_order_detail_grant`;

-- 先收口：把这 16 个权限码上不在清单里的授权清掉。
-- 起因是实测发现授权结果会随执行顺序漂移：lamp_rbac_v1.sql 对 PRODUCTION_SUPERVISOR 用的是
-- 'mfg:%' 通配，本脚本跑完再重跑 rbac，会凭空多出 12 条授权（sys_role_menu 1956→1971）。
-- 只清本脚本管的这 16 个码，不碰其它权限。
UPDATE `sys_role_menu` relation
  JOIN `sys_menu` menu
    ON menu.`menu_id` = relation.`menu_id`
  JOIN `sys_role` role
    ON role.`role_id` = relation.`role_id`
  LEFT JOIN `tmp_order_role_permission` allowed
    ON allowed.`role_code` = role.`role_code`
   AND allowed.`permission_code` = menu.`permission_code`
   SET relation.`deleted_flag` = 1
 WHERE menu.`permission_code` IN (
         'mfg:sales-order:view', 'mfg:sales-order:create', 'mfg:sales-order:update',
         'mfg:sales-order:delete', 'mfg:sales-order:export', 'mfg:sales-order:confirm',
         'mfg:sales-order:withdraw', 'mfg:sales-order-detail:view',
         'mfg:purchase-order:view', 'mfg:purchase-order:create', 'mfg:purchase-order:update',
         'mfg:purchase-order:delete', 'mfg:purchase-order:export', 'mfg:purchase-order:release',
         'mfg:purchase-order:withdraw', 'mfg:purchase-order-detail:view')
   AND relation.`deleted_flag` = 0
   AND allowed.`role_code` IS NULL;

-- sys_role_menu 只有 role_id/menu_id/created_by/created_time/deleted_flag 五列（无 version/updated_*），
-- 授权幂等靠唯一键 uk_sys_role_menu(role_id, menu_id)，冲突时把软删标记拉回 0。
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`, `created_by`, `created_time`, `deleted_flag`)
SELECT role.`role_id`, menu.`menu_id`, @order_admin_user_id, @order_permission_time, 0
FROM `tmp_order_role_permission` grant_row
JOIN `sys_role` role
  ON role.`role_code` = grant_row.`role_code`
 AND role.`deleted_flag` = 0
JOIN `sys_menu` menu
  ON menu.`permission_code` = grant_row.`permission_code`
 AND menu.`deleted_flag` = 0
ON DUPLICATE KEY UPDATE `deleted_flag` = 0;

COMMIT;

-- ---------------------------------------------------------------------------
-- 三、自检：列出本次应有的权限项与已授权角色数，任一为 0 说明脚本没生效
-- ---------------------------------------------------------------------------
SELECT menu.`permission_code`, menu.`menu_name`,
       COUNT(DISTINCT relation.`role_id`) AS `granted_roles`
FROM `sys_menu` menu
LEFT JOIN `sys_role_menu` relation
       ON relation.`menu_id` = menu.`menu_id`
      AND relation.`deleted_flag` = 0
WHERE menu.`permission_code` LIKE 'mfg:sales-order:%'
   OR menu.`permission_code` LIKE 'mfg:purchase-order:%'
GROUP BY menu.`permission_code`, menu.`menu_name`
ORDER BY menu.`permission_code`;
