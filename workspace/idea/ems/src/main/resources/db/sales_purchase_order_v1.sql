-- SALES_PURCHASE_ORDER_V1：补齐销售订单与采购订单两张主表，接上主流程链两端。
--
-- 背景：原系统只有「生产订单」，没有它的上游来源；仓储只有「采购入库」，没有采购订单。
-- 结果是主流程链「客户需求 → BOM 算料 → 采购 → 到货检验」缺前两环，
-- 销售侧也只能靠生产订单直接生成销售出库，无法回答「这张销售单发了几台、还剩几台」。
--
-- 设计：
--   1. mfg_sales_order / mfg_sales_order_detail：销售订单，确认后生成生产订单（source_type='SALES_ORDER'）。
--   2. mfg_purchase_order / mfg_purchase_order_detail：采购订单，下达后生成采购入库单
--      （wms_inbound_order.source_order_type='PURCHASE_ORDER'），复用现有「采购入库 → 来料检验 → 确认入库」链路。
--   3. 已发/已收数量不落冗余列，按实际出库/入库流水实时汇总，避免两处口径打架。
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

CREATE TABLE IF NOT EXISTS `mfg_sales_order` (
  `sales_order_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '销售订单主键。',
  `sales_order_no` VARCHAR(64) NULL COMMENT '销售订单编号。',
  `customer_id` BIGINT UNSIGNED NULL COMMENT '客户 ID，对应 mdm_customer。',
  `order_date` DATE NULL COMMENT '客户下单日期。',
  `delivery_date` DATE NULL COMMENT '约定交付日期。',
  `status` VARCHAR(32) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT:草稿，SUBMITTED:已确认，CLOSED:已关闭，WITHDRAWN:已撤回。',
  `total_quantity` DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '订单总数量，由明细汇总。',
  `remark` VARCHAR(500) NULL COMMENT '备注',
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `created_by` BIGINT UNSIGNED NULL COMMENT '创建人',
  `created_time` DATETIME NULL COMMENT '创建时间',
  `updated_by` BIGINT UNSIGNED NULL COMMENT '更新人',
  `updated_time` DATETIME NULL COMMENT '更新时间',
  `deleted_flag` TINYINT NOT NULL DEFAULT 0 COMMENT '软删除标记 0未删 1已删',
  PRIMARY KEY (`sales_order_id`),
  UNIQUE KEY `uk_mfg_sales_order_no` (`sales_order_no`),
  KEY `idx_mfg_sales_order_customer` (`customer_id`),
  KEY `idx_mfg_sales_order_delivery` (`delivery_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='销售订单主表';

CREATE TABLE IF NOT EXISTS `mfg_sales_order_detail` (
  `detail_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '明细主键。',
  `sales_order_id` BIGINT UNSIGNED NOT NULL COMMENT '销售订单 ID。',
  `product_id` BIGINT UNSIGNED NULL COMMENT '产品 ID，对应 mdm_product。',
  `order_quantity` DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '订购数量。',
  `delivered_quantity` DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '已发货数量，按销售出库流水汇总。',
  `unit_price` DECIMAL(18,4) NULL COMMENT '单价，选填。',
  `remark` VARCHAR(500) NULL COMMENT '备注',
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `created_by` BIGINT UNSIGNED NULL COMMENT '创建人',
  `created_time` DATETIME NULL COMMENT '创建时间',
  `updated_by` BIGINT UNSIGNED NULL COMMENT '更新人',
  `updated_time` DATETIME NULL COMMENT '更新时间',
  `deleted_flag` TINYINT NOT NULL DEFAULT 0 COMMENT '软删除标记 0未删 1已删',
  PRIMARY KEY (`detail_id`),
  KEY `idx_mfg_sales_order_detail_order` (`sales_order_id`),
  KEY `idx_mfg_sales_order_detail_product` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='销售订单明细表';

CREATE TABLE IF NOT EXISTS `mfg_purchase_order` (
  `purchase_order_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '采购订单主键。',
  `purchase_order_no` VARCHAR(64) NULL COMMENT '采购订单编号。',
  `supplier_id` BIGINT UNSIGNED NULL COMMENT '供应商 ID，对应 mdm_supplier。',
  `warehouse_id` BIGINT UNSIGNED NULL COMMENT '收货仓库 ID，下达时必填。',
  `order_date` DATE NULL COMMENT '下单日期。',
  `expected_date` DATE NULL COMMENT '预计到货日期。',
  `status` VARCHAR(32) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT:草稿，RELEASED:已下达，CLOSED:已关闭，WITHDRAWN:已撤回。',
  `total_quantity` DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '采购总数量，由明细汇总。',
  `remark` VARCHAR(500) NULL COMMENT '备注',
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `created_by` BIGINT UNSIGNED NULL COMMENT '创建人',
  `created_time` DATETIME NULL COMMENT '创建时间',
  `updated_by` BIGINT UNSIGNED NULL COMMENT '更新人',
  `updated_time` DATETIME NULL COMMENT '更新时间',
  `deleted_flag` TINYINT NOT NULL DEFAULT 0 COMMENT '软删除标记 0未删 1已删',
  PRIMARY KEY (`purchase_order_id`),
  UNIQUE KEY `uk_mfg_purchase_order_no` (`purchase_order_no`),
  KEY `idx_mfg_purchase_order_supplier` (`supplier_id`),
  KEY `idx_mfg_purchase_order_expected` (`expected_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='采购订单主表';

CREATE TABLE IF NOT EXISTS `mfg_purchase_order_detail` (
  `detail_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '明细主键。',
  `purchase_order_id` BIGINT UNSIGNED NOT NULL COMMENT '采购订单 ID。',
  `material_id` BIGINT UNSIGNED NULL COMMENT '物料 ID，对应 mdm_material。',
  `order_quantity` DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '采购数量。',
  `received_quantity` DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '已入库数量，按采购入库流水汇总。',
  `unit_price` DECIMAL(18,4) NULL COMMENT '单价，选填。',
  `remark` VARCHAR(500) NULL COMMENT '备注',
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `created_by` BIGINT UNSIGNED NULL COMMENT '创建人',
  `created_time` DATETIME NULL COMMENT '创建时间',
  `updated_by` BIGINT UNSIGNED NULL COMMENT '更新人',
  `updated_time` DATETIME NULL COMMENT '更新时间',
  `deleted_flag` TINYINT NOT NULL DEFAULT 0 COMMENT '软删除标记 0未删 1已删',
  PRIMARY KEY (`detail_id`),
  KEY `idx_mfg_purchase_order_detail_order` (`purchase_order_id`),
  KEY `idx_mfg_purchase_order_detail_material` (`material_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='采购订单明细表';

-- 生产订单补两个来源字段：确认销售订单后生成的订单，能反查到是哪张销售单的哪一行。
SET @ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns
         WHERE table_schema = @lamp_schema AND table_name = 'mfg_production_order'
           AND column_name = 'source_sales_order_id'),
  'SELECT 1',
  'ALTER TABLE `mfg_production_order` ADD COLUMN `source_sales_order_id` BIGINT UNSIGNED NULL COMMENT ''来源销售订单 ID。'' AFTER `source_type`'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns
         WHERE table_schema = @lamp_schema AND table_name = 'mfg_production_order'
           AND column_name = 'source_sales_order_detail_id'),
  'SELECT 1',
  'ALTER TABLE `mfg_production_order` ADD COLUMN `source_sales_order_detail_id` BIGINT UNSIGNED NULL COMMENT ''来源销售订单明细 ID。'' AFTER `source_sales_order_id`'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.statistics
         WHERE table_schema = @lamp_schema AND table_name = 'mfg_production_order'
           AND index_name = 'idx_mfg_production_order_source_sales_order'),
  'SELECT 1',
  'ALTER TABLE `mfg_production_order` ADD KEY `idx_mfg_production_order_source_sales_order` (`source_sales_order_id`)'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 已收数量不额外加明细外键：采购入库单头已经带 source_order_type='PURCHASE_ORDER'
-- 与 source_order_id=purchase_order_id，按「单头 + 物料」汇总即可，避免再维护一条冗余关联。
