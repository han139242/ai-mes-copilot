package com.neuedu.ems.service.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI 只读数据资源的登记表。
 *
 * <p>这里只登记「模型能看到什么」：资源编码、中文名、说明、可选筛选项，
 * 以及每张表允许暴露的列。真实 SQL 由 {@code AiReadOnlyQueryServiceImpl}
 * 按这份登记拼装，模型既不接触表名也不接触列名。</p>
 *
 * <p>登记原则：
 * 1. 只登记业务与主数据，系统内部表（会话、日志、任务实例）不进目录；
 * 2. 敏感列永不登记：密码摘要、手机号明文、手机号检索哈希、密钥类字段；
 * 3. 每个资源都必须声明一个查看权限，查询时逐个校验，不靠数据范围兜底。</p>
 */
public final class AiDataResourceCatalog {

    private AiDataResourceCatalog() {
    }

    /**
     * 一列：对外属性名、SQL 表达式、中文表头。
     */
    public record Column(String prop, String expr, String label) {
    }

    /**
     * 一个筛选条件：对外名称、SQL 表达式、中文名、类型。
     *
     * <p>类型决定取值怎么进 SQL：eq 精确匹配，like 模糊匹配，dateFrom/dateTo 日期区间，
     * number 数值精确匹配。表达式里统一用命名参数占位符。</p>
     */
    public record Filter(String name, String expr, String label, String type) {
    }

    /**
     * 一个资源。
     *
     * @param code        资源编码，模型选择时用
     * @param label       中文名
     * @param description 说明，讲清这张表能回答什么问题
     * @param from        FROM 子句，表别名统一用 t
     * @param columns     对外暴露的列
     * @param filters     可筛选条件
     * @param keywordExpr 关键词模糊匹配的表达式，null 表示不支持关键词
     * @param permission  查看该资源所需的权限码
     * @param orderBy     默认排序
     * @param maxRows     单次最多返回行数
     */
    public record Resource(String code, String label, String description, String from,
                           List<Column> columns, List<Filter> filters, String keywordExpr,
                           String permission, String orderBy, int maxRows) {
    }

    private static Column column(String prop, String expr, String label) {
        return new Column(prop, expr, label);
    }

    private static Filter eq(String name, String expr, String label) {
        return new Filter(name, expr, label, "eq");
    }

    private static Filter like(String name, String expr, String label) {
        return new Filter(name, expr, label, "like");
    }

    private static Filter dateFrom(String name, String expr, String label) {
        return new Filter(name, expr, label, "dateFrom");
    }

    private static Filter dateTo(String name, String expr, String label) {
        return new Filter(name, expr, label, "dateTo");
    }

    /**
     * 全部可查资源。新增资源只在这里加一条，其余代码不用动。
     */
    public static List<Resource> resources() {
        List<Resource> resources = new ArrayList<>(32);
        resources.addAll(systemAccountResources());
        resources.addAll(organizationResources());
        resources.addAll(partnerResources());
        resources.addAll(materialProductResources());
        resources.addAll(bomResources());
        resources.addAll(warehouseLocationResources());
        resources.addAll(inventoryResource());
        resources.addAll(salesPurchaseOrderResources());
        resources.addAll(productionOrderResources());
        resources.addAll(workOrderResource());
        resources.addAll(taskResource());
        resources.addAll(executionDocumentResources());
        resources.addAll(inboundOutboundResources());
        resources.addAll(qualityResources());
        resources.addAll(exceptionEquipmentResources());
        resources.addAll(notificationResource());
        return List.copyOf(resources);
    }

    /** systemAccountResources。 */
    private static List<Resource> systemAccountResources() {
        return List.of(
                new Resource("sys_role", "系统角色",
                        "查系统里配了哪些角色、各自的数据范围（ALL/部门/班组/本人）与启停状态",
                        "sys_role t",
                        List.of(column("roleId", "t.role_id", "角色ID"),
                                column("roleCode", "t.role_code", "角色编码"),
                                column("roleName", "t.role_name", "角色名称"),
                                column("dataScope", "t.data_scope", "数据范围"),
                                column("status", "t.status", "状态"),
                                column("remark", "t.remark", "备注")),
                        List.of(eq("roleCode", "t.role_code = :roleCode", "角色编码"),
                                eq("status", "t.status = :status", "状态")),
                        "t.role_code LIKE CONCAT('%', :keyword, '%') OR t.role_name LIKE CONCAT('%', :keyword, '%')",
                        "sys:role:view", "t.role_id", 100),
                new Resource("sys_user", "系统账号",
                        "查系统里有哪些登录账号、绑了哪些角色、启用状态与最近登录时间。不包含任何密码信息",
                        "sys_user t LEFT JOIN org_employee e ON e.employee_id = t.employee_id AND e.deleted_flag = 0",
                        List.of(column("userId", "t.user_id", "账号ID"),
                                column("username", "t.username", "登录名"),
                                column("employeeName", "e.employee_name", "姓名"),
                                column("status", "t.status", "状态"),
                                column("roleNames", "(SELECT GROUP_CONCAT(r.role_name ORDER BY r.role_id SEPARATOR '、')"
                                        + " FROM sys_user_role ur JOIN sys_role r ON r.role_id = ur.role_id AND r.deleted_flag = 0"
                                        + " WHERE ur.user_id = t.user_id AND ur.deleted_flag = 0)", "角色"),
                                column("lastLoginTime", "t.last_login_time", "最近登录"),
                                column("remark", "t.remark", "备注")),
                        List.of(eq("username", "t.username = :username", "登录名"),
                                eq("status", "t.status = :status", "状态")),
                        "t.username LIKE CONCAT('%', :keyword, '%') OR e.employee_name LIKE CONCAT('%', :keyword, '%')",
                        "sys:user:view", "t.user_id", 100)
        );
    }

    /** organizationResources。 */
    private static List<Resource> organizationResources() {
        return List.of(
                new Resource("org_employee", "员工",
                        "查员工档案：工号、姓名、所属部门、岗位、技能等级与在职状态。不含手机号",
                        "org_employee t LEFT JOIN org_department d ON d.department_id = t.department_id AND d.deleted_flag = 0",
                        List.of(column("employeeId", "t.employee_id", "员工ID"),
                                column("employeeNo", "t.employee_no", "工号"),
                                column("employeeName", "t.employee_name", "姓名"),
                                column("departmentName", "d.department_name", "部门"),
                                column("positionName", "t.position_name", "岗位"),
                                column("skillLevel", "t.skill_level", "技能等级"),
                                column("status", "t.status", "状态")),
                        List.of(eq("departmentId", "t.department_id = :departmentId", "部门ID"),
                                eq("status", "t.status = :status", "状态")),
                        "t.employee_no LIKE CONCAT('%', :keyword, '%') OR t.employee_name LIKE CONCAT('%', :keyword, '%')"
                                + " OR t.position_name LIKE CONCAT('%', :keyword, '%')",
                        "org:employee:view", "t.employee_no", 200),
                new Resource("org_department", "部门",
                        "查部门组织机构：编码、名称、上级部门与部门负责人",
                        "org_department t"
                                + " LEFT JOIN org_department p ON p.department_id = t.parent_id AND p.deleted_flag = 0"
                                + " LEFT JOIN org_employee m ON m.employee_id = t.manager_id AND m.deleted_flag = 0",
                        List.of(column("departmentId", "t.department_id", "部门ID"),
                                column("departmentCode", "t.department_code", "部门编码"),
                                column("departmentName", "t.department_name", "部门名称"),
                                column("parentName", "p.department_name", "上级部门"),
                                column("managerName", "m.employee_name", "负责人")),
                        List.of(eq("departmentCode", "t.department_code = :departmentCode", "部门编码")),
                        "t.department_code LIKE CONCAT('%', :keyword, '%') OR t.department_name LIKE CONCAT('%', :keyword, '%')",
                        "org:department:view", "t.department_id", 100),
                new Resource("org_team", "班组",
                        "查生产班组：编码、名称、组长与所属部门",
                        "org_team t"
                                + " LEFT JOIN org_department d ON d.department_id = t.department_id AND d.deleted_flag = 0"
                                + " LEFT JOIN org_employee l ON l.employee_id = t.leader_id AND l.deleted_flag = 0",
                        List.of(column("teamId", "t.team_id", "班组ID"),
                                column("teamCode", "t.team_code", "班组编码"),
                                column("teamName", "t.team_name", "班组名称"),
                                column("leaderName", "l.employee_name", "组长"),
                                column("departmentName", "d.department_name", "部门")),
                        List.of(eq("teamCode", "t.team_code = :teamCode", "班组编码")),
                        "t.team_code LIKE CONCAT('%', :keyword, '%') OR t.team_name LIKE CONCAT('%', :keyword, '%')",
                        "org:team:view", "t.team_id", 100)
        );
    }

    /** partnerResources。 */
    private static List<Resource> partnerResources() {
        return List.of(
                new Resource("mdm_supplier", "供应商",
                        "查供应商档案：编码、名称、联系人、启停状态。不含联系电话",
                        "mdm_supplier t",
                        List.of(column("supplierId", "t.supplier_id", "供应商ID"),
                                column("supplierCode", "t.supplier_code", "供应商编码"),
                                column("supplierName", "t.supplier_name", "供应商名称"),
                                column("contactName", "t.contact_name", "联系人"),
                                column("status", "t.status", "状态"),
                                column("remark", "t.remark", "备注")),
                        List.of(eq("supplierCode", "t.supplier_code = :supplierCode", "供应商编码"),
                                eq("status", "t.status = :status", "状态")),
                        "t.supplier_code LIKE CONCAT('%', :keyword, '%') OR t.supplier_name LIKE CONCAT('%', :keyword, '%')",
                        "mdm:supplier:view", "t.supplier_id", 100),
                new Resource("mdm_customer", "客户",
                        "查客户档案：编码、名称、联系人、启停状态。不含联系电话",
                        "mdm_customer t",
                        List.of(column("customerId", "t.customer_id", "客户ID"),
                                column("customerCode", "t.customer_code", "客户编码"),
                                column("customerName", "t.customer_name", "客户名称"),
                                column("contactName", "t.contact_name", "联系人"),
                                column("status", "t.status", "状态")),
                        List.of(eq("customerCode", "t.customer_code = :customerCode", "客户编码"),
                                eq("status", "t.status = :status", "状态")),
                        "t.customer_code LIKE CONCAT('%', :keyword, '%') OR t.customer_name LIKE CONCAT('%', :keyword, '%')",
                        "mdm:customer:view", "t.customer_id", 100)
        );
    }

    /** materialProductResources。 */
    private static List<Resource> materialProductResources() {
        return List.of(
                new Resource("mdm_material", "物料",
                        "查物料主数据：编码、名称、规格、物料类型（RAW 原料/AUXILIARY 辅料/SEMI_FINISHED 半成品/FINISHED 成品）、计量方式与状态",
                        "mdm_material t"
                                + " LEFT JOIN mdm_material_category c ON c.category_id = t.category_id AND c.deleted_flag = 0"
                                + " LEFT JOIN mdm_unit u ON u.unit_id = t.unit_id AND u.deleted_flag = 0",
                        List.of(column("materialId", "t.material_id", "物料ID"),
                                column("materialCode", "t.material_code", "物料编码"),
                                column("materialName", "t.material_name", "物料名称"),
                                column("categoryName", "c.category_name", "物料分类"),
                                column("specModel", "t.spec_model", "规格型号"),
                                column("unitName", "u.unit_name", "单位"),
                                column("materialType", "t.material_type", "物料类型"),
                                column("status", "t.status", "状态")),
                        List.of(eq("materialType", "t.material_type = :materialType", "物料类型"),
                                eq("status", "t.status = :status", "状态")),
                        "t.material_code LIKE CONCAT('%', :keyword, '%') OR t.material_name LIKE CONCAT('%', :keyword, '%')"
                                + " OR t.spec_model LIKE CONCAT('%', :keyword, '%')",
                        "mdm:material:view", "t.material_code", 200),
                new Resource("mdm_product", "产品",
                        "查产品主数据：编码、名称、型号、对应成品物料与是否纳管制造准备",
                        "mdm_product t LEFT JOIN mdm_material m ON m.material_id = t.material_id AND m.deleted_flag = 0",
                        List.of(column("productId", "t.product_id", "产品ID"),
                                column("productCode", "t.product_code", "产品编码"),
                                column("productName", "t.product_name", "产品名称"),
                                column("productModel", "t.product_model", "产品型号"),
                                column("materialCode", "m.material_code", "成品物料"),
                                column("status", "t.status", "状态")),
                        List.of(eq("status", "t.status = :status", "状态")),
                        "t.product_code LIKE CONCAT('%', :keyword, '%') OR t.product_name LIKE CONCAT('%', :keyword, '%')"
                                + " OR t.product_model LIKE CONCAT('%', :keyword, '%')",
                        "mdm:product:view", "t.product_id", 100)
        );
    }

    /** bomResources。 */
    private static List<Resource> bomResources() {
        return List.of(
                new Resource("mdm_bom", "BOM",
                        "查产品 BOM 头：编码、对应产品、版本、生效区间与状态",
                        "mdm_bom t LEFT JOIN mdm_product p ON p.product_id = t.product_id AND p.deleted_flag = 0",
                        List.of(column("bomId", "t.bom_id", "BOM ID"),
                                column("bomCode", "t.bom_code", "BOM编码"),
                                column("productCode", "p.product_code", "产品编码"),
                                column("productName", "p.product_name", "产品名称"),
                                column("bomVersion", "t.bom_version", "版本"),
                                column("effectiveDate", "t.effective_date", "生效日期"),
                                column("status", "t.status", "状态")),
                        List.of(eq("productId", "t.product_id = :productId", "产品ID"),
                                eq("status", "t.status = :status", "状态")),
                        "t.bom_code LIKE CONCAT('%', :keyword, '%')",
                        "mdm:bom:view", "t.bom_id", 100),
                new Resource("mdm_bom_item", "BOM明细",
                        "查 BOM 用料明细：某版本 BOM 下每个物料用多少、损耗率、是否指向子 BOM",
                        "mdm_bom_item t"
                                + " JOIN mdm_bom b ON b.bom_id = t.bom_id AND b.deleted_flag = 0"
                                + " LEFT JOIN mdm_material m ON m.material_id = t.material_id AND m.deleted_flag = 0",
                        List.of(column("bomItemId", "t.bom_item_id", "明细ID"),
                                column("bomCode", "b.bom_code", "BOM编码"),
                                column("materialCode", "m.material_code", "物料编码"),
                                column("materialName", "m.material_name", "物料名称"),
                                column("quantity", "t.quantity", "用量"),
                                column("lossRate", "t.loss_rate", "损耗率"),
                                column("childBomId", "t.child_bom_id", "子BOM ID"),
                                column("sequenceNo", "t.sequence_no", "序号")),
                        List.of(eq("bomId", "t.bom_id = :bomId", "BOM ID")),
                        "b.bom_code LIKE CONCAT('%', :keyword, '%') OR m.material_code LIKE CONCAT('%', :keyword, '%')"
                                + " OR m.material_name LIKE CONCAT('%', :keyword, '%')",
                        "mdm:bom:view", "t.bom_id, t.sequence_no", 300)
        );
    }

    /** warehouseLocationResources。 */
    private static List<Resource> warehouseLocationResources() {
        return List.of(
                new Resource("wms_warehouse", "仓库",
                        "查仓库：编码、名称、仓库类型（RAW 原料/ LINE_SIDE 线边/ SEMI_FINISHED 半成品/ FINISHED 成品/ DEFECTIVE 不良品）与状态",
                        "wms_warehouse t",
                        List.of(column("warehouseId", "t.warehouse_id", "仓库ID"),
                                column("warehouseCode", "t.warehouse_code", "仓库编码"),
                                column("warehouseName", "t.warehouse_name", "仓库名称"),
                                column("warehouseType", "t.warehouse_type", "仓库类型"),
                                column("status", "t.status", "状态")),
                        List.of(eq("warehouseType", "t.warehouse_type = :warehouseType", "仓库类型")),
                        "t.warehouse_code LIKE CONCAT('%', :keyword, '%') OR t.warehouse_name LIKE CONCAT('%', :keyword, '%')",
                        "wms:warehouse:view", "t.warehouse_id", 50),
                new Resource("wms_location", "库位",
                        "查库位：编码、名称、所属仓库与库位类型",
                        "wms_location t JOIN wms_warehouse w ON w.warehouse_id = t.warehouse_id AND w.deleted_flag = 0",
                        List.of(column("locationId", "t.location_id", "库位ID"),
                                column("locationCode", "t.location_code", "库位编码"),
                                column("locationName", "t.location_name", "库位名称"),
                                column("warehouseCode", "w.warehouse_code", "所属仓库"),
                                column("warehouseName", "w.warehouse_name", "仓库名称"),
                                column("status", "t.status", "状态")),
                        List.of(eq("warehouseId", "t.warehouse_id = :warehouseId", "仓库ID")),
                        "t.location_code LIKE CONCAT('%', :keyword, '%')",
                        "wms:location:view", "t.warehouse_id, t.location_id", 200)
        );
    }

    /** inventoryResource。 */
    private static List<Resource> inventoryResource() {
        return List.of(
                new Resource("wms_inventory", "库存",
                        "查库存现有量：某物料在某仓某库位的可用量、占用量、待检量，以及占用量挂在哪个工单上",
                        "wms_inventory t"
                                + " JOIN mdm_material m ON m.material_id = t.material_id AND m.deleted_flag = 0"
                                + " JOIN wms_warehouse w ON w.warehouse_id = t.warehouse_id AND w.deleted_flag = 0"
                                + " LEFT JOIN wms_location l ON l.location_id = t.location_id AND l.deleted_flag = 0",
                        List.of(column("materialCode", "m.material_code", "物料编码"),
                                column("materialName", "m.material_name", "物料名称"),
                                column("warehouseCode", "w.warehouse_code", "仓库"),
                                column("locationCode", "l.location_code", "库位"),
                                column("availableQuantity", "t.available_quantity", "可用量"),
                                column("lockedQuantity", "t.locked_quantity", "占用量"),
                                column("inspectionQuantity", "t.inspection_quantity", "待检量"),
                                column("workOrderId", "t.work_order_id", "占用工单ID")),
                        List.of(eq("materialId", "t.material_id = :materialId", "物料ID"),
                                eq("warehouseId", "t.warehouse_id = :warehouseId", "仓库ID")),
                        "m.material_code LIKE CONCAT('%', :keyword, '%') OR m.material_name LIKE CONCAT('%', :keyword, '%')",
                        "wms:inventory:view", "m.material_code, w.warehouse_code", 300)
        );
    }

    /** salesPurchaseOrderResources。 */
    private static List<Resource> salesPurchaseOrderResources() {
        return List.of(
                new Resource("mfg_sales_order", "销售订单",
                        "查销售订单：单号、客户、下单日期、交期、状态、订单总量。已发数量在明细表里按产品统计",
                        "mfg_sales_order t JOIN mdm_customer c ON c.customer_id = t.customer_id AND c.deleted_flag = 0",
                        List.of(column("salesOrderId", "t.sales_order_id", "销售订单ID"),
                                column("salesOrderNo", "t.sales_order_no", "销售订单号"),
                                column("customerName", "c.customer_name", "客户"),
                                column("orderDate", "t.order_date", "下单日期"),
                                column("deliveryDate", "t.delivery_date", "交期"),
                                column("totalQuantity", "t.total_quantity", "订单总量"),
                                column("status", "t.status", "状态")),
                        List.of(eq("customerId", "t.customer_id = :customerId", "客户ID"),
                                eq("status", "t.status = :status", "状态"),
                                dateFrom("orderDateFrom", "t.order_date >= :orderDateFrom", "下单日期起"),
                                dateTo("orderDateTo", "t.order_date <= :orderDateTo", "下单日期止")),
                        "t.sales_order_no LIKE CONCAT('%', :keyword, '%') OR c.customer_name LIKE CONCAT('%', :keyword, '%')",
                        "mfg:sales-order:view", "t.sales_order_id DESC", 200),
                new Resource("mfg_purchase_order", "采购订单",
                        "查采购订单：单号、供应商、收货仓库、下单日期、预计到货、状态与总量",
                        "mfg_purchase_order t"
                                + " JOIN mdm_supplier s ON s.supplier_id = t.supplier_id AND s.deleted_flag = 0"
                                + " LEFT JOIN wms_warehouse w ON w.warehouse_id = t.warehouse_id AND w.deleted_flag = 0",
                        List.of(column("purchaseOrderId", "t.purchase_order_id", "采购订单ID"),
                                column("purchaseOrderNo", "t.purchase_order_no", "采购订单号"),
                                column("supplierName", "s.supplier_name", "供应商"),
                                column("warehouseCode", "w.warehouse_code", "收货仓库"),
                                column("orderDate", "t.order_date", "下单日期"),
                                column("expectedDate", "t.expected_date", "预计到货"),
                                column("totalQuantity", "t.total_quantity", "总量"),
                                column("status", "t.status", "状态")),
                        List.of(eq("supplierId", "t.supplier_id = :supplierId", "供应商ID"),
                                eq("status", "t.status = :status", "状态"),
                                dateFrom("orderDateFrom", "t.order_date >= :orderDateFrom", "下单日期起"),
                                dateTo("orderDateTo", "t.order_date <= :orderDateTo", "下单日期止")),
                        "t.purchase_order_no LIKE CONCAT('%', :keyword, '%') OR s.supplier_name LIKE CONCAT('%', :keyword, '%')",
                        "mfg:purchase-order:view", "t.purchase_order_id DESC", 200)
        );
    }

    /** productionOrderResources。 */
    private static List<Resource> productionOrderResources() {
        return List.of(
                new Resource("mfg_production_order", "生产订单",
                        "查生产订单：单号、客户、产品、订单数量、交期、来源类型（MANUAL 手工/ SALES_ORDER 销售订单）与状态",
                        "mfg_production_order t"
                                + " LEFT JOIN mdm_customer c ON c.customer_id = t.customer_id AND c.deleted_flag = 0"
                                + " LEFT JOIN mdm_product p ON p.product_id = t.product_id AND p.deleted_flag = 0",
                        List.of(column("productionOrderId", "t.production_order_id", "生产订单ID"),
                                column("productionOrderNo", "t.production_order_no", "生产订单号"),
                                column("customerName", "c.customer_name", "客户"),
                                column("productName", "p.product_name", "产品"),
                                column("orderQuantity", "t.order_quantity", "订单数量"),
                                column("deliveryDate", "t.delivery_date", "交期"),
                                column("sourceType", "t.source_type", "来源类型"),
                                column("status", "t.status", "状态")),
                        List.of(eq("status", "t.status = :status", "状态"),
                                eq("productId", "t.product_id = :productId", "产品ID"),
                                dateFrom("deliveryDateFrom", "t.delivery_date >= :deliveryDateFrom", "交期起"),
                                dateTo("deliveryDateTo", "t.delivery_date <= :deliveryDateTo", "交期止")),
                        "t.production_order_no LIKE CONCAT('%', :keyword, '%') OR p.product_name LIKE CONCAT('%', :keyword, '%')"
                                + " OR c.customer_name LIKE CONCAT('%', :keyword, '%')",
                        "mfg:production-order:view", "t.production_order_id DESC", 200)
        );
    }

    /** workOrderResource。 */
    private static List<Resource> workOrderResource() {
        return List.of(
                new Resource("mfg_work_order", "生产工单",
                        "查生产工单：单号、产品、计划量、完成量、工单类型（ROOT 总装/ COMPONENT 组件）、父子关系与状态",
                        "mfg_work_order t LEFT JOIN mdm_product p ON p.product_id = t.product_id AND p.deleted_flag = 0"
                                + " LEFT JOIN mfg_production_plan pl ON pl.plan_id=t.plan_id AND pl.deleted_flag=0"
                                + " LEFT JOIN mfg_production_order po ON po.production_order_id=pl.production_order_id AND po.deleted_flag=0",
                        List.of(column("workOrderId", "t.work_order_id", "工单ID"),
                                column("workOrderNo", "t.work_order_no", "工单号"),
                                column("productionOrderNo", "po.production_order_no", "生产订单号"),
                                column("productionOrderId", "po.production_order_id", "生产订单ID"),
                                column("planNo", "pl.plan_no", "计划号"),
                                column("productName", "p.product_name", "产品"),
                                column("plannedQuantity", "t.planned_quantity", "计划量"),
                                column("completedQuantity", "t.completed_quantity", "完成量"),
                                column("workOrderType", "t.work_order_type", "工单类型"),
                                column("parentWorkOrderId", "t.parent_work_order_id", "上级工单ID"),
                                column("status", "t.status", "状态")),
                        List.of(eq("workOrderNo", "t.work_order_no = :workOrderNo", "工单号"),
                                eq("productionOrderNo", "po.production_order_no = :productionOrderNo", "生产订单号"),
                                eq("status", "t.status = :status", "状态"),
                                eq("productId", "t.product_id = :productId", "产品ID"),
                                eq("workOrderType", "t.work_order_type = :workOrderType", "工单类型")),
                        "t.work_order_no LIKE CONCAT('%', :keyword, '%') OR p.product_name LIKE CONCAT('%', :keyword, '%')",
                        "mfg:work-order:view", "t.work_order_id DESC", 300)
        );
    }

    /** 派工、报工和产品批次：让「完成这张订单的某一步」能从订单找到真实单据。 */
    private static List<Resource> executionDocumentResources() {
        return List.of(
                new Resource("mfg_dispatch_order", "派工单",
                        "查派工单：单号、工单、状态",
                        "mfg_dispatch_order t JOIN mfg_work_order w ON w.work_order_id=t.work_order_id AND w.deleted_flag=0",
                        List.of(column("dispatchNo", "t.dispatch_no", "派工单号"),
                                column("workOrderId", "t.work_order_id", "工单ID"),
                                column("workOrderNo", "w.work_order_no", "工单号"),
                                column("status", "t.status", "状态")),
                        List.of(eq("workOrderId", "t.work_order_id = :workOrderId", "工单ID"),
                                eq("status", "t.status = :status", "状态")),
                        "t.dispatch_no LIKE CONCAT('%', :keyword, '%')",
                        "mfg:work-order:view", "t.dispatch_id ASC", 100),
                new Resource("mfg_work_report", "报工单",
                        "查报工单：单号、工单、审核状态",
                        "mfg_work_report t JOIN mfg_task k ON k.task_id=t.task_id AND k.deleted_flag=0"
                                + " JOIN mfg_work_order w ON w.work_order_id=k.work_order_id AND w.deleted_flag=0",
                        List.of(column("reportNo", "t.report_no", "报工单号"),
                                column("workOrderId", "w.work_order_id", "工单ID"),
                                column("workOrderNo", "w.work_order_no", "工单号"),
                                column("taskNo", "k.task_no", "任务号"),
                                column("auditStatus", "t.audit_status", "审核状态")),
                        List.of(eq("workOrderId", "w.work_order_id = :workOrderId", "工单ID"),
                                eq("auditStatus", "t.audit_status = :auditStatus", "审核状态")),
                        "t.report_no LIKE CONCAT('%', :keyword, '%')",
                        "mfg:work-order:view", "t.report_id ASC", 100),
                new Resource("trace_product_lot", "产品批次",
                        "查产品批次：批号、工单、状态",
                        "trace_product_lot t",
                        List.of(column("lotNo", "t.lot_no", "批号"),
                                column("workOrderId", "t.work_order_id", "工单ID"),
                                column("productLotId", "t.product_lot_id", "批次ID"),
                                column("status", "t.status", "状态")),
                        List.of(eq("workOrderId", "t.work_order_id = :workOrderId", "工单ID"),
                                eq("status", "t.status = :status", "状态")),
                        "t.lot_no LIKE CONCAT('%', :keyword, '%')",
                        "mfg:work-order:view", "t.product_lot_id ASC", 100));
    }

    /** inboundOutboundResources。 */
    private static List<Resource> taskResource() {
        return List.of(new Resource("mfg_task", "工序任务",
                "查询指定工单的真实工序任务、工位、数量与状态；展示顺序不是开工许可，开工须校验冻结前序与工位占用",
                "mfg_task t JOIN mfg_work_order w ON w.work_order_id=t.work_order_id AND w.deleted_flag=0 "
                        + "LEFT JOIN process_operation o ON o.operation_id=t.operation_id AND o.deleted_flag=0 "
                        + "LEFT JOIN factory_station s ON s.station_id=t.station_id AND s.deleted_flag=0",
                List.of(column("taskNo", "t.task_no", "任务号"), column("workOrderNo", "w.work_order_no", "工单号"),
                        column("operationName", "o.operation_name", "工序"), column("stationName", "s.station_name", "工位"),
                        column("plannedQuantity", "t.planned_quantity", "计划数量"),
                        column("completedQuantity", "t.completed_quantity", "已完成数量"), column("status", "t.status", "状态")),
                List.of(eq("workOrderNo", "w.work_order_no = :workOrderNo", "工单号"), eq("status", "t.status = :status", "状态")),
                "t.task_no LIKE CONCAT('%', :keyword, '%') OR w.work_order_no LIKE CONCAT('%', :keyword, '%')",
                "mfg:task:view", "t.task_id ASC", 100));
    }

    /** inboundOutboundResources。 */
    private static List<Resource> inboundOutboundResources() {
        return List.of(
                new Resource("wms_inbound_order", "入库单",
                        "查入库单：单号、类型（PURCHASE 采购/ PRODUCTION 生产/ RETURN 退料）、仓库、来源单据、审核状态与入库时间",
                        "wms_inbound_order t JOIN wms_warehouse w ON w.warehouse_id = t.warehouse_id AND w.deleted_flag = 0",
                        List.of(column("inboundId", "t.inbound_id", "入库单ID"),
                                column("inboundNo", "t.inbound_no", "入库单号"),
                                column("inboundType", "t.inbound_type", "类型"),
                                column("warehouseCode", "w.warehouse_code", "仓库"),
                                column("sourceOrderType", "t.source_order_type", "来源单据类型"),
                                column("sourceOrderId", "t.source_order_id", "来源单据ID"),
                                column("auditStatus", "t.audit_status", "审核状态"),
                                column("inboundTime", "t.inbound_time", "入库时间")),
                        List.of(eq("inboundType", "t.inbound_type = :inboundType", "类型"),
                                eq("auditStatus", "t.audit_status = :auditStatus", "审核状态"),
                                eq("warehouseId", "t.warehouse_id = :warehouseId", "仓库ID"),
                                dateFrom("inboundTimeFrom", "t.inbound_time >= :inboundTimeFrom", "入库时间起"),
                                dateTo("inboundTimeTo", "t.inbound_time <= :inboundTimeTo", "入库时间止")),
                        "t.inbound_no LIKE CONCAT('%', :keyword, '%')",
                        "wms:inbound-order:view", "t.inbound_id DESC", 200),
                new Resource("wms_outbound_order", "出库单",
                        "查出库单：单号、类型（MATERIAL_ISSUE 生产领料/ SALE 销售发货）、仓库、关联工单、客户、审核状态与出库时间",
                        "wms_outbound_order t JOIN wms_warehouse w ON w.warehouse_id = t.warehouse_id AND w.deleted_flag = 0",
                        List.of(column("outboundId", "t.outbound_id", "出库单ID"),
                                column("outboundNo", "t.outbound_no", "出库单号"),
                                column("outboundType", "t.outbound_type", "类型"),
                                column("warehouseCode", "w.warehouse_code", "仓库"),
                                column("workOrderId", "t.work_order_id", "关联工单ID"),
                                column("sourceProductionOrderId", "t.source_production_order_id", "来源生产订单ID"),
                                column("inspectionId", "t.inspection_id", "检验单ID"),
                                column("customerId", "t.customer_id", "客户ID"),
                                column("auditStatus", "t.audit_status", "审核状态"),
                                column("outboundTime", "t.outbound_time", "出库时间")),
                        List.of(eq("outboundType", "t.outbound_type = :outboundType", "类型"),
                                eq("auditStatus", "t.audit_status = :auditStatus", "审核状态"),
                                eq("workOrderId", "t.work_order_id = :workOrderId", "工单ID"),
                                eq("sourceProductionOrderId", "t.source_production_order_id = :sourceProductionOrderId", "来源生产订单ID"),
                                dateFrom("outboundTimeFrom", "t.outbound_time >= :outboundTimeFrom", "出库时间起"),
                                dateTo("outboundTimeTo", "t.outbound_time <= :outboundTimeTo", "出库时间止")),
                        "t.outbound_no LIKE CONCAT('%', :keyword, '%')",
                        "wms:outbound-order:view", "t.outbound_id DESC", 200)
        );
    }

    /** qualityResources。 */
    private static List<Resource> qualityResources() {
        return List.of(
                new Resource("qms_inspection_order", "检验单",
                        "查检验单：单号、检验类型（INCOMING 来料/ PROCESS 过程/ FINAL 终检/ OUTGOING 出货）、抽样与合格数、检验结论、状态与检验时间",
                        "qms_inspection_order t",
                        List.of(column("inspectionId", "t.inspection_id", "检验单ID"),
                                column("inspectionNo", "t.inspection_no", "检验单号"),
                                column("inspectionType", "t.inspection_type", "检验类型"),
                                column("workOrderId", "t.work_order_id", "工单ID"),
                                column("sampleQuantity", "t.sample_quantity", "抽样数"),
                                column("qualifiedQuantity", "t.qualified_quantity", "合格数"),
                                column("unqualifiedQuantity", "t.unqualified_quantity", "不合格数"),
                                column("inspectionResult", "t.inspection_result", "检验结论"),
                                column("status", "t.status", "状态"),
                                column("inspectionTime", "t.inspection_time", "检验时间")),
                        List.of(eq("inspectionType", "t.inspection_type = :inspectionType", "检验类型"),
                                eq("status", "t.status = :status", "状态"),
                                eq("workOrderId", "t.work_order_id = :workOrderId", "工单ID"),
                                eq("inspectionResult", "t.inspection_result = :inspectionResult", "检验结论"),
                                dateFrom("inspectionTimeFrom", "t.inspection_time >= :inspectionTimeFrom", "检验时间起"),
                                dateTo("inspectionTimeTo", "t.inspection_time <= :inspectionTimeTo", "检验时间止")),
                        "t.inspection_no LIKE CONCAT('%', :keyword, '%')",
                        "qms:inspection-order:view", "t.inspection_id DESC", 300)
        );
    }

    /** exceptionEquipmentResources。 */
    private static List<Resource> exceptionEquipmentResources() {
        return List.of(
                new Resource("mfg_exception", "生产异常",
                        "查生产异常：异常单号、类型、来源预警、关联任务与工位、现象描述、处理结果与状态",
                        "mfg_exception t",
                        List.of(column("exceptionId", "t.exception_id", "异常ID"),
                                column("exceptionNo", "t.exception_no", "异常单号"),
                                column("exceptionType", "t.exception_type", "异常类型"),
                                column("taskId", "t.task_id", "关联任务ID"),
                                column("stationId", "t.station_id", "工位ID"),
                                column("description", "t.description", "现象"),
                                column("handleResult", "t.handle_result", "处理结果"),
                                column("status", "t.status", "状态"),
                                column("createdTime", "t.created_time", "上报时间")),
                        List.of(eq("status", "t.status = :status", "状态"),
                                eq("exceptionType", "t.exception_type = :exceptionType", "异常类型")),
                        "t.exception_no LIKE CONCAT('%', :keyword, '%') OR t.description LIKE CONCAT('%', :keyword, '%')",
                        "mfg:exception:view", "t.exception_id DESC", 200),
                new Resource("eam_equipment", "设备",
                        "查设备台账：编码、名称、型号、厂家、所属工位与状态",
                        "eam_equipment t"
                                + " LEFT JOIN factory_station s ON s.station_id = t.station_id AND s.deleted_flag = 0"
                                + " LEFT JOIN eam_equipment_category c ON c.category_id = t.category_id AND c.deleted_flag = 0",
                        List.of(column("equipmentId", "t.equipment_id", "设备ID"),
                                column("equipmentCode", "t.equipment_code", "设备编码"),
                                column("equipmentName", "t.equipment_name", "设备名称"),
                                column("categoryName", "c.category_name", "设备分类"),
                                column("model", "t.model", "型号"),
                                column("manufacturer", "t.manufacturer", "厂家"),
                                column("stationName", "s.station_name", "所属工位"),
                                column("status", "t.status", "状态")),
                        List.of(eq("status", "t.status = :status", "状态"),
                                eq("stationId", "t.station_id = :stationId", "工位ID")),
                        "t.equipment_code LIKE CONCAT('%', :keyword, '%') OR t.equipment_name LIKE CONCAT('%', :keyword, '%')"
                                + " OR t.model LIKE CONCAT('%', :keyword, '%')",
                        "eam:equipment:view", "t.equipment_id", 200)
        );
    }

    /** notificationResource。 */
    private static List<Resource> notificationResource() {
        return List.of(
                new Resource("sys_notification", "站内通知",
                        "查系统推给用户的待办与提醒：事件类型、分类、等级、标题、摘要、关联单据与发生时间。"
                                + "注意这不是待办数量表：status=CANCELLED 的记录表示该提醒已经失效（被取消），"
                                + "问「有几条提醒」「有多少待办」时必须带 status=ACTIVE 只统计有效条数，"
                                + "并把取消的条数单独说明，不能把取消的也算成有效提醒",
                        "sys_notification t",
                        List.of(column("notificationId", "t.notification_id", "通知ID"),
                                column("eventType", "t.event_type", "事件类型"),
                                column("category", "t.category", "分类"),
                                column("severity", "t.severity", "等级"),
                                column("title", "t.title", "标题"),
                                column("summary", "t.summary", "摘要"),
                                column("bizNo", "t.biz_no", "关联单据"),
                                column("occurredTime", "t.occurred_time", "发生时间"),
                                column("status", "t.status", "状态")),
                        List.of(eq("category", "t.category = :category", "分类"),
                                eq("severity", "t.severity = :severity", "等级"),
                                eq("status", "t.status = :status", "状态"),
                                dateFrom("occurredTimeFrom", "t.occurred_time >= :occurredTimeFrom", "发生时间起"),
                                dateTo("occurredTimeTo", "t.occurred_time <= :occurredTimeTo", "发生时间止")),
                        "t.title LIKE CONCAT('%', :keyword, '%') OR t.summary LIKE CONCAT('%', :keyword, '%')"
                                + " OR t.biz_no LIKE CONCAT('%', :keyword, '%')",
                        "sys:notification:view", "t.notification_id DESC", 200)
        );
    }


    /**
     * 按编码取资源，找不到返回 null。
     */
    public static Resource find(String code) {
        if (code == null) {
            return null;
        }
        String normalized = code.trim().toLowerCase(java.util.Locale.ROOT);
        return resources().stream().filter(item -> item.code().equals(normalized)).findFirst().orElse(null);
    }

    /**
     * 生成给大模型的资源清单文本。只给资源名、说明与筛选项，不含表名列名。
     */
    public static String describe() {
        StringBuilder builder = new StringBuilder();
        builder.append("【可查数据资源】选定 resource 后可用 filters 里的筛选项收窄范围：\n");
        for (Resource resource : resources()) {
            builder.append("- ").append(resource.code()).append("（").append(resource.label()).append("）：")
                    .append(resource.description());
            appendFilterHints(builder, resource.filters());
            builder.append('\n');
        }
        builder.append("另外 filters.keyword 可对名称、编码、单号做模糊匹配。\n");
        return builder.toString();
    }

    /** 追加一条资源的可筛选项清单；没有筛选项时整段省略。 */
    private static void appendFilterHints(StringBuilder builder, List<Filter> filters) {
        if (filters.isEmpty()) {
            return;
        }
        builder.append("；可筛选：");
        for (int index = 0; index < filters.size(); index++) {
            if (index > 0) {
                builder.append("、");
            }
            Filter filter = filters.get(index);
            builder.append(filter.name()).append("=").append(filter.label());
        }
    }
}
