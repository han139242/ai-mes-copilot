package com.neuedu.ems.mapper.ai;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * AI 结构化查询 Mapper。
 * 这里的 SQL 只做只读查询，返回 Map 列表，列别名直接对应前端字段名。
 * 所有查询均过滤 deleted_flag = 0，并严格按数据库真实字段取值，不做任何估算。
 */
@Mapper
public interface AiDataQueryMapper {
    @Select("""
            SELECT c.line_id AS lineId, c.day_of_week AS dayOfWeek,
                   c.effective_from AS effectiveFrom, c.effective_to AS effectiveTo,
                   c.capacity_factor AS capacityFactor, s.start_time AS startTime, s.end_time AS endTime
            FROM mfg_line_calendar_rule c
            JOIN org_shift s ON s.shift_id=c.shift_id AND s.deleted_flag=0
            JOIN factory_line l ON l.line_id=c.line_id AND l.deleted_flag=0 AND l.status IN ('ENABLE','RUNNING')
            WHERE c.deleted_flag=0 AND c.status='ENABLE'
              AND c.effective_from <= #{to} AND (c.effective_to IS NULL OR c.effective_to >= #{from})
            """)
    List<Map<String, Object>> selectCalendarRules(@Param("from") java.time.LocalDate from, @Param("to") java.time.LocalDate to);

    @Select("""
            SELECT line_id AS lineId, planned_start_time AS plannedStartTime, planned_end_time AS plannedEndTime
            FROM mfg_production_plan WHERE deleted_flag=0 AND status IN ('RELEASED','RUNNING','FINISHED')
              AND planned_start_time < #{end} AND planned_end_time > #{start}
            """)
    List<Map<String, Object>> selectBookedPlanWindows(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * 按物料与仓库汇总收发存。期初为开始日期之前的累计入减累计出，期末为期初加本期入减本期出。
     *
     * @param materialIds   物料 ID 列表，为空时不过滤
     * @param warehouseType 仓库类型，可空
     * @param start         统计开始时间（含）
     * @param end           统计结束时间（含）
     * @return 每个物料每个仓库一行
     */
    @Select("""
            <script>
            SELECT m.material_id AS materialId,
                   m.material_code AS materialCode,
                   m.material_name AS materialName,
                   COALESCE(u.unit_name, '') AS unitName,
                   COALESCE(w.warehouse_code, '') AS warehouseCode,
                   COALESCE(w.warehouse_name, '未指定仓库') AS warehouseName,
                   COALESCE(w.warehouse_type, '') AS warehouseType,
                   COALESCE(SUM(CASE WHEN mv.operation_time &lt; #{start}
                       THEN (CASE WHEN mv.direction = 'IN' THEN mv.quantity ELSE -mv.quantity END)
                       ELSE 0 END), 0) AS openingQuantity,
                   COALESCE(SUM(CASE WHEN mv.operation_time &gt;= #{start} AND mv.operation_time &lt;= #{end} AND mv.direction = 'IN'
                       THEN mv.quantity ELSE 0 END), 0) AS inboundQuantity,
                   COALESCE(SUM(CASE WHEN mv.operation_time &gt;= #{start} AND mv.operation_time &lt;= #{end} AND mv.direction = 'OUT'
                       THEN mv.quantity ELSE 0 END), 0) AS outboundQuantity,
                   COALESCE(SUM(CASE WHEN mv.operation_time &lt;= #{end}
                       THEN (CASE WHEN mv.direction = 'IN' THEN mv.quantity ELSE -mv.quantity END)
                       ELSE 0 END), 0) AS closingQuantity
            FROM wms_stock_movement mv
            JOIN mdm_material m ON m.material_id = mv.material_id AND m.deleted_flag = 0
            LEFT JOIN mdm_unit u ON u.unit_id = m.unit_id AND u.deleted_flag = 0
            LEFT JOIN wms_warehouse w ON w.warehouse_id = mv.warehouse_id AND w.deleted_flag = 0
            WHERE mv.deleted_flag = 0
              AND mv.material_id IS NOT NULL
              AND mv.operation_time IS NOT NULL
              <if test="materialIds != null and materialIds.size() > 0">
                AND mv.material_id IN
                <foreach collection="materialIds" item="mid" open="(" separator="," close=")">#{mid}</foreach>
              </if>
              <if test="warehouseType != null and warehouseType != ''">
                AND w.warehouse_type = #{warehouseType}
              </if>
            GROUP BY m.material_id, m.material_code, m.material_name, u.unit_name,
                     w.warehouse_code, w.warehouse_name, w.warehouse_type
            HAVING openingQuantity &lt;&gt; 0 OR inboundQuantity &lt;&gt; 0 OR outboundQuantity &lt;&gt; 0 OR closingQuantity &lt;&gt; 0
            ORDER BY m.material_code, w.warehouse_code
            </script>
            """)
    List<Map<String, Object>> selectInventoryFlow(@Param("materialIds") List<Long> materialIds,
                                                  @Param("warehouseType") String warehouseType,
                                                  @Param("start") LocalDateTime start,
                                                  @Param("end") LocalDateTime end);

    /**
     * 查询当前库存明细，含批次质量状态、库位与工单占用。
     *
     * @param warehouseCode   仓库编码，可空
     * @param warehouseType   仓库类型（RAW/LINE_SIDE/FINISHED…），可空
     * @param materialIds     物料 ID 列表，可空
     * @return 库存明细行
     */
    @Select("""
            <script>
            SELECT m.material_code AS materialCode,
                   m.material_name AS materialName,
                   COALESCE(u.unit_name, '') AS unitName,
                   COALESCE(w.warehouse_code, '') AS warehouseCode,
                   COALESCE(w.warehouse_name, '') AS warehouseName,
                   COALESCE(w.warehouse_type, '') AS warehouseType,
                   COALESCE(l.location_code, '') AS locationCode,
                   COALESCE(b.batch_no, '') AS batchNo,
                   COALESCE(b.quality_status, '') AS qualityStatus,
                   i.available_quantity AS availableQuantity,
                   i.locked_quantity AS lockedQuantity,
                   i.inspection_quantity AS inspectionQuantity,
                   (i.available_quantity + i.locked_quantity + i.inspection_quantity) AS totalQuantity,
                   COALESCE(wo.work_order_no, '') AS workOrderNo,
                   COALESCE(m.material_type, '') AS materialType
            FROM wms_inventory i
            JOIN mdm_material m ON m.material_id = i.material_id AND m.deleted_flag = 0
            LEFT JOIN mdm_unit u ON u.unit_id = m.unit_id AND u.deleted_flag = 0
            LEFT JOIN wms_warehouse w ON w.warehouse_id = i.warehouse_id AND w.deleted_flag = 0
            LEFT JOIN wms_location l ON l.location_id = i.location_id AND l.deleted_flag = 0
            LEFT JOIN wms_material_batch b ON b.batch_id = i.batch_id AND b.deleted_flag = 0
            LEFT JOIN mfg_work_order wo ON wo.work_order_id = i.work_order_id AND wo.deleted_flag = 0
            WHERE i.deleted_flag = 0
              <if test="warehouseCode != null and warehouseCode != ''">
                AND w.warehouse_code = #{warehouseCode}
              </if>
              <if test="warehouseType != null and warehouseType != ''">
                AND w.warehouse_type = #{warehouseType}
              </if>
              <if test="materialIds != null and materialIds.size() > 0">
                AND i.material_id IN
                <foreach collection="materialIds" item="mid" open="(" separator="," close=")">#{mid}</foreach>
              </if>
            ORDER BY m.material_code, w.warehouse_code, b.batch_no, l.location_code
            </script>
            """)
    List<Map<String, Object>> selectInventoryDetail(@Param("warehouseCode") String warehouseCode,
                                                    @Param("warehouseType") String warehouseType,
                                                    @Param("materialIds") List<Long> materialIds);

    /**
     * 查询工单表头。
     */
    @Select("""
            SELECT wo.work_order_no AS workOrderNo,
                   COALESCE(p.product_code, '') AS productCode,
                   COALESCE(p.product_name, '') AS productName,
                   wo.planned_quantity AS plannedQuantity,
                   wo.completed_quantity AS completedQuantity,
                   COALESCE(wo.status, '') AS status,
                   COALESCE(wo.work_order_type, 'ROOT') AS workOrderType,
                   COALESCE(parent.work_order_no, '') AS parentWorkOrderNo,
                   COALESCE(root.work_order_no, '') AS rootWorkOrderNo,
                   wo.created_time AS createdTime,
                   wo.work_order_id AS workOrderId,
                   wo.product_id AS productId
            FROM mfg_work_order wo
            LEFT JOIN mdm_product p ON p.product_id = wo.product_id AND p.deleted_flag = 0
            LEFT JOIN mfg_work_order parent ON parent.work_order_id = wo.parent_work_order_id AND parent.deleted_flag = 0
            LEFT JOIN mfg_work_order root ON root.work_order_id = wo.root_work_order_id AND root.deleted_flag = 0
            WHERE wo.deleted_flag = 0 AND wo.work_order_no = #{workOrderNo}
            LIMIT 1
            """)
    Map<String, Object> selectWorkOrderHeader(@Param("workOrderNo") String workOrderNo);

    /**
     * 查询工单领料明细。
     */
    @Select("""
            SELECT m.material_code AS materialCode,
                   m.material_name AS materialName,
                   COALESCE(u.unit_name, '') AS unitName,
                   wm.required_quantity AS requiredQuantity,
                   wm.issued_quantity AS issuedQuantity,
                   wm.shortage_quantity AS shortageQuantity,
                   COALESCE(wm.status, '') AS status
            FROM mfg_work_order_material wm
            JOIN mdm_material m ON m.material_id = wm.material_id AND m.deleted_flag = 0
            LEFT JOIN mdm_unit u ON u.unit_id = m.unit_id AND u.deleted_flag = 0
            WHERE wm.deleted_flag = 0 AND wm.work_order_id = #{workOrderId}
            ORDER BY m.material_code
            """)
    List<Map<String, Object>> selectWorkOrderMaterials(@Param("workOrderId") Long workOrderId);

    /**
     * 查询工单用料需求，带供给方式与组件来源子工单状态。
     *
     * <p>比 {@link #selectWorkOrderMaterials} 多三列：`supply_type`（STOCK 库存件 /
     * COMPONENT_WORK_ORDER 组件件）、来源子工单号与子工单状态。开工条件核查要靠这三列
     * 判断「缺的是还没生产完的组件、还是仓库里本来就没料」，两者的处置办法完全不同。</p>
     */
    @Select("""
            SELECT m.material_code AS materialCode,
                   m.material_name AS materialName,
                   COALESCE(u.unit_name, '') AS unitName,
                   wm.required_quantity AS requiredQuantity,
                   wm.issued_quantity AS issuedQuantity,
                   wm.shortage_quantity AS shortageQuantity,
                   COALESCE(wm.supply_type, 'STOCK') AS supplyType,
                   COALESCE(sub.work_order_no, '') AS sourceWorkOrderNo,
                   COALESCE(sub.status, '') AS sourceWorkOrderStatus,
                   COALESCE(wm.status, '') AS status
            FROM mfg_work_order_material wm
            JOIN mdm_material m ON m.material_id = wm.material_id AND m.deleted_flag = 0
            LEFT JOIN mdm_unit u ON u.unit_id = m.unit_id AND u.deleted_flag = 0
            LEFT JOIN mfg_work_order sub ON sub.work_order_id = wm.source_work_order_id AND sub.deleted_flag = 0
            WHERE wm.deleted_flag = 0 AND wm.work_order_id = #{workOrderId}
            ORDER BY m.material_code
            """)
    List<Map<String, Object>> selectWorkOrderReadinessMaterials(@Param("workOrderId") Long workOrderId);

    /**
     * 工单的工序任务统计：总数与「非待开始」条数，用来判断能否开工。
     */
    @Select("""
            SELECT COUNT(*) AS taskCount,
                   SUM(CASE WHEN COALESCE(t.status, '') <> #{waitStartStatus} THEN 1 ELSE 0 END) AS nonWaitingTaskCount
            FROM mfg_task t
            WHERE t.deleted_flag = 0 AND t.work_order_id = #{workOrderId}
            """)
    Map<String, Object> selectWorkOrderTaskStats(@Param("workOrderId") Long workOrderId,
                                                 @Param("waitStartStatus") String waitStartStatus);

    /**
     * 查询工单报工明细。
     */
    @Select("""
            SELECT wr.report_no AS reportNo,
                   COALESCE(o.operation_name, '') AS operationName,
                   COALESCE(st.station_name, '') AS stationName,
                   wr.qualified_quantity AS qualifiedQuantity,
                   wr.defect_quantity AS defectQuantity,
                   wr.actual_work_hours AS actualWorkHours,
                   wr.report_time AS reportTime,
                   COALESCE(wr.audit_status, '') AS auditStatus
            FROM mfg_work_report wr
            JOIN mfg_task t ON t.task_id = wr.task_id AND t.deleted_flag = 0
            LEFT JOIN process_operation o ON o.operation_id = t.operation_id AND o.deleted_flag = 0
            LEFT JOIN factory_station st ON st.station_id = wr.station_id AND st.deleted_flag = 0
            WHERE wr.deleted_flag = 0 AND t.work_order_id = #{workOrderId}
            ORDER BY wr.report_time, wr.report_no
            """)
    List<Map<String, Object>> selectWorkOrderReports(@Param("workOrderId") Long workOrderId);

    /**
     * 查询工单关联质检单。
     */
    @Select("""
            SELECT q.inspection_no AS inspectionNo,
                   COALESCE(q.inspection_type, '') AS inspectionType,
                   q.sample_quantity AS sampleQuantity,
                   q.qualified_quantity AS qualifiedQuantity,
                   q.unqualified_quantity AS unqualifiedQuantity,
                   COALESCE(q.inspection_result, '') AS inspectionResult,
                   COALESCE(q.status, '') AS status,
                   q.inspection_time AS inspectionTime
            FROM qms_inspection_order q
            WHERE q.deleted_flag = 0 AND q.work_order_id = #{workOrderId}
            ORDER BY q.inspection_time, q.inspection_no
            """)
    List<Map<String, Object>> selectWorkOrderInspections(@Param("workOrderId") Long workOrderId);

    /**
     * 查询工单产出产品批次。
     */
    @Select("""
            SELECT l.lot_no AS lotNo,
                   COALESCE(p.product_code, '') AS productCode,
                   COALESCE(p.product_name, '') AS productName,
                   l.batch_quantity AS batchQuantity,
                   l.qualified_quantity AS qualifiedQuantity,
                   l.defect_quantity AS defectQuantity,
                   COALESCE(l.status, '') AS status,
                   l.created_time AS createdTime
            FROM trace_product_lot l
            LEFT JOIN mdm_product p ON p.product_id = l.product_id AND p.deleted_flag = 0
            WHERE l.deleted_flag = 0 AND l.work_order_id = #{workOrderId}
            ORDER BY l.created_time
            """)
    List<Map<String, Object>> selectWorkOrderLots(@Param("workOrderId") Long workOrderId);

    /**
     * 按天汇总报工产量与不良。
     * 口径与看板保持一致：只统计各工艺路线末工序的已审核报工，不再区分整机工单与组件工单，
     * 否则组件工单的末工序产出会被漏掉，与看板产量对不上。
     */
    @Select("""
            SELECT DATE_FORMAT(wr.report_time, '%Y-%m-%d') AS reportDate,
                   COUNT(DISTINCT t.work_order_id) AS workOrderCount,
                   COUNT(DISTINCT wr.report_id) AS reportCount,
                   COALESCE(SUM(wr.qualified_quantity), 0) AS qualifiedQuantity,
                   COALESCE(SUM(wr.defect_quantity), 0) AS defectQuantity,
                   COALESCE(SUM(wr.actual_work_hours), 0) AS actualWorkHours
            FROM mfg_work_report wr
            JOIN mfg_task t ON t.task_id = wr.task_id AND t.deleted_flag = 0
            JOIN mfg_work_order wo ON wo.work_order_id=t.work_order_id AND wo.deleted_flag=0
            WHERE wr.deleted_flag = 0
              AND t.operation_id=(SELECT ri.operation_id FROM process_route_item ri WHERE ri.route_id=wo.route_id AND ri.deleted_flag=0 ORDER BY ri.sequence_no DESC,ri.route_item_id DESC LIMIT 1)
              AND wr.audit_status = 'APPROVED'
              AND wr.report_time >= #{start} AND wr.report_time <= #{end}
            GROUP BY DATE_FORMAT(wr.report_time, '%Y-%m-%d')
            ORDER BY reportDate
            """)
    List<Map<String, Object>> selectDailyProduction(@Param("start") LocalDateTime start,
                                                    @Param("end") LocalDateTime end);

    /**
     * 按物料/产品汇总不良。
     * 不良同时来自两处：质检单判定的不合格数量（qms_inspection_order.unqualified_quantity，与质量看板同源）
     * 和报工单登记的不良数量（mfg_work_report.defect_quantity）。两处分开列示再合计，避免只看报工漏掉来料与过程检验的不良。
     */
    @Select("""
            SELECT src.code AS productCode,
                   src.name AS productName,
                   COALESCE(SUM(src.inspectionDefect), 0) AS inspectionDefect,
                   COALESCE(SUM(src.reportDefect), 0) AS reportDefect,
                   COALESCE(SUM(src.inspectionDefect), 0) + COALESCE(SUM(src.reportDefect), 0) AS defectQuantity,
                   COUNT(DISTINCT src.docId) AS docCount
            FROM (
                SELECT COALESCE(m.material_code, p.product_code, '') AS code,
                       COALESCE(m.material_name, p.product_name, '未关联物料') AS name,
                       i.unqualified_quantity AS inspectionDefect,
                       0 AS reportDefect,
                       CONCAT('QI', i.inspection_id) AS docId
                FROM qms_inspection_order i
                LEFT JOIN wms_material_batch b ON b.batch_id = i.batch_id AND b.deleted_flag = 0
                LEFT JOIN mdm_material m ON m.material_id = b.material_id AND m.deleted_flag = 0
                LEFT JOIN mfg_work_order wo ON wo.work_order_id = i.work_order_id AND wo.deleted_flag = 0
                LEFT JOIN mdm_product p ON p.product_id = wo.product_id AND p.deleted_flag = 0
                WHERE i.deleted_flag = 0
                  AND COALESCE(i.unqualified_quantity, 0) > 0
                  AND COALESCE(i.inspection_time, i.created_time) >= #{start}
                  AND COALESCE(i.inspection_time, i.created_time) <= #{end}
                UNION ALL
                SELECT COALESCE(p.product_code, '') AS code,
                       COALESCE(p.product_name, '未关联产品') AS name,
                       0 AS inspectionDefect,
                       wr.defect_quantity AS reportDefect,
                       CONCAT('WR', wr.report_id) AS docId
                FROM mfg_work_report wr
                JOIN mfg_task t ON t.task_id = wr.task_id AND t.deleted_flag = 0
                JOIN mfg_work_order wo ON wo.work_order_id = t.work_order_id AND wo.deleted_flag = 0
                LEFT JOIN mdm_product p ON p.product_id = wo.product_id AND p.deleted_flag = 0
                WHERE wr.deleted_flag = 0
                  AND wr.audit_status = 'APPROVED'
                  AND COALESCE(wr.defect_quantity, 0) > 0
                  AND wr.report_time >= #{start} AND wr.report_time <= #{end}
            ) src
            WHERE (#{productKeyword} IS NULL OR #{productKeyword} = ''
                   OR src.code LIKE CONCAT('%', #{productKeyword}, '%')
                   OR src.name LIKE CONCAT('%', #{productKeyword}, '%'))
            GROUP BY src.code, src.name
            ORDER BY defectQuantity DESC
            """)
    List<Map<String, Object>> selectDefectByProduct(@Param("start") LocalDateTime start,
                                                    @Param("end") LocalDateTime end,
                                                    @Param("productKeyword") String productKeyword);

    /**
     * 检验口径的良率：本期已判定检验单的判定结论分布与抽检数量。
     *
     * <p>「良率」问的是质量口径，不能拿报工不良率顶替——报工只记末道工序登记的不良，
     * 来料与过程检验判不合格的那部分不在里面。合格率按检验结论统计（与质量看板同源），
     * 不按合格数量除抽检数量：历史导入的来料单只填了抽检数与 PASS 结论，合格数量是 0，
     * 拿它做分母会把合格率算成 0.31%，明显违背事实。</p>
     */
    @Select("""
            SELECT COUNT(*) AS inspectionCount,
                   COALESCE(SUM(CASE WHEN i.inspection_result IN ('PASS', 'FAIL') THEN 1 ELSE 0 END), 0) AS evaluatedCount,
                   COALESCE(SUM(CASE WHEN i.inspection_result = 'PASS' THEN 1 ELSE 0 END), 0) AS qualifiedCount,
                   COALESCE(SUM(CASE WHEN i.inspection_result = 'FAIL' THEN 1 ELSE 0 END), 0) AS unqualifiedCount,
                   COALESCE(SUM(i.sample_quantity), 0) AS sampleQuantity
            FROM qms_inspection_order i
            LEFT JOIN wms_material_batch b ON b.batch_id = i.batch_id AND b.deleted_flag = 0
            LEFT JOIN mdm_material m ON m.material_id = b.material_id AND m.deleted_flag = 0
            LEFT JOIN mfg_work_order wo ON wo.work_order_id = i.work_order_id AND wo.deleted_flag = 0
            LEFT JOIN mdm_product p ON p.product_id = wo.product_id AND p.deleted_flag = 0
            WHERE i.deleted_flag = 0
              AND i.status = 'FINISHED'
              AND COALESCE(i.inspection_time, i.created_time) >= #{start}
              AND COALESCE(i.inspection_time, i.created_time) <= #{end}
              AND (#{productKeyword} IS NULL OR #{productKeyword} = ''
                   OR COALESCE(m.material_code, p.product_code, '') LIKE CONCAT('%', #{productKeyword}, '%')
                   OR COALESCE(m.material_name, p.product_name, '') LIKE CONCAT('%', #{productKeyword}, '%'))
            """)
    Map<String, Object> selectQualityYield(@Param("start") LocalDateTime start,
                                           @Param("end") LocalDateTime end,
                                           @Param("productKeyword") String productKeyword);

    /**
     * 按工序汇总不良，口径与按物料/产品一致，同样合并质检与报工两处来源。
     */
    @Select("""
            SELECT src.code AS operationCode,
                   src.name AS operationName,
                   COALESCE(SUM(src.inspectionDefect), 0) AS inspectionDefect,
                   COALESCE(SUM(src.reportDefect), 0) AS reportDefect,
                   COALESCE(SUM(src.inspectionDefect), 0) + COALESCE(SUM(src.reportDefect), 0) AS defectQuantity,
                   COUNT(DISTINCT src.docId) AS docCount
            FROM (
                SELECT COALESCE(o.operation_code, '') AS code,
                       COALESCE(o.operation_name, '未关联工序') AS name,
                       0 AS inspectionDefect,
                       wr.defect_quantity AS reportDefect,
                       CONCAT('WR', wr.report_id) AS docId,
                       COALESCE(p.product_code, '') AS productCode,
                       COALESCE(p.product_name, '') AS productName
                FROM mfg_work_report wr
                JOIN mfg_task t ON t.task_id = wr.task_id AND t.deleted_flag = 0
                LEFT JOIN process_operation o ON o.operation_id = t.operation_id AND o.deleted_flag = 0
                LEFT JOIN mfg_work_order wo ON wo.work_order_id = t.work_order_id AND wo.deleted_flag = 0
                LEFT JOIN mdm_product p ON p.product_id = wo.product_id AND p.deleted_flag = 0
                WHERE wr.deleted_flag = 0
                  AND wr.audit_status = 'APPROVED'
                  AND COALESCE(wr.defect_quantity, 0) > 0
                  AND wr.report_time >= #{start} AND wr.report_time <= #{end}
                UNION ALL
                SELECT COALESCE(o.operation_code, '') AS code,
                       COALESCE(o.operation_name, CONCAT(i.inspection_type, '检验')) AS name,
                       i.unqualified_quantity AS inspectionDefect,
                       0 AS reportDefect,
                       CONCAT('QI', i.inspection_id) AS docId,
                       COALESCE(p.product_code, '') AS productCode,
                       COALESCE(p.product_name, '') AS productName
                FROM qms_inspection_order i
                LEFT JOIN mfg_task t ON t.task_id = i.task_id AND t.deleted_flag = 0
                LEFT JOIN process_operation o ON o.operation_id = t.operation_id AND o.deleted_flag = 0
                LEFT JOIN mfg_work_order wo ON wo.work_order_id = i.work_order_id AND wo.deleted_flag = 0
                LEFT JOIN mdm_product p ON p.product_id = wo.product_id AND p.deleted_flag = 0
                WHERE i.deleted_flag = 0
                  AND COALESCE(i.unqualified_quantity, 0) > 0
                  AND COALESCE(i.inspection_time, i.created_time) >= #{start}
                  AND COALESCE(i.inspection_time, i.created_time) <= #{end}
            ) src
            WHERE (#{productKeyword} IS NULL OR #{productKeyword} = ''
                   OR src.productCode LIKE CONCAT('%', #{productKeyword}, '%')
                   OR src.productName LIKE CONCAT('%', #{productKeyword}, '%'))
            GROUP BY src.code, src.name
            ORDER BY defectQuantity DESC
            """)
    List<Map<String, Object>> selectDefectByOperation(@Param("start") LocalDateTime start,
                                                      @Param("end") LocalDateTime end,
                                                      @Param("productKeyword") String productKeyword);

    /**
     * 按批次号查询物料批次主档，兼容成品批次号。
     */
    @Select("""
            SELECT b.batch_id AS batchId,
                   b.batch_no AS batchNo,
                   COALESCE(m.material_code, '') AS materialCode,
                   COALESCE(m.material_name, '') AS materialName,
                   COALESCE(u.unit_name, '') AS unitName,
                   COALESCE(b.quality_status, '') AS qualityStatus,
                   COALESCE(b.status, '') AS status,
                   b.production_date AS productionDate,
                   b.received_date AS receivedDate,
                   COALESCE(b.supplier_batch_no, '') AS supplierBatchNo,
                   COALESCE(b.source_order_type, '') AS sourceOrderType,
                   b.source_order_id AS sourceOrderId,
                   b.source_product_lot_id AS sourceProductLotId
            FROM wms_material_batch b
            LEFT JOIN mdm_material m ON m.material_id = b.material_id AND m.deleted_flag = 0
            LEFT JOIN mdm_unit u ON u.unit_id = m.unit_id AND u.deleted_flag = 0
            WHERE b.deleted_flag = 0 AND b.batch_no = #{batchNo}
            LIMIT 1
            """)
    Map<String, Object> selectBatchInfo(@Param("batchNo") String batchNo);

    /**
     * 按成品批次号查询产品批次。
     */
    @Select("""
            SELECT l.product_lot_id AS productLotId,
                   l.lot_no AS lotNo,
                   COALESCE(p.product_code, '') AS productCode,
                   COALESCE(p.product_name, '') AS productName,
                   l.batch_quantity AS batchQuantity,
                   l.qualified_quantity AS qualifiedQuantity,
                   l.defect_quantity AS defectQuantity,
                   COALESCE(l.status, '') AS status,
                   COALESCE(wo.work_order_no, '') AS workOrderNo,
                   l.created_time AS createdTime
            FROM trace_product_lot l
            LEFT JOIN mdm_product p ON p.product_id = l.product_id AND p.deleted_flag = 0
            LEFT JOIN mfg_work_order wo ON wo.work_order_id = l.work_order_id AND wo.deleted_flag = 0
            WHERE l.deleted_flag = 0 AND l.lot_no = #{lotNo}
            LIMIT 1
            """)
    Map<String, Object> selectProductLot(@Param("lotNo") String lotNo);

    /**
     * 查询批次来源入库单。
     */
    @Select("""
            SELECT io.inbound_no AS inboundNo,
                   COALESCE(io.inbound_type, '') AS inboundType,
                   COALESCE(w.warehouse_name, '') AS warehouseName,
                   d.inbound_quantity AS inboundQuantity,
                   io.inbound_time AS inboundTime,
                   COALESCE(io.audit_status, '') AS auditStatus
            FROM wms_inbound_detail d
            JOIN wms_inbound_order io ON io.inbound_id = d.inbound_id AND io.deleted_flag = 0
            LEFT JOIN wms_warehouse w ON w.warehouse_id = io.warehouse_id AND w.deleted_flag = 0
            WHERE d.deleted_flag = 0 AND d.batch_id = #{batchId}
            ORDER BY io.inbound_time
            """)
    List<Map<String, Object>> selectBatchInbound(@Param("batchId") Long batchId);

    /**
     * 查询批次领用出库的工单。
     */
    @Select("""
            SELECT wo.work_order_no AS workOrderNo,
                   COALESCE(p.product_name, '') AS productName,
                   wo.planned_quantity AS plannedQuantity,
                   wo.completed_quantity AS completedQuantity,
                   COALESCE(wo.status, '') AS status,
                   COALESCE(SUM(mv.quantity), 0) AS issuedQuantity,
                   MIN(mv.operation_time) AS firstIssueTime
            FROM wms_stock_movement mv
            JOIN mfg_work_order wo ON wo.work_order_id = mv.work_order_id AND wo.deleted_flag = 0
            LEFT JOIN mdm_product p ON p.product_id = wo.product_id AND p.deleted_flag = 0
            WHERE mv.deleted_flag = 0 AND mv.batch_id = #{batchId} AND mv.direction = 'OUT'
              AND mv.work_order_id IS NOT NULL AND mv.work_order_id > 0
            GROUP BY wo.work_order_no, p.product_name, wo.planned_quantity, wo.completed_quantity, wo.status
            ORDER BY firstIssueTime
            """)
    List<Map<String, Object>> selectBatchIssues(@Param("batchId") Long batchId);

    /**
     * 查询批次对应的报工消耗。
     */
    @Select("""
            SELECT wr.report_no AS reportNo,
                   wo.work_order_no AS workOrderNo,
                   COALESCE(o.operation_name, '') AS operationName,
                   rm.used_quantity AS usedQuantity,
                   rm.scrap_quantity AS scrapQuantity,
                   wr.report_time AS reportTime
            FROM mfg_report_material rm
            JOIN mfg_work_report wr ON wr.report_id = rm.report_id AND wr.deleted_flag = 0
            JOIN mfg_task t ON t.task_id = wr.task_id AND t.deleted_flag = 0
            JOIN mfg_work_order wo ON wo.work_order_id = t.work_order_id AND wo.deleted_flag = 0
            LEFT JOIN process_operation o ON o.operation_id = t.operation_id AND o.deleted_flag = 0
            WHERE rm.deleted_flag = 0 AND rm.batch_id = #{batchId}
            ORDER BY wr.report_time
            """)
    List<Map<String, Object>> selectBatchReports(@Param("batchId") Long batchId);

    /**
     * 查询批次相关检验单。
     */
    @Select("""
            SELECT q.inspection_no AS inspectionNo,
                   COALESCE(q.inspection_type, '') AS inspectionType,
                   q.sample_quantity AS sampleQuantity,
                   q.qualified_quantity AS qualifiedQuantity,
                   q.unqualified_quantity AS unqualifiedQuantity,
                   COALESCE(q.inspection_result, '') AS inspectionResult,
                   COALESCE(q.status, '') AS status,
                   q.inspection_time AS inspectionTime
            FROM qms_inspection_order q
            WHERE q.deleted_flag = 0 AND (q.batch_id = #{batchId} OR q.product_lot_id = #{productLotId})
            ORDER BY q.inspection_time
            """)
    List<Map<String, Object>> selectBatchInspections(@Param("batchId") Long batchId,
                                                     @Param("productLotId") Long productLotId);

    /**
     * 查询由该产品批次入库形成的成品批次。
     */
    @Select("""
            SELECT b.batch_no AS batchNo,
                   COALESCE(m.material_name, '') AS materialName,
                   COALESCE(b.quality_status, '') AS qualityStatus,
                   COALESCE(b.status, '') AS status,
                   b.received_date AS receivedDate
            FROM wms_material_batch b
            LEFT JOIN mdm_material m ON m.material_id = b.material_id AND m.deleted_flag = 0
            WHERE b.deleted_flag = 0 AND b.source_product_lot_id = #{productLotId}
            ORDER BY b.received_date
            """)
    List<Map<String, Object>> selectBatchByProductLot(@Param("productLotId") Long productLotId);

    /**
     * 按产线汇总排程占用。以产线为主表左连排程项，保证没有排程的产线也能体现出来，
     * 便于回答"本周排程和产能怎么样"时给出全部产线的负荷对比。
     */
    @Select("""
            SELECT l.line_id AS lineId,
                   l.line_code AS lineCode,
                   l.line_name AS lineName,
                   l.daily_capacity AS dailyCapacity,
                   COALESCE(l.status, '') AS lineStatus,
                   COUNT(si.schedule_item_id) AS itemCount,
                   COALESCE(SUM(si.planned_quantity), 0) AS plannedQuantity,
                   COALESCE(SUM(si.estimated_minutes), 0) AS loadMinutes,
                   SUM(CASE WHEN si.tardiness_minutes > 0 THEN 1 ELSE 0 END) AS lateCount,
                   SUM(CASE WHEN si.conflict_reason IS NOT NULL AND si.conflict_reason <> '' THEN 1 ELSE 0 END) AS conflictCount
            FROM factory_line l
            LEFT JOIN mfg_schedule_item si ON si.line_id = l.line_id AND si.deleted_flag = 0
                 AND si.status = 'CONFIRMED'
                 AND si.planned_start_time <= #{end} AND si.planned_end_time >= #{start}
                 AND EXISTS (SELECT 1 FROM mfg_production_plan confirmed_plan WHERE confirmed_plan.plan_id=si.plan_id AND confirmed_plan.deleted_flag=0 AND confirmed_plan.status IN ('RELEASED','RUNNING','FINISHED'))
            WHERE l.deleted_flag = 0
            GROUP BY l.line_id, l.line_code, l.line_name, l.daily_capacity, l.status
            ORDER BY l.line_code
            """)
    List<Map<String, Object>> selectScheduleByLine(@Param("start") LocalDateTime start,
                                                   @Param("end") LocalDateTime end);

    /**
     * 查询排程明细。
     */
    @Select("""
            SELECT l.line_name AS lineName,
                   COALESCE(po.production_order_no, '') AS productionOrderNo,
                   COALESCE(p.product_code, '') AS productCode,
                   COALESCE(p.product_name, '') AS productName,
                   si.planned_quantity AS plannedQuantity,
                   si.planned_start_time AS plannedStartTime,
                   si.planned_end_time AS plannedEndTime,
                   si.estimated_minutes AS estimatedMinutes,
                   COALESCE(si.tardiness_minutes, 0) AS tardinessMinutes,
                   COALESCE(si.status, '') AS status,
                   COALESCE(si.conflict_reason, '') AS conflictReason
            FROM mfg_schedule_item si
            JOIN factory_line l ON l.line_id = si.line_id AND l.deleted_flag = 0
            LEFT JOIN mfg_production_order po ON po.production_order_id = si.production_order_id AND po.deleted_flag = 0
            LEFT JOIN mdm_product p ON p.product_id = si.product_id AND p.deleted_flag = 0
            WHERE si.deleted_flag = 0 AND si.status = 'CONFIRMED'
              AND EXISTS (SELECT 1 FROM mfg_production_plan confirmed_plan WHERE confirmed_plan.plan_id=si.plan_id AND confirmed_plan.deleted_flag=0 AND confirmed_plan.status IN ('RELEASED','RUNNING','FINISHED'))
              AND si.planned_start_time <= #{end} AND si.planned_end_time >= #{start}
            ORDER BY si.planned_start_time
            """)
    List<Map<String, Object>> selectScheduleItems(@Param("start") LocalDateTime start,
                                                  @Param("end") LocalDateTime end);

    /**
     * 查询生产订单表头。
     */
    @Select("""
            SELECT po.production_order_id AS productionOrderId,
                   po.production_order_no AS productionOrderNo,
                   COALESCE(p.product_code, '') AS productCode,
                   COALESCE(p.product_name, '') AS productName,
                   COALESCE(c.customer_name, '') AS customerName,
                   po.order_quantity AS orderQuantity,
                   po.delivery_date AS deliveryDate,
                   COALESCE(po.status, '') AS status,
                   po.created_time AS createdTime
            FROM mfg_production_order po
            LEFT JOIN mdm_product p ON p.product_id = po.product_id AND p.deleted_flag = 0
            LEFT JOIN mdm_customer c ON c.customer_id = po.customer_id AND c.deleted_flag = 0
            WHERE po.deleted_flag = 0 AND po.production_order_no = #{orderNo}
            LIMIT 1
            """)
    Map<String, Object> selectOrderHeader(@Param("orderNo") String orderNo);

    /**
     * 查询订单下的生产计划与工单。
     */
    @Select("""
            SELECT pl.plan_no AS planNo, COALESCE(pl.plan_type,'ROOT') AS planType,
                   COALESCE(l.line_name, '') AS lineName,
                   pl.planned_quantity AS plannedQuantity,
                   pl.planned_start_time AS plannedStartTime,
                   pl.planned_end_time AS plannedEndTime,
                   COALESCE(pl.status, '') AS planStatus,
                   COALESCE(wo.work_order_no, '') AS workOrderNo,
                   COALESCE(p.product_name, '') AS workOrderProduct,
                   wo.planned_quantity AS workOrderPlannedQuantity,
                   wo.completed_quantity AS workOrderCompletedQuantity,
                   COALESCE(wo.status, '') AS workOrderStatus
            FROM mfg_production_plan pl
            LEFT JOIN factory_line l ON l.line_id = pl.line_id AND l.deleted_flag = 0
            LEFT JOIN mfg_work_order wo ON wo.plan_id = pl.plan_id AND wo.deleted_flag = 0
            LEFT JOIN mdm_product p ON p.product_id = wo.product_id AND p.deleted_flag = 0
            WHERE pl.deleted_flag = 0 AND pl.production_order_id = #{productionOrderId}
            ORDER BY pl.planned_start_time, wo.work_order_no
            """)
    List<Map<String, Object>> selectOrderPlans(@Param("productionOrderId") Long productionOrderId);

    /**
     * 查询订单工单清单，用于统计完工与入库。
     */
    @Select("""
            SELECT wo.work_order_id AS workOrderId, COALESCE(wo.work_order_type,'ROOT') AS workOrderType,
                   wo.work_order_no AS workOrderNo,
                   COALESCE(p.product_name, '') AS productName,
                   wo.planned_quantity AS plannedQuantity,
                   wo.completed_quantity AS completedQuantity,
                   COALESCE(wo.status, '') AS status
            FROM mfg_work_order wo
            JOIN mfg_production_plan pl ON pl.plan_id = wo.plan_id AND pl.deleted_flag = 0
            LEFT JOIN mdm_product p ON p.product_id = wo.product_id AND p.deleted_flag = 0
            WHERE wo.deleted_flag = 0 AND pl.production_order_id = #{productionOrderId}
            ORDER BY wo.work_order_no
            """)
    List<Map<String, Object>> selectOrderWorkOrders(@Param("productionOrderId") Long productionOrderId);

    /**
     * 查询订单关联的生产入库。
     *
     * <p>生产入库有两个来源：按工单入库（source_order_type=WORK_ORDER）和按产品批次入库
     * （source_order_type=PRODUCT_LOT，AI 工作台第 7 阶段走的就是这条）。只认前者会让
     * 订单进度永远显示「已入库 0」，所以这里用批次回查工单，把两种来源都算进本订单。</p>
     */
    @Select("""
            <script>
            SELECT io.inbound_no AS inboundNo,
                   COALESCE(w.warehouse_name, '') AS warehouseName,
                   COALESCE(m.material_name, '') AS materialName,
                   d.inbound_quantity AS inboundQuantity,
                   io.inbound_time AS inboundTime,
                   COALESCE(io.audit_status, '') AS auditStatus
            FROM wms_inbound_order io
            JOIN wms_inbound_detail d ON d.inbound_id = io.inbound_id AND d.deleted_flag = 0
            LEFT JOIN mdm_material m ON m.material_id = d.material_id AND m.deleted_flag = 0
            LEFT JOIN wms_warehouse w ON w.warehouse_id = io.warehouse_id AND w.deleted_flag = 0
            LEFT JOIN trace_product_lot l ON io.source_order_type = 'PRODUCT_LOT'
                 AND l.product_lot_id = io.source_order_id
            WHERE io.deleted_flag = 0
              AND (
                (<if test="workOrderIds != null and workOrderIds.size() > 0">
                io.source_order_type = 'WORK_ORDER' AND io.source_order_id IN
                <foreach collection="workOrderIds" item="wid" open="(" separator="," close=")">#{wid}</foreach>
                </if>
                <if test="workOrderIds == null or workOrderIds.size() == 0">1 = 0</if>)
                OR
                (<if test="workOrderIds != null and workOrderIds.size() > 0">
                io.source_order_type = 'PRODUCT_LOT' AND l.work_order_id IN
                <foreach collection="workOrderIds" item="wid" open="(" separator="," close=")">#{wid}</foreach>
                </if>
                <if test="workOrderIds == null or workOrderIds.size() == 0">1 = 0</if>)
              )
            ORDER BY io.inbound_time
            </script>
            """)
    List<Map<String, Object>> selectOrderInbound(@Param("workOrderIds") List<Long> workOrderIds);

    /**
     * 查询订单发货记录。
     */
    @Select("""
            SELECT oo.outbound_no AS outboundNo,
                   COALESCE(c.customer_name, '') AS customerName,
                   COALESCE(m.material_name, '') AS materialName,
                   d.outbound_quantity AS outboundQuantity,
                   oo.outbound_time AS outboundTime,
                   COALESCE(oo.audit_status, '') AS auditStatus
            FROM wms_outbound_order oo
            JOIN wms_outbound_detail d ON d.outbound_id = oo.outbound_id AND d.deleted_flag = 0
            LEFT JOIN mdm_customer c ON c.customer_id = oo.customer_id AND c.deleted_flag = 0
            LEFT JOIN mdm_material m ON m.material_id = d.material_id AND m.deleted_flag = 0
            WHERE oo.deleted_flag = 0 AND oo.source_production_order_id = #{productionOrderId}
            ORDER BY oo.outbound_time
            """)
    List<Map<String, Object>> selectOrderShipments(@Param("productionOrderId") Long productionOrderId);

    /**
     * 查询故障设备与未关闭报修单。
     */
    @Select("""
            <script>
            SELECT e.equipment_code AS equipmentCode,
                   e.equipment_name AS equipmentName,
                   COALESCE(e.status, '') AS equipmentStatus,
                   COALESCE(st.station_name, '') AS stationName,
                   st.station_id AS stationId,
                   COALESCE(r.repair_no, '') AS repairNo,
                   COALESCE(r.fault_description, '') AS faultDescription,
                   COALESCE(r.status, '') AS repairStatus,
                   r.reported_time AS reportedTime
            FROM eam_equipment e
            LEFT JOIN factory_station st ON st.station_id = e.station_id AND st.deleted_flag = 0
            LEFT JOIN eam_repair_order r ON r.equipment_id = e.equipment_id AND r.deleted_flag = 0
                 AND r.status &lt;&gt; 'CLOSED'
            WHERE e.deleted_flag = 0
              <if test="keyword != null and keyword != ''">
                AND (e.equipment_code LIKE CONCAT('%', #{keyword}, '%')
                     OR e.equipment_name LIKE CONCAT('%', #{keyword}, '%'))
              </if>
            ORDER BY r.reported_time DESC, e.equipment_code
            </script>
            """)
    List<Map<String, Object>> selectEquipmentFaults(@Param("keyword") String keyword);

    /**
     * 查询指定工位上的未完成任务及所属工单。
     */
    @Select("""
            <script>
            SELECT t.task_no AS taskNo,
                   COALESCE(o.operation_name, '') AS operationName,
                   COALESCE(st.station_name, '') AS stationName,
                   t.planned_quantity AS plannedQuantity,
                   t.completed_quantity AS completedQuantity,
                   COALESCE(t.status, '') AS taskStatus,
                   COALESCE(wo.work_order_no, '') AS workOrderNo,
                   COALESCE(p.product_name, '') AS productName,
                   COALESCE(po.production_order_no, '') AS productionOrderNo,
                   po.delivery_date AS deliveryDate
            FROM mfg_task t
            LEFT JOIN process_operation o ON o.operation_id = t.operation_id AND o.deleted_flag = 0
            LEFT JOIN factory_station st ON st.station_id = t.station_id AND st.deleted_flag = 0
            LEFT JOIN mfg_work_order wo ON wo.work_order_id = t.work_order_id AND wo.deleted_flag = 0
            LEFT JOIN mdm_product p ON p.product_id = wo.product_id AND p.deleted_flag = 0
            LEFT JOIN mfg_production_plan pl ON pl.plan_id = wo.plan_id AND pl.deleted_flag = 0
            LEFT JOIN mfg_production_order po ON po.production_order_id = pl.production_order_id AND po.deleted_flag = 0
            WHERE t.deleted_flag = 0
              AND t.status NOT IN ('FINISHED', 'CLOSED')
              <if test="stationIds != null and stationIds.size() > 0">
                AND t.station_id IN
                <foreach collection="stationIds" item="sid" open="(" separator="," close=")">#{sid}</foreach>
              </if>
              <if test="stationIds == null or stationIds.size() == 0">
                AND 1 = 0
              </if>
            ORDER BY po.delivery_date, t.task_no
            </script>
            """)
    List<Map<String, Object>> selectEquipmentAffectedTasks(@Param("stationIds") List<Long> stationIds);

    /**
     * 查询产品 BOM 直接子项。
     */
    @Select("""
            SELECT m.material_code AS materialCode,
                   m.material_name AS materialName,
                   COALESCE(u.unit_name, '') AS unitName,
                   bi.quantity AS unitQuantity,
                   COALESCE(bi.loss_rate, 0) AS lossRate
            FROM mdm_bom_item bi
            JOIN mdm_bom b ON b.bom_id = bi.bom_id AND b.deleted_flag = 0
            JOIN mdm_material m ON m.material_id = bi.material_id AND m.deleted_flag = 0
            LEFT JOIN mdm_unit u ON u.unit_id = m.unit_id AND u.deleted_flag = 0
            WHERE bi.deleted_flag = 0 AND b.product_id = #{productId}
              AND b.status = 'RELEASED'
            ORDER BY bi.sequence_no, bi.bom_item_id
            """)
    List<Map<String, Object>> selectProductBom(@Param("productId") Long productId);

    /**
     * 查产品在指定日期生效的已发布 BOM 头。
     *
     * <p>同一个产品可能有多版 BOM，按生效日期倒序取最新一版；没有生效版本时返回 null，
     * 由调用方明确告诉用户「算不出用量」，而不是拿旧版本凑一个数。</p>
     */
    @Select("""
            SELECT b.bom_id AS bomId, b.bom_code AS bomCode, b.bom_version AS bomVersion
            FROM mdm_bom b
            WHERE b.deleted_flag = 0 AND b.product_id = #{productId} AND b.status = 'RELEASED'
              AND (b.effective_date IS NULL OR b.effective_date <= #{asOf})
              AND (b.effective_to IS NULL OR b.effective_to >= #{asOf})
            ORDER BY b.effective_date DESC, b.bom_id DESC
            LIMIT 1
            """)
    Map<String, Object> selectReleasedBom(@Param("productId") Long productId,
                                          @Param("asOf") java.time.LocalDate asOf);

    /**
     * 查询指定物料的有效可用库存（放行且可用状态）。
     */
    @Select("""
            <script>
            SELECT i.material_id AS materialId,
                   COALESCE(SUM(CASE WHEN b.quality_status IN ('PASS', 'CONCESSION')
                                      AND b.status IN ('AVAILABLE', 'ENABLE')
                                      AND w.status = 'ENABLE'
                                     THEN i.available_quantity ELSE 0 END), 0) AS availableQuantity
            FROM wms_inventory i
            LEFT JOIN wms_material_batch b ON b.batch_id = i.batch_id AND b.deleted_flag = 0
            LEFT JOIN wms_warehouse w ON w.warehouse_id = i.warehouse_id AND w.deleted_flag = 0
            WHERE i.deleted_flag = 0 AND i.work_order_id = 0
              <if test="materialIds != null and materialIds.size() > 0">
                AND i.material_id IN
                <foreach collection="materialIds" item="mid" open="(" separator="," close=")">#{mid}</foreach>
              </if>
              <if test="materialIds == null or materialIds.size() == 0">
                AND 1 = 0
              </if>
            GROUP BY i.material_id
            </script>
            """)
    List<Map<String, Object>> selectAvailableStock(@Param("materialIds") List<Long> materialIds);

    /**
     * 查询产品可生产的产线及路线标准工时。
     */
    @Select("""
            SELECT l.line_id AS lineId,
                   l.line_code AS lineCode,
                   l.line_name AS lineName,
                   COALESCE(SUM(ri.standard_time), 0) AS unitMinutes
            FROM mfg_line_product_capability cap
            JOIN factory_line l ON l.line_id = cap.line_id AND l.deleted_flag = 0
            LEFT JOIN process_route_item ri ON ri.route_id = cap.route_id AND ri.deleted_flag = 0
            WHERE cap.deleted_flag = 0 AND cap.product_id = #{productId}
              AND cap.status = 'ENABLE' AND l.status IN ('ENABLE','RUNNING')
            GROUP BY l.line_id, l.line_code, l.line_name
            ORDER BY l.line_code
            """)
    List<Map<String, Object>> selectCapableLines(@Param("productId") Long productId);

    /**
     * 按产品型号或名称查询工单清单，用于“L200 有哪些工单”这类列表型提问。
     */
    @Select("""
            <script>
            SELECT wo.work_order_no AS workOrderNo,
                   COALESCE(wo.work_order_type, 'ROOT') AS workOrderType,
                   COALESCE(p.product_code, '') AS productCode,
                   COALESCE(p.product_name, '') AS productName,
                   wo.planned_quantity AS plannedQuantity,
                   wo.completed_quantity AS completedQuantity,
                   COALESCE(wo.status, '') AS status,
                   COALESCE(parent.work_order_no, '') AS parentWorkOrderNo,
                   COALESCE(root.work_order_no, '') AS rootWorkOrderNo,
                   COALESCE(po.production_order_no, '') AS productionOrderNo,
                   COALESCE(l.line_name, '') AS lineName,
                   wo.created_time AS createdTime
            FROM mfg_work_order wo
            LEFT JOIN mdm_product p ON p.product_id = wo.product_id AND p.deleted_flag = 0
            LEFT JOIN mfg_production_plan pl ON pl.plan_id = wo.plan_id AND pl.deleted_flag = 0
            LEFT JOIN mfg_production_order po ON po.production_order_id = pl.production_order_id AND po.deleted_flag = 0
            LEFT JOIN factory_line l ON l.line_id = pl.line_id AND l.deleted_flag = 0
            LEFT JOIN mfg_work_order parent ON parent.work_order_id = wo.parent_work_order_id AND parent.deleted_flag = 0
            LEFT JOIN mfg_work_order root ON root.work_order_id = wo.root_work_order_id AND root.deleted_flag = 0
            WHERE wo.deleted_flag = 0
              <if test="keyword != null and keyword != ''">
                AND (p.product_code LIKE CONCAT('%', #{keyword}, '%')
                     OR p.product_name LIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="status != null and status != ''">
                <choose>
                  <when test="status == 'UNFINISHED'">
                    AND COALESCE(wo.status, '') NOT IN ('FINISHED', 'CLOSED')
                  </when>
                  <when test="status == 'NOT_STARTED'">
                    AND NOT EXISTS (SELECT 1 FROM mfg_task t
                                     WHERE t.work_order_id = wo.work_order_id
                                       AND t.deleted_flag = 0
                                       AND t.status IN ('RUNNING', 'PAUSED', 'FINISHED'))
                  </when>
                  <otherwise>
                    AND wo.status = #{status}
                  </otherwise>
                </choose>
              </if>
            ORDER BY wo.created_time DESC, wo.work_order_no DESC
            </script>
            """)
    List<Map<String, Object>> selectWorkOrdersByProduct(@Param("keyword") String keyword,
                                                        @Param("status") String status);

    /**
     * 按客户名称查询该客户下的生产订单与交付进度。
     */
    @Select("""
            <script>
            SELECT po.production_order_no AS productionOrderNo,
                   COALESCE(c.customer_name, '') AS customerName,
                   COALESCE(p.product_code, '') AS productCode,
                   COALESCE(p.product_name, '') AS productName,
                   po.order_quantity AS orderQuantity,
                   COALESCE(SUM(DISTINCT wo.completed_quantity), 0) AS completedQuantity,
                   po.delivery_date AS deliveryDate,
                   COALESCE(po.status, '') AS status,
                   po.created_time AS createdTime
            FROM mfg_production_order po
            LEFT JOIN mdm_customer c ON c.customer_id = po.customer_id AND c.deleted_flag = 0
            LEFT JOIN mdm_product p ON p.product_id = po.product_id AND p.deleted_flag = 0
            LEFT JOIN mfg_production_plan pl ON pl.production_order_id = po.production_order_id
                 AND pl.deleted_flag = 0 AND COALESCE(pl.plan_type, 'ROOT') = 'ROOT'
            LEFT JOIN mfg_work_order wo ON wo.plan_id = pl.plan_id AND wo.deleted_flag = 0
                 AND COALESCE(wo.work_order_type, 'ROOT') = 'ROOT'
            WHERE po.deleted_flag = 0
              <if test="customerKeyword != null and customerKeyword != ''">
                AND c.customer_name LIKE CONCAT('%', #{customerKeyword}, '%')
              </if>
            GROUP BY po.production_order_id, po.production_order_no, c.customer_name, p.product_code,
                     p.product_name, po.order_quantity, po.delivery_date, po.status, po.created_time
            ORDER BY po.created_time DESC, po.production_order_no DESC
            </script>
            """)
    List<Map<String, Object>> selectOrdersByCustomer(@Param("customerKeyword") String customerKeyword);

    /**
     * 查询尚未完成的检验单，覆盖来料、过程、终检与出货检验。
     */
    @Select("""
            <script>
            SELECT q.inspection_no AS inspectionNo,
                   COALESCE(q.inspection_type, '') AS inspectionType,
                   COALESCE(wo.work_order_no, '') AS workOrderNo,
                   COALESCE(p.product_name, '') AS productName,
                   COALESCE(m.material_name, '') AS materialName,
                   COALESCE(b.batch_no, '') AS batchNo,
                   q.sample_quantity AS sampleQuantity,
                   COALESCE(q.status, '') AS status,
                   q.inspection_time AS inspectionTime,
                   q.created_time AS createdTime
            FROM qms_inspection_order q
            LEFT JOIN mfg_work_order wo ON wo.work_order_id = q.work_order_id AND wo.deleted_flag = 0
            LEFT JOIN mdm_product p ON p.product_id = wo.product_id AND p.deleted_flag = 0
            LEFT JOIN wms_material_batch b ON b.batch_id = q.batch_id AND b.deleted_flag = 0
            LEFT JOIN mdm_material m ON m.material_id = b.material_id AND m.deleted_flag = 0
            WHERE q.deleted_flag = 0
              AND COALESCE(q.status, '') NOT IN ('FINISHED', 'CLOSED')
              <if test="inspectionType != null and inspectionType != ''">
                AND q.inspection_type = #{inspectionType}
              </if>
            ORDER BY q.created_time, q.inspection_no
            </script>
            """)
    List<Map<String, Object>> selectPendingInspections(@Param("inspectionType") String inspectionType);

    /**
     * 查一张工单的组件子工单及其路线标准工时，用于推演「先做完组件再开工」需要多长时间。
     *
     * <p>只读。单件工时为该产品路线全部工序标准工时之和，批量工时按计划数量线性放大。
     * 同时带出来源生产计划的状态：组件工单要能下达，来源计划必须先是已生效，
     * 否则方案里写的「下达组件工单」点下去就会被前置检查拦住。</p>
     */
    @Select("""
            SELECT wo.work_order_no AS workOrderNo,
                   wo.work_order_id AS workOrderId,
                   COALESCE(wo.status, '') AS status,
                   wo.planned_quantity AS plannedQuantity,
                   COALESCE(p.product_name, '') AS productName,
                   COALESCE(p.product_code, '') AS productCode,
                   wo.product_id AS productId,
                   COALESCE(wo.plan_id, 0) AS planId,
                   COALESCE(pl.plan_no, '') AS planNo,
                   COALESCE(pl.status, '') AS planStatus,
                   COALESCE((SELECT SUM(ri.standard_time) FROM process_route_item ri
                             WHERE ri.route_id = wo.route_id AND ri.deleted_flag = 0), 0) AS unitMinutes,
                   COALESCE((SELECT COUNT(*) FROM process_route_item ri
                             WHERE ri.route_id = wo.route_id AND ri.deleted_flag = 0), 0) AS stepCount
            FROM mfg_work_order wo
            LEFT JOIN mdm_product p ON p.product_id = wo.product_id AND p.deleted_flag = 0
            LEFT JOIN mfg_production_plan pl ON pl.plan_id = wo.plan_id AND pl.deleted_flag = 0
            WHERE wo.deleted_flag = 0 AND wo.parent_work_order_id = #{workOrderId}
            ORDER BY wo.work_order_id
            """)
    List<Map<String, Object>> selectComponentWorkOrders(@Param("workOrderId") Long workOrderId);

    /**
     * 查一张工单所用路线的单件标准工时（各工序标准工时之和）。
     *
     * <p>只算工序标准工时，不含排队、换型与等待；没有路线或没有工序时返回 0。</p>
     */
    @Select("""
            SELECT COALESCE(SUM(ri.standard_time), 0) AS unitMinutes
            FROM mfg_work_order wo
            LEFT JOIN process_route_item ri ON ri.route_id = wo.route_id AND ri.deleted_flag = 0
            WHERE wo.deleted_flag = 0 AND wo.work_order_id = #{workOrderId}
            GROUP BY wo.work_order_id
            """)
    BigDecimal selectWorkOrderUnitMinutes(@Param("workOrderId") Long workOrderId);
}
