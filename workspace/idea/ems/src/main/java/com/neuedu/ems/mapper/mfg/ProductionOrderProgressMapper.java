package com.neuedu.ems.mapper.mfg;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 订单全过程只读投影查询。
 *
 * <p>本 Mapper 只包含 SELECT，不写任何业务数据、异常台账或预留。聚合量全部按
 * {@code productionOrderId} 或已解析的主键集合查询，不做分页，避免前端分页截断导致
 * 后续单据漏算。</p>
 */
@Mapper
public interface ProductionOrderProgressMapper {

    /** 订单头：客户、产品、单位与交期。 */
    @Select("""
            SELECT po.production_order_id AS productionOrderId,
                   po.production_order_no AS productionOrderNo,
                   po.customer_id AS customerId,
                   COALESCE(c.customer_name, '') AS customerName,
                   po.product_id AS productId,
                   COALESCE(p.product_code, '') AS productCode,
                   COALESCE(p.product_name, '') AS productName,
                   COALESCE(u.unit_code, '') AS unitCode,
                   COALESCE(u.unit_name, '') AS unitName,
                   po.order_quantity AS orderQuantity,
                   po.delivery_date AS deliveryDate,
                   COALESCE(po.status, '') AS status,
                   COALESCE(po.remark, '') AS remark,
                   po.created_time AS createdTime
            FROM mfg_production_order po
            LEFT JOIN mdm_customer c ON c.customer_id = po.customer_id AND c.deleted_flag = 0
            LEFT JOIN mdm_product p ON p.product_id = po.product_id AND p.deleted_flag = 0
            LEFT JOIN mdm_material m ON m.material_id = p.material_id AND m.deleted_flag = 0
            LEFT JOIN mdm_unit u ON u.unit_id = m.unit_id AND u.deleted_flag = 0
            WHERE po.deleted_flag = 0 AND po.production_order_id = #{orderId}
            """)
    Map<String, Object> selectOrderHeader(@Param("orderId") Long orderId);

    /**
     * 订单下全部有效计划，含根计划与组件计划。
     * 撤销过的计划是 CLOSED，已经不在订单当前执行范围内，不能再参与当前状态聚合，
     * 否则撤回后页面仍会把它当成"已排产的计划"继续报警。
     */
    @Select("""
            SELECT pl.plan_id AS planId, pl.plan_no AS planNo,
                   COALESCE(pl.plan_type, 'ROOT') AS planType,
                   pl.parent_plan_id AS parentPlanId, pl.line_id AS lineId,
                   COALESCE(l.line_name, '') AS lineName,
                   pl.planned_quantity AS plannedQuantity,
                   pl.planned_start_time AS plannedStartTime,
                   pl.planned_end_time AS plannedEndTime,
                   COALESCE(pl.status, '') AS status
            FROM mfg_production_plan pl
            LEFT JOIN factory_line l ON l.line_id = pl.line_id AND l.deleted_flag = 0
            WHERE pl.deleted_flag = 0 AND pl.production_order_id = #{orderId}
              AND NOT (COALESCE(pl.status, '') = 'CLOSED')
            ORDER BY pl.plan_type DESC, pl.planned_start_time, pl.plan_id
            """)
    List<Map<String, Object>> selectPlans(@Param("orderId") Long orderId);

    /**
     * 订单下全部工单，含父子关系、产品/物料、产线与所属计划。
     * 同计划一样，撤回后置为 CLOSED 的工单不再参与当前状态聚合。
     */
    @Select("""
            SELECT wo.work_order_id AS workOrderId, wo.work_order_no AS workOrderNo,
                   COALESCE(wo.work_order_type, 'ROOT') AS workOrderType,
                   wo.parent_work_order_id AS parentWorkOrderId,
                   wo.root_work_order_id AS rootWorkOrderId,
                   wo.plan_id AS planId, pl.line_id AS lineId, wo.product_id AS productId,
                   wo.bom_id AS bomId, wo.route_id AS routeId,
                   wo.source_requirement_id AS sourceRequirementId,
                   p.material_id AS materialId,
                   COALESCE(p.product_code, '') AS productCode,
                   COALESCE(p.product_name, '') AS productName,
                   COALESCE(u.unit_code, '') AS unitCode,
                   COALESCE(u.unit_name, '') AS unitName,
                   COALESCE(wo.status, '') AS status,
                   wo.planned_quantity AS plannedQuantity,
                   wo.completed_quantity AS completedQuantity,
                   COALESCE(l.line_name, '') AS lineName,
                   pl.plan_no AS planNo,
                   COALESCE(pl.plan_type, 'ROOT') AS planType,
                   COALESCE(wo.planned_start_time, pl.planned_start_time) AS plannedStartTime,
                   COALESCE(wo.planned_end_time, pl.planned_end_time) AS plannedEndTime,
                   wo.created_time AS createdTime, wo.updated_time AS updatedTime
            FROM mfg_work_order wo
            JOIN mfg_production_plan pl ON pl.plan_id = wo.plan_id AND pl.deleted_flag = 0
            LEFT JOIN mdm_product p ON p.product_id = wo.product_id AND p.deleted_flag = 0
            LEFT JOIN mdm_material m ON m.material_id = p.material_id AND m.deleted_flag = 0
            LEFT JOIN mdm_unit u ON u.unit_id = m.unit_id AND u.deleted_flag = 0
            LEFT JOIN factory_line l ON l.line_id = pl.line_id AND l.deleted_flag = 0
            WHERE wo.deleted_flag = 0 AND pl.production_order_id = #{orderId}
              AND NOT (COALESCE(pl.status, '') = 'CLOSED')
              AND NOT (COALESCE(wo.status, '') = 'CLOSED')
            ORDER BY wo.work_order_type DESC, wo.work_order_id
            """)
    List<Map<String, Object>> selectWorkOrders(@Param("orderId") Long orderId);

    /** 指定工单的全部用料需求及物料单位。 */
    @Select("""
            <script>
            SELECT r.requirement_id AS requirementId, r.work_order_id AS workOrderId,
                   r.material_id AS materialId,
                   COALESCE(m.material_code, '') AS materialCode,
                   COALESCE(m.material_name, '') AS materialName,
                   COALESCE(m.material_type, '') AS materialType,
                   COALESCE(u.unit_code, '') AS unitCode,
                   COALESCE(u.unit_name, '') AS unitName,
                   r.required_quantity AS requiredQuantity,
                   r.issued_quantity AS issuedQuantity,
                   r.reserved_quantity AS reservedQuantity,
                   r.shortage_quantity AS shortageQuantity,
                   COALESCE(r.status, '') AS status,
                   COALESCE(r.supply_type, '') AS supplyType,
                   r.source_work_order_id AS sourceWorkOrderId
            FROM mfg_work_order_material r
            LEFT JOIN mdm_material m ON m.material_id = r.material_id AND m.deleted_flag = 0
            LEFT JOIN mdm_unit u ON u.unit_id = m.unit_id AND u.deleted_flag = 0
            WHERE r.deleted_flag = 0
              AND r.work_order_id IN
              <foreach collection="workOrderIds" item="wid" open="(" separator="," close=")">#{wid}</foreach>
            ORDER BY r.work_order_id, r.requirement_id
            </script>
            """)
    List<Map<String, Object>> selectRequirements(@Param("workOrderIds") List<Long> workOrderIds);

    /** 需求声明的供给来源；一张需求可能由多张补产工单供给。 */
    @Select("""
            <script>
            SELECT s.source_id AS sourceId, s.source_no AS sourceNo,
                   s.requirement_id AS requirementId, s.work_order_id AS workOrderId,
                   s.plan_id AS planId, s.material_id AS materialId,
                   COALESCE(s.supply_type, '') AS supplyType,
                   s.planned_quantity AS plannedQuantity,
                   s.sequence_no AS sequenceNo,
                   COALESCE(s.status, '') AS status
            FROM mfg_supply_source s
            WHERE s.deleted_flag = 0
              AND s.requirement_id IN
              <foreach collection="requirementIds" item="rid" open="(" separator="," close=")">#{rid}</foreach>
            ORDER BY s.requirement_id, s.sequence_no, s.source_id
            </script>
            """)
    List<Map<String, Object>> selectSupplySources(@Param("requirementIds") List<Long> requirementIds);

    /** 需求上的库存分配明细，用于区分已预留、已转线边、已消耗。 */
    @Select("""
            <script>
            SELECT a.allocation_id AS allocationId, a.allocation_no AS allocationNo,
                   a.requirement_id AS requirementId, a.work_order_id AS workOrderId,
                   a.material_id AS materialId, a.warehouse_id AS warehouseId,
                   a.location_id AS locationId, a.batch_id AS batchId,
                   a.inventory_id AS inventoryId,
                   a.allocated_quantity AS allocatedQuantity,
                   a.reserved_quantity AS reservedQuantity,
                   a.transferred_quantity AS transferredQuantity,
                   a.consumed_quantity AS consumedQuantity,
                   a.released_quantity AS releasedQuantity,
                   COALESCE(b.batch_no, '') AS batchNo,
                   COALESCE(a.status, '') AS status,
                   i.locked_quantity AS lockedQuantity,
                   CASE WHEN i.deleted_flag=0 AND i.work_order_id=0
                        AND i.material_id=a.material_id AND i.batch_id=a.batch_id
                        AND i.warehouse_id=a.warehouse_id AND i.location_id=a.location_id
                        AND wh.status='ENABLE' AND wh.deleted_flag=0
                        AND wh.warehouse_type IN ('RAW','SEMI','SEMI_FINISHED')
                        AND loc.deleted_flag=0 AND loc.warehouse_id=wh.warehouse_id
                        AND loc.location_type='NORMAL' AND loc.status IN ('IDLE','OCCUPIED','ENABLE')
                        AND b.quality_status IN ('PASS','CONCESSION') AND b.status IN ('AVAILABLE','ENABLE')
                        AND b.material_id=a.material_id THEN 1 ELSE 0 END AS eligibleReservation
            FROM mfg_supply_allocation a
            LEFT JOIN wms_material_batch b ON b.batch_id = a.batch_id AND b.deleted_flag = 0
            LEFT JOIN wms_inventory i ON i.inventory_id=a.inventory_id
            LEFT JOIN wms_warehouse wh ON wh.warehouse_id=a.warehouse_id
            LEFT JOIN wms_location loc ON loc.location_id=a.location_id
            WHERE a.deleted_flag = 0
              AND a.requirement_id IN
              <foreach collection="requirementIds" item="rid" open="(" separator="," close=")">#{rid}</foreach>
            ORDER BY a.requirement_id, a.allocation_id
            </script>
            """)
    List<Map<String, Object>> selectAllocations(@Param("requirementIds") List<Long> requirementIds);

    /** 派工单与承接班组，用于责任人。 */
    @Select("""
            <script>
            SELECT d.dispatch_id AS dispatchId, d.dispatch_no AS dispatchNo,
                   d.work_order_id AS workOrderId, d.team_id AS teamId,
                   COALESCE(t.team_name, '') AS teamName,
                   COALESCE(le.employee_name, '') AS leaderName,
                   d.planned_quantity AS plannedQuantity, d.due_time AS dueTime,
                   COALESCE(d.material_ready_status, '') AS materialReadyStatus,
                   COALESCE(d.status, '') AS status
            FROM mfg_dispatch_order d
            LEFT JOIN org_team t ON t.team_id = d.team_id AND t.deleted_flag = 0
            LEFT JOIN org_employee le ON le.employee_id = t.leader_id AND le.deleted_flag = 0
            WHERE d.deleted_flag = 0
              AND d.work_order_id IN
              <foreach collection="workOrderIds" item="wid" open="(" separator="," close=")">#{wid}</foreach>
            ORDER BY d.work_order_id, d.dispatch_id
            </script>
            """)
    List<Map<String, Object>> selectDispatches(@Param("workOrderIds") List<Long> workOrderIds);

    /** 工单下的生产任务与工位、工序。 */
    @Select("""
            <script>
            SELECT tk.task_id AS taskId, tk.task_no AS taskNo,
                   tk.work_order_id AS workOrderId, tk.dispatch_id AS dispatchId,
                   tk.operation_id AS operationId,
                   COALESCE(op.operation_code, '') AS operationCode,
                   COALESCE(op.operation_name, '') AS operationName,
                   tk.station_id AS stationId,
                   COALESCE(st.station_name, '') AS stationName,
                   tk.planned_quantity AS plannedQuantity,
                   tk.completed_quantity AS completedQuantity,
                   tk.defect_quantity AS defectQuantity,
                   COALESCE(tk.task_type, '') AS taskType,
                   tk.source_task_id AS sourceTaskId,
                   COALESCE(tk.status, '') AS status,
                   tk.created_time AS createdTime
            FROM mfg_task tk
            LEFT JOIN process_operation op ON op.operation_id = tk.operation_id
            LEFT JOIN factory_station st ON st.station_id = tk.station_id AND st.deleted_flag = 0
            WHERE tk.deleted_flag = 0
              AND tk.work_order_id IN
              <foreach collection="workOrderIds" item="wid" open="(" separator="," close=")">#{wid}</foreach>
            ORDER BY tk.work_order_id, tk.task_id
            </script>
            """)
    List<Map<String, Object>> selectTasks(@Param("workOrderIds") List<Long> workOrderIds);

    /** 任务报工记录，区分审核状态后分别汇总。 */
    @Select("""
            <script>
            SELECT wr.report_id AS reportId, wr.report_no AS reportNo,
                   wr.task_id AS taskId, wr.operator_id AS operatorId,
                   COALESCE(e.employee_name, '') AS operatorName,
                   wr.station_id AS stationId,
                   wr.qualified_quantity AS qualifiedQuantity,
                   wr.defect_quantity AS defectQuantity,
                   wr.actual_work_hours AS actualWorkHours,
                   wr.report_time AS reportTime,
                   COALESCE(wr.audit_status, '') AS auditStatus
            FROM mfg_work_report wr
            LEFT JOIN org_employee e ON e.employee_id = wr.operator_id AND e.deleted_flag = 0
            WHERE wr.deleted_flag = 0
              AND wr.task_id IN
              <foreach collection="taskIds" item="tid" open="(" separator="," close=")">#{tid}</foreach>
            ORDER BY wr.task_id, wr.report_id
            </script>
            """)
    List<Map<String, Object>> selectReports(@Param("taskIds") List<Long> taskIds);

    /** 工单产出的产品批次，是生产入库和终检的唯一入口。 */
    @Select("""
            <script>
            SELECT lot.product_lot_id AS productLotId, lot.lot_no AS lotNo,
                   lot.product_id AS productId, lot.work_order_id AS workOrderId,
                   lot.source_report_id AS sourceReportId,
                   lot.batch_quantity AS batchQuantity,
                   lot.qualified_quantity AS qualifiedQuantity,
                   lot.defect_quantity AS defectQuantity,
                   COALESCE(lot.status, '') AS status,
                   lot.created_time AS createdTime
            FROM trace_product_lot lot
            WHERE lot.deleted_flag = 0
              AND lot.work_order_id IN
              <foreach collection="workOrderIds" item="wid" open="(" separator="," close=")">#{wid}</foreach>
            ORDER BY lot.work_order_id, lot.product_lot_id
            </script>
            """)
    List<Map<String, Object>> selectProductLots(@Param("workOrderIds") List<Long> workOrderIds);

    /** 生产入库：来源类型固定 PRODUCT_LOT，来源单号是产品批次 ID。 */
    @Select("""
            <script>
            SELECT io.inbound_id AS inboundId, io.inbound_no AS inboundNo,
                   COALESCE(io.inbound_type, '') AS inboundType,
                   COALESCE(io.source_order_type, '') AS sourceOrderType,
                   io.source_order_id AS sourceOrderId,
                   COALESCE(io.audit_status, '') AS auditStatus,
                   io.inbound_time AS inboundTime,
                   COALESCE(w.warehouse_name, '') AS warehouseName,
                   iod.detail_id AS detailId, iod.material_id AS materialId,
                   COALESCE(m.material_name, '') AS materialName,
                   iod.inbound_quantity AS inboundQuantity, iod.batch_id AS batchId
            FROM wms_inbound_order io
            JOIN wms_inbound_detail iod ON iod.inbound_id = io.inbound_id AND iod.deleted_flag = 0
            LEFT JOIN wms_warehouse w ON w.warehouse_id = io.warehouse_id AND w.deleted_flag = 0
            LEFT JOIN mdm_material m ON m.material_id = iod.material_id AND m.deleted_flag = 0
            WHERE io.deleted_flag = 0
              AND io.source_order_type = 'PRODUCT_LOT'
              AND io.source_order_id IN
              <foreach collection="productLotIds" item="lid" open="(" separator="," close=")">#{lid}</foreach>
            ORDER BY io.inbound_time, io.inbound_id
            </script>
            """)
    List<Map<String, Object>> selectProductionInbound(@Param("productLotIds") List<Long> productLotIds);

    /** 发料出库单：按工单关联的领料记录，用于备料阶段证据。 */
    @Select("""
            <script>
            SELECT oo.outbound_id AS outboundId, oo.outbound_no AS outboundNo,
                   COALESCE(oo.outbound_type, '') AS outboundType,
                   oo.work_order_id AS workOrderId,
                   COALESCE(oo.audit_status, '') AS auditStatus,
                   oo.outbound_time AS outboundTime,
                   od.detail_id AS detailId, od.material_id AS materialId,
                   COALESCE(m.material_name, '') AS materialName,
                   od.outbound_quantity AS outboundQuantity, od.batch_id AS batchId
            FROM wms_outbound_order oo
            JOIN wms_outbound_detail od ON od.outbound_id = oo.outbound_id AND od.deleted_flag = 0
            LEFT JOIN mdm_material m ON m.material_id = od.material_id AND m.deleted_flag = 0
            WHERE oo.deleted_flag = 0 AND oo.outbound_type = 'MATERIAL_ISSUE'
              AND oo.work_order_id IN
              <foreach collection="workOrderIds" item="wid" open="(" separator="," close=")">#{wid}</foreach>
            ORDER BY oo.work_order_id, oo.outbound_time, oo.outbound_id
            </script>
            """)
    List<Map<String, Object>> selectMaterialIssues(@Param("workOrderIds") List<Long> workOrderIds);

    /** 发货：只认 SALE 且来源生产订单等于本订单的已确认单据。 */
    @Select("""
            SELECT oo.outbound_id AS outboundId, oo.outbound_no AS outboundNo,
                   COALESCE(oo.outbound_type, '') AS outboundType,
                   oo.customer_id AS customerId,
                   COALESCE(c.customer_name, '') AS customerName,
                   oo.work_order_id AS workOrderId, oo.inspection_id AS inspectionId,
                   COALESCE(oo.audit_status, '') AS auditStatus,
                   oo.outbound_time AS outboundTime,
                   od.detail_id AS detailId, od.material_id AS materialId,
                   COALESCE(m.material_name, '') AS materialName,
                   od.outbound_quantity AS outboundQuantity, od.batch_id AS batchId
            FROM wms_outbound_order oo
            JOIN wms_outbound_detail od ON od.outbound_id = oo.outbound_id AND od.deleted_flag = 0
            LEFT JOIN mdm_customer c ON c.customer_id = oo.customer_id AND c.deleted_flag = 0
            LEFT JOIN mdm_material m ON m.material_id = od.material_id AND m.deleted_flag = 0
            WHERE oo.deleted_flag = 0 AND oo.outbound_type = 'SALE'
              AND oo.source_production_order_id = #{orderId}
            ORDER BY oo.outbound_time, oo.outbound_id
            """)
    List<Map<String, Object>> selectShipments(@Param("orderId") Long orderId);

    /** 与订单工单或产品批次关联的检验单。 */
    @Select("""
            <script>
            SELECT q.inspection_id AS inspectionId, q.inspection_no AS inspectionNo,
                   COALESCE(q.inspection_type, '') AS inspectionType,
                   q.scheme_id AS schemeId, q.work_order_id AS workOrderId,
                   q.task_id AS taskId, q.report_id AS reportId,
                   q.batch_id AS batchId, q.product_lot_id AS productLotId,
                   q.inspector_id AS inspectorId,
                   COALESCE(e.employee_name, '') AS inspectorName,
                   q.sample_quantity AS sampleQuantity,
                   q.qualified_quantity AS qualifiedQuantity,
                   q.unqualified_quantity AS unqualifiedQuantity,
                   q.inspection_time AS inspectionTime, q.created_time AS createdTime,
                   COALESCE(q.inspection_result, '') AS inspectionResult,
                    COALESCE(q.status, '') AS status,
                    (SELECT COUNT(*) FROM wms_outbound_detail sd WHERE sd.inspection_id=q.inspection_id AND sd.deleted_flag=0) AS stockReleaseInspection
            FROM qms_inspection_order q
            LEFT JOIN org_employee e ON e.employee_id = q.inspector_id AND e.deleted_flag = 0
            WHERE q.deleted_flag = 0
              <choose>
                <when test="workOrderIds != null and workOrderIds.size() > 0 and productLotIds != null and productLotIds.size() > 0">
                  AND (q.work_order_id IN
                  <foreach collection="workOrderIds" item="wid" open="(" separator="," close=")">#{wid}</foreach>
                  OR q.product_lot_id IN
                  <foreach collection="productLotIds" item="lid" open="(" separator="," close=")">#{lid}</foreach>
                  OR EXISTS (SELECT 1 FROM wms_outbound_order sale JOIN mfg_work_order sw ON sw.work_order_id IN
                    <foreach collection="workOrderIds" item="wid" open="(" separator="," close=")">#{wid}</foreach>
                    JOIN mfg_production_plan sp ON sp.plan_id=sw.plan_id
                    WHERE sale.inspection_id=q.inspection_id AND sale.source_production_order_id=sp.production_order_id
                    AND sale.outbound_type='SALE' AND sale.deleted_flag=0 AND sp.deleted_flag=0 AND sw.deleted_flag=0)
                  <if test="orderId != null">
                  OR EXISTS (SELECT 1 FROM wms_outbound_detail sod
                    JOIN wms_outbound_order so ON so.outbound_id=sod.outbound_id
                    JOIN mfg_work_order swo ON swo.work_order_id=so.work_order_id
                    JOIN mfg_production_plan spo ON spo.plan_id=swo.plan_id
                    WHERE sod.inspection_id=q.inspection_id AND spo.production_order_id=#{orderId}
                      AND sod.deleted_flag=0 AND so.deleted_flag=0 AND swo.deleted_flag=0)
                  </if>
                  )
                </when>
                <when test="workOrderIds != null and workOrderIds.size() > 0">
                  AND (q.work_order_id IN
                  <foreach collection="workOrderIds" item="wid" open="(" separator="," close=")">#{wid}</foreach>
                  <if test="orderId != null">
                  OR EXISTS (SELECT 1 FROM wms_outbound_detail sod
                    JOIN wms_outbound_order so ON so.outbound_id=sod.outbound_id
                    JOIN mfg_work_order swo ON swo.work_order_id=so.work_order_id
                    JOIN mfg_production_plan spo ON spo.plan_id=swo.plan_id
                    WHERE sod.inspection_id=q.inspection_id AND spo.production_order_id=#{orderId}
                      AND sod.deleted_flag=0 AND so.deleted_flag=0 AND swo.deleted_flag=0)
                  </if>
                  )
                </when>
                <when test="productLotIds != null and productLotIds.size() > 0">
                  AND q.product_lot_id IN
                  <foreach collection="productLotIds" item="lid" open="(" separator="," close=")">#{lid}</foreach>
                </when>
                <otherwise>AND 1 = 0</otherwise>
              </choose>
            ORDER BY q.inspection_id
            </script>
            """)
    List<Map<String, Object>> selectInspections(@Param("workOrderIds") List<Long> workOrderIds,
                                                @Param("productLotIds") List<Long> productLotIds,
                                                @Param("orderId") Long orderId);

    /** 状态变更日志：实际起止时间的首选证据。 */
    @Select("""
            <script>
            SELECT l.log_id AS logId, l.biz_type AS bizType, l.biz_id AS bizId,
                   l.old_status AS oldStatus, l.new_status AS newStatus,
                   l.change_reason AS changeReason, l.changed_by AS changedBy,
                   l.changed_time AS changedTime
            FROM sys_status_change_log l
            WHERE l.deleted_flag = 0 AND l.biz_type = #{bizType}
              AND l.biz_id IN
              <foreach collection="bizIds" item="bid" open="(" separator="," close=")">#{bid}</foreach>
            ORDER BY l.biz_id, l.changed_time, l.log_id
            </script>
            """)
    List<Map<String, Object>> selectStatusLogs(@Param("bizType") String bizType,
                                               @Param("bizIds") List<Long> bizIds);

    /** 工位上的设备及未关闭报修，用于设备故障判断与替代资源核查。 */
    @Select("""
            <script>
            SELECT e.equipment_id AS equipmentId, e.equipment_code AS equipmentCode,
                   e.equipment_name AS equipmentName,
                   COALESCE(e.status, '') AS equipmentStatus,
                   e.station_id AS stationId, e.category_id AS categoryId,
                   r.repair_id AS repairId, r.repair_no AS repairNo,
                   COALESCE(r.fault_description, '') AS faultDescription,
                   COALESCE(r.status, '') AS repairStatus,
                   r.reported_time AS reportedTime
            FROM eam_equipment e
            LEFT JOIN eam_repair_order r ON r.equipment_id = e.equipment_id
                 AND r.deleted_flag = 0 AND COALESCE(r.status, '') &lt;&gt; 'CLOSED'
            WHERE e.deleted_flag = 0
              AND e.station_id IN
              <foreach collection="stationIds" item="sid" open="(" separator="," close=")">#{sid}</foreach>
            ORDER BY e.station_id, e.equipment_code, r.reported_time
            </script>
            """)
    List<Map<String, Object>> selectEquipmentFaults(@Param("stationIds") List<Long> stationIds);

    /**
     * 物料待检量：只读库存余额上的检验中数量，用于说明「有货但还不能用」，
     * 不计入可用量、不参与齐套判定。
     */
    @Select("""
            <script>
            SELECT i.material_id AS materialId,
                   COALESCE(SUM(i.inspection_quantity), 0) AS inspectionQuantity
            FROM wms_inventory i
            WHERE i.deleted_flag = 0 AND i.inspection_quantity &gt; 0
              AND i.material_id IN
              <foreach collection="materialIds" item="mid" open="(" separator="," close=")">#{mid}</foreach>
            GROUP BY i.material_id
            </script>
            """)
    List<Map<String, Object>> selectInspectionStock(@Param("materialIds") List<Long> materialIds);

    /** Confirmed returns only: pending or withdrawn returns have not changed physical coverage. */
    @Select("""
            <script>
            SELECT ro.return_id AS returnId, ro.return_no AS returnNo, ro.work_order_id AS workOrderId,
                   ro.return_type AS returnType, ro.audit_status AS auditStatus, ro.return_time AS returnTime,
                   rd.material_id AS materialId, rd.return_quantity AS returnQuantity,
                   rd.batch_id AS batchId
            FROM wms_return_order ro JOIN wms_return_detail rd ON rd.return_id=ro.return_id AND rd.deleted_flag=0
            WHERE ro.deleted_flag=0 AND ro.audit_status='APPROVED' AND ro.work_order_id IN
            <foreach collection="workOrderIds" item="wid" open="(" separator="," close=")">#{wid}</foreach>
            ORDER BY ro.return_id,rd.detail_id
            </script>
            """)
    List<Map<String,Object>> selectConfirmedReturns(@Param("workOrderIds") List<Long> workOrderIds);

    /** A candidate is a resource hint, never an automatic rescheduling commitment. */
    @Select("""
            <script>
            SELECT so.operation_id AS operationId, st.station_id AS stationId,
                   st.line_id AS lineId, st.station_name AS stationName,
                   e.category_id AS categoryId, e.equipment_id AS equipmentId,
                   CASE WHEN EXISTS (SELECT 1 FROM mfg_task busy WHERE busy.station_id=st.station_id
                        AND busy.deleted_flag=0 AND busy.status IN ('RUNNING','PAUSED')) THEN 1 ELSE 0 END AS busy
            FROM factory_station_operation so
            JOIN factory_station st ON st.station_id=so.station_id AND st.deleted_flag=0
                 AND st.status IN ('RUNNING','ENABLE','IDLE')
            JOIN eam_equipment e ON e.station_id=st.station_id AND e.deleted_flag=0
                 AND e.status IN ('IDLE','ENABLE','REPAIRED')
            WHERE so.deleted_flag=0 AND so.status='ENABLE' AND so.operation_id IN
            <foreach collection="operationIds" item="op" open="(" separator="," close=")">#{op}</foreach>
            AND NOT EXISTS (SELECT 1 FROM eam_repair_order repair WHERE repair.equipment_id=e.equipment_id
                 AND repair.deleted_flag=0 AND COALESCE(repair.status,'') NOT IN ('CLOSED','FINISHED'))
            </script>
            """)
    List<Map<String,Object>> selectAlternativeResources(@Param("operationIds") List<Long> operationIds);

    @Select("""
            <script>
            SELECT n.ncr_id AS ncrId,n.ncr_no AS ncrNo,n.inspection_id AS inspectionId,
                   n.product_lot_id AS productLotId,n.disposition_type AS dispositionType,
                   n.status AS status,n.approval_status AS approvalStatus,n.created_time AS createdTime,
                   COALESCE((SELECT SUM(d.defect_quantity) FROM qms_nonconforming_detail d
                        WHERE d.ncr_id=n.ncr_id AND d.deleted_flag=0),0) AS defectQuantity
            FROM qms_nonconforming_order n WHERE n.deleted_flag=0 AND n.product_lot_id IN
            <foreach collection="productLotIds" item="lid" open="(" separator="," close=")">#{lid}</foreach>
            ORDER BY n.ncr_id
            </script>
            """)
    List<Map<String,Object>> selectDispositions(@Param("productLotIds") List<Long> productLotIds);

    /** Follow actually issued/reserved batches to their receipts and incoming inspections. */
    @Select("""
            <script>
            WITH related_batch AS (
                SELECT DISTINCT d.batch_id FROM wms_outbound_detail d
                JOIN wms_outbound_order o ON o.outbound_id=d.outbound_id AND o.deleted_flag=0
                WHERE d.deleted_flag=0 AND o.outbound_type='MATERIAL_ISSUE' AND o.work_order_id IN
                <foreach collection="workOrderIds" item="wid" open="(" separator="," close=")">#{wid}</foreach>
                UNION SELECT a.batch_id FROM mfg_supply_allocation a WHERE a.deleted_flag=0 AND a.work_order_id IN
                <foreach collection="workOrderIds" item="wid" open="(" separator="," close=")">#{wid}</foreach>
            )
            SELECT 'MATERIAL_BATCH' AS docType,b.batch_id AS docId,b.batch_no AS docNo,b.quality_status AS status,
                   NULL AS quantity,'' AS unit,b.created_time AS eventTime,'wms-material-batch' AS targetResource
            FROM wms_material_batch b JOIN related_batch rb ON rb.batch_id=b.batch_id WHERE b.deleted_flag=0
            UNION ALL
            SELECT CASE WHEN o.inbound_type='PURCHASE' THEN 'PURCHASE_INBOUND' WHEN o.inbound_type='PRODUCTION' THEN 'PRODUCTION_INBOUND' ELSE 'OTHER_INBOUND' END,o.inbound_id,o.inbound_no,o.audit_status,NULL,'',o.inbound_time,'wms-inbound-order'
            FROM wms_inbound_order o WHERE o.deleted_flag=0 AND EXISTS (
                SELECT 1 FROM wms_inbound_detail d JOIN related_batch rb ON rb.batch_id=d.batch_id
                WHERE d.inbound_id=o.inbound_id AND d.deleted_flag=0)
            UNION ALL
            SELECT 'INSPECTION',q.inspection_id,q.inspection_no,q.status,q.sample_quantity,'',q.inspection_time,'qms-inspection-order'
            FROM qms_inspection_order q JOIN related_batch rb ON rb.batch_id=q.batch_id
            WHERE q.deleted_flag=0 AND q.inspection_type='INCOMING'
            </script>
            """)
    List<Map<String,Object>> selectBatchSourceDocuments(@Param("workOrderIds") List<Long> workOrderIds);

}
