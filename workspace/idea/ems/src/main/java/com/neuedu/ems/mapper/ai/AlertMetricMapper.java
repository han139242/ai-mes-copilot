package com.neuedu.ems.mapper.ai;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 预警指标取数 Mapper。
 *
 * <p>每个方法只负责把一个指标在当前时点的真实值查出来，返回统一形状：
 * {@code bizId / bizNo / subjectName / metricValue / extraText}。是否超过限度、
 * 超多少、算 WARNING 还是 CRITICAL 一律交给 Java 侧按规则配置判断，
 * 这样使用者改限度时不需要动 SQL，也不会出现"阈值写死在语句里"的老问题。</p>
 *
 * <p>所有查询都过滤 deleted_flag = 0，并且只读，不会改动业务数据。</p>
 */
@Mapper
public interface AlertMetricMapper {

    /** 原料/半成品/成品的公共可用库存（work_order_id = 0，即未被线边占用）。 */
    @Select("""
            SELECT m.material_id AS bizId, m.material_code AS bizNo, m.material_name AS subjectName,
                   m.material_type AS extraText, w.warehouse_type AS warehouseType,
                   COALESCE(SUM(i.available_quantity), 0) AS metricValue
            FROM mdm_material m
            LEFT JOIN wms_inventory i ON i.material_id = m.material_id AND i.deleted_flag = 0 AND i.work_order_id = 0
            LEFT JOIN wms_warehouse w ON w.warehouse_id = i.warehouse_id AND w.deleted_flag = 0
            WHERE m.deleted_flag = 0 AND m.status = 'ENABLE'
              AND (#{warehouseType} IS NULL OR w.warehouse_type = #{warehouseType})
            GROUP BY m.material_id, m.material_code, m.material_name, m.material_type, w.warehouse_type
            ORDER BY m.material_id
            """)
    List<Map<String, Object>> materialAvailable(@Param("warehouseType") String warehouseType);

    /** 批次呆滞：已放行可用、但入库超过 N 天仍有可用余量的批次。 */
    @Select("""
            SELECT b.batch_id AS bizId, b.batch_no AS bizNo, m.material_name AS subjectName,
                   CONCAT('批次 ', b.batch_no, ' / ', m.material_code) AS extraText,
                   w.warehouse_type AS warehouseType,
                   DATEDIFF(NOW(), b.received_date) AS metricValue
            FROM wms_material_batch b
            JOIN mdm_material m ON m.material_id = b.material_id AND m.deleted_flag = 0
            LEFT JOIN wms_inventory i ON i.batch_id = b.batch_id AND i.deleted_flag = 0
            LEFT JOIN wms_warehouse w ON w.warehouse_id = i.warehouse_id AND w.deleted_flag = 0
            WHERE b.deleted_flag = 0 AND b.received_date IS NOT NULL
              AND b.quality_status IN ('PASS', 'CONCESSION')
              AND b.status IN ('AVAILABLE', 'ENABLE')
              AND COALESCE(i.available_quantity, 0) > 0
              AND (#{warehouseType} IS NULL OR w.warehouse_type = #{warehouseType})
            ORDER BY b.batch_id
            """)
    List<Map<String, Object>> batchAging(@Param("warehouseType") String warehouseType);

    /** 待检批次积压：入库后仍未放行的批次，按入库至今小时数计。 */
    @Select("""
            SELECT b.batch_id AS bizId, b.batch_no AS bizNo, m.material_name AS subjectName,
                   CONCAT('批次 ', b.batch_no, ' 质量状态 ', COALESCE(b.quality_status, '未检')) AS extraText,
                   TIMESTAMPDIFF(HOUR, b.received_date, NOW()) AS metricValue
            FROM wms_material_batch b
            JOIN mdm_material m ON m.material_id = b.material_id AND m.deleted_flag = 0
            WHERE b.deleted_flag = 0 AND b.received_date IS NOT NULL
              AND (b.quality_status IS NULL OR b.quality_status NOT IN ('PASS', 'CONCESSION'))
            ORDER BY b.batch_id
            """)
    List<Map<String, Object>> batchInspectionPending();

    /** 工单已下达但长时间未开工，按下达至今小时数计。 */
    @Select("""
            SELECT o.work_order_id AS bizId, o.work_order_no AS bizNo, p.product_name AS subjectName,
                   CONCAT('计划 ', o.planned_quantity, ' 件，状态 ', COALESCE(o.status, '')) AS extraText,
                   TIMESTAMPDIFF(HOUR, o.created_time, NOW()) AS metricValue
            FROM mfg_work_order o
            LEFT JOIN mdm_product p ON p.product_id = o.product_id AND p.deleted_flag = 0
            WHERE o.deleted_flag = 0 AND o.status = 'RELEASED' AND o.created_time IS NOT NULL
            ORDER BY o.work_order_id
            """)
    List<Map<String, Object>> workOrderIdle();

    /** 工单超计划完工时间仍未完成，按超出小时数计。 */
    @Select("""
            SELECT o.work_order_id AS bizId, o.work_order_no AS bizNo, p.product_name AS subjectName,
                   CONCAT('计划完工 ', DATE_FORMAT(pl.planned_end_time, '%Y-%m-%d %H:%i'),
                          '，已完成 ', o.completed_quantity, '/', o.planned_quantity) AS extraText,
                   TIMESTAMPDIFF(HOUR, pl.planned_end_time, NOW()) AS metricValue
            FROM mfg_work_order o
            JOIN mfg_production_plan pl ON pl.plan_id = o.plan_id AND pl.deleted_flag = 0
            LEFT JOIN mdm_product p ON p.product_id = o.product_id AND p.deleted_flag = 0
            WHERE o.deleted_flag = 0 AND o.status IN ('RELEASED', 'RUNNING', 'PAUSED')
              AND pl.planned_end_time IS NOT NULL AND pl.planned_end_time < NOW()
            ORDER BY o.work_order_id
            """)
    List<Map<String, Object>> workOrderOverdue();

    /** 工单缺料：需求行里仍有未覆盖缺口，按缺口物料种数计。 */
    @Select("""
            SELECT o.work_order_id AS bizId, o.work_order_no AS bizNo, p.product_name AS subjectName,
                   GROUP_CONCAT(DISTINCT m.material_code ORDER BY m.material_code SEPARATOR ', ') AS extraText,
                   COUNT(DISTINCT r.material_id) AS metricValue
            FROM mfg_work_order o
            JOIN mfg_work_order_material r ON r.work_order_id = o.work_order_id AND r.deleted_flag = 0
            LEFT JOIN mdm_material m ON m.material_id = r.material_id AND m.deleted_flag = 0
            LEFT JOIN mdm_product p ON p.product_id = o.product_id AND p.deleted_flag = 0
            WHERE o.deleted_flag = 0 AND o.status IN ('DRAFT', 'RELEASED', 'RUNNING')
              AND r.shortage_quantity > 0
            GROUP BY o.work_order_id, o.work_order_no, p.product_name
            ORDER BY o.work_order_id
            """)
    List<Map<String, Object>> workOrderShortage();

    /** 任务执行中长时间没有更新，按最后更新时间至今小时数计。 */
    @Select("""
            SELECT t.task_id AS bizId, t.task_no AS bizNo, o.work_order_no AS subjectName,
                   CONCAT('完成 ', t.completed_quantity, '/', t.planned_quantity) AS extraText,
                   TIMESTAMPDIFF(HOUR, COALESCE(t.updated_time, t.created_time), NOW()) AS metricValue
            FROM mfg_task t
            LEFT JOIN mfg_work_order o ON o.work_order_id = t.work_order_id AND o.deleted_flag = 0
            WHERE t.deleted_flag = 0 AND t.status = 'RUNNING'
            ORDER BY t.task_id
            """)
    List<Map<String, Object>> taskStalled();

    /** 维修任务提交后长时间无人处理，按提交至今小时数计。 */
    @Select("""
            SELECT r.repair_id AS bizId, r.repair_no AS bizNo, e.equipment_name AS subjectName,
                   CONCAT('故障：', COALESCE(r.fault_description, '未填写')) AS extraText,
                   TIMESTAMPDIFF(HOUR, COALESCE(r.reported_time, r.created_time), NOW()) AS metricValue
            FROM eam_repair_order r
            LEFT JOIN eam_equipment e ON e.equipment_id = r.equipment_id AND e.deleted_flag = 0
            WHERE r.deleted_flag = 0 AND r.status = 'SUBMITTED'
            ORDER BY r.repair_id
            """)
    List<Map<String, Object>> repairPending();

    /** 维修完成待验收超时，按进入待验收状态至今小时数计。 */
    @Select("""
            SELECT r.repair_id AS bizId, r.repair_no AS bizNo, e.equipment_name AS subjectName,
                   CONCAT('维修结果：', COALESCE(r.repair_result, '未填写')) AS extraText,
                   TIMESTAMPDIFF(HOUR, COALESCE(r.updated_time, r.created_time), NOW()) AS metricValue
            FROM eam_repair_order r
            LEFT JOIN eam_equipment e ON e.equipment_id = r.equipment_id AND e.deleted_flag = 0
            WHERE r.deleted_flag = 0 AND r.status = 'WAIT_ACCEPTANCE'
            ORDER BY r.repair_id
            """)
    List<Map<String, Object>> repairWaitAcceptance();

    /** 保养计划超期：启用中的计划，最近一次保养的下次保养时间已过，或从未保养且已超过周期天数。 */
    @Select("""
            SELECT p.plan_id AS bizId, CONCAT('保养计划 ', p.plan_id) AS bizNo, e.equipment_name AS subjectName,
                   CONCAT('周期 ', p.cycle_value, ' ', COALESCE(p.cycle_type, '')) AS extraText,
                   DATEDIFF(NOW(), COALESCE(
                       (SELECT MAX(r.next_maintenance_time) FROM eam_maintenance_record r
                        WHERE r.plan_id = p.plan_id AND r.deleted_flag = 0),
                       DATE_ADD(p.created_time, INTERVAL p.cycle_value DAY))) AS metricValue
            FROM eam_maintenance_plan p
            LEFT JOIN eam_equipment e ON e.equipment_id = p.equipment_id AND e.deleted_flag = 0
            WHERE p.deleted_flag = 0 AND p.status = 'ENABLE' AND p.created_time IS NOT NULL
              AND (p.cycle_type IS NULL OR p.cycle_type IN ('DAY', 'DAYS', '按天'))
            ORDER BY p.plan_id
            """)
    List<Map<String, Object>> maintenanceOverdue();

    /** 点检超期：启用中的点检计划，最近一次点检距今天数。 */
    @Select("""
            SELECT p.plan_id AS bizId, CONCAT('点检计划 ', p.plan_id) AS bizNo, e.equipment_name AS subjectName,
                   CONCAT('周期：', COALESCE(p.check_cycle, '未填写')) AS extraText,
                   DATEDIFF(NOW(), COALESCE(
                       (SELECT MAX(r.check_time) FROM eam_spot_check_record r
                        WHERE r.plan_id = p.plan_id AND r.deleted_flag = 0),
                       p.created_time)) AS metricValue
            FROM eam_spot_check_plan p
            LEFT JOIN eam_equipment e ON e.equipment_id = p.equipment_id AND e.deleted_flag = 0
            WHERE p.deleted_flag = 0 AND p.status = 'ENABLE' AND p.created_time IS NOT NULL
            ORDER BY p.plan_id
            """)
    List<Map<String, Object>> spotCheckOverdue();

    /** 设备处于故障/维修状态持续时长（小时）。 */
    @Select("""
            SELECT e.equipment_id AS bizId, e.equipment_code AS bizNo, e.equipment_name AS subjectName,
                   CONCAT('当前状态 ', COALESCE(e.status, '')) AS extraText,
                   TIMESTAMPDIFF(HOUR, COALESCE(
                       (SELECT MAX(l.collected_time) FROM eam_equipment_status_log l
                        WHERE l.equipment_id = e.equipment_id AND l.deleted_flag = 0
                          AND l.running_status IN ('FAULT', 'REPAIRING')),
                       e.updated_time, e.created_time), NOW()) AS metricValue
            FROM eam_equipment e
            WHERE e.deleted_flag = 0 AND e.status IN ('FAULT', 'REPAIRING')
            ORDER BY e.equipment_id
            """)
    List<Map<String, Object>> equipmentFault();

    /** 检验单建单后长时间未提交，按建单至今小时数计。 */
    @Select("""
            SELECT q.inspection_id AS bizId, q.inspection_no AS bizNo, q.inspection_type AS subjectName,
                   CONCAT('类型 ', COALESCE(q.inspection_type, ''), '，抽样 ', q.sample_quantity) AS extraText,
                   TIMESTAMPDIFF(HOUR, q.created_time, NOW()) AS metricValue
            FROM qms_inspection_order q
            WHERE q.deleted_flag = 0 AND q.status = 'DRAFT' AND q.created_time IS NOT NULL
            ORDER BY q.inspection_id
            """)
    List<Map<String, Object>> inspectionOverdue();

    /** 不合格品单未处置时长（小时）。 */
    @Select("""
            SELECT n.ncr_id AS bizId, n.ncr_no AS bizNo, n.defect_reason AS subjectName,
                   CONCAT('处置方式 ', COALESCE(n.disposition_type, '未定'), '，审批 ',
                          COALESCE(n.approval_status, '未提交')) AS extraText,
                   TIMESTAMPDIFF(HOUR, n.created_time, NOW()) AS metricValue
            FROM qms_nonconforming_order n
            WHERE n.deleted_flag = 0 AND n.status NOT IN ('CLOSED', 'FINISHED')
            ORDER BY n.ncr_id
            """)
    List<Map<String, Object>> nonconformingOpen();

    /** 生产计划已过计划完工时间仍为已下达状态，按超出小时数计。 */
    @Select("""
            SELECT pl.plan_id AS bizId, pl.plan_no AS bizNo, po.production_order_no AS subjectName,
                   CONCAT('计划完工 ', DATE_FORMAT(pl.planned_end_time, '%Y-%m-%d %H:%i'),
                          '，数量 ', pl.planned_quantity) AS extraText,
                   TIMESTAMPDIFF(HOUR, pl.planned_end_time, NOW()) AS metricValue
            FROM mfg_production_plan pl
            LEFT JOIN mfg_production_order po ON po.production_order_id = pl.production_order_id
                 AND po.deleted_flag = 0
            WHERE pl.deleted_flag = 0 AND pl.status = 'RELEASED'
              AND pl.planned_end_time IS NOT NULL AND pl.planned_end_time < NOW()
            ORDER BY pl.plan_id
            """)
    List<Map<String, Object>> planDelayed();

    /** 安灯告警触发后长时间无人响应，按触发至今小时数计。 */
    @Select("""
            SELECT a.alert_id AS bizId, a.alert_no AS bizNo, a.alert_type AS subjectName,
                   CONCAT('类型 ', COALESCE(a.alert_type, '')) AS extraText,
                   TIMESTAMPDIFF(HOUR, COALESCE(a.trigger_time, a.created_time), NOW()) AS metricValue
            FROM mfg_andon_alert a
            WHERE a.deleted_flag = 0 AND a.status = 'ACTIVE'
            ORDER BY a.alert_id
            """)
    List<Map<String, Object>> andonUnresponded();

    /** 补货建议长期未处理，按创建至今小时数计。 */
    @Select("""
            SELECT s.suggestion_id AS bizId, s.suggestion_no AS bizNo, m.material_name AS subjectName,
                   CONCAT('建议补货 ', s.suggested_quantity, '，可用 ', s.available_quantity) AS extraText,
                   TIMESTAMPDIFF(HOUR, s.created_time, NOW()) AS metricValue
            FROM wms_replenishment_suggestion s
            LEFT JOIN mdm_material m ON m.material_id = s.material_id AND m.deleted_flag = 0
            WHERE s.deleted_flag = 0 AND s.status NOT IN ('CLOSED', 'FINISHED', 'HANDLED')
            ORDER BY s.suggestion_id
            """)
    List<Map<String, Object>> replenishmentPending();

    /** 排程冲突项数量（按场景汇总）。 */
    @Select("""
            SELECT i.scenario_id AS bizId, CONCAT('排程场景 ', i.scenario_id) AS bizNo,
                   s.scenario_name AS subjectName,
                   GROUP_CONCAT(DISTINCT i.conflict_reason ORDER BY i.conflict_reason SEPARATOR '；') AS extraText,
                   COUNT(*) AS metricValue
            FROM mfg_schedule_item i
            LEFT JOIN mfg_schedule_scenario s ON s.scenario_id = i.scenario_id AND s.deleted_flag = 0
            WHERE i.deleted_flag = 0 AND i.status = 'CONFLICT'
            GROUP BY i.scenario_id, s.scenario_name
            ORDER BY i.scenario_id
            """)
    List<Map<String, Object>> scheduleConflict();

    /** 产线未来 7 天负荷率（百分比）：已下达计划量 ÷（日产能 × 7 天）。 */
    @Select("""
            SELECT l.line_id AS bizId, l.line_code AS bizNo, l.line_name AS subjectName,
                   CONCAT('日产能 ', l.daily_capacity, '，7 天计划量 ', COALESCE(SUM(pl.planned_quantity), 0)) AS extraText,
                   CASE WHEN l.daily_capacity > 0
                        THEN ROUND(COALESCE(SUM(pl.planned_quantity), 0) / (l.daily_capacity * 7) * 100, 2)
                        ELSE 0 END AS metricValue
            FROM factory_line l
            LEFT JOIN mfg_production_plan pl ON pl.line_id = l.line_id AND pl.deleted_flag = 0
                 AND pl.status IN ('RELEASED', 'RUNNING')
                 AND pl.planned_start_time >= #{horizonStart} AND pl.planned_start_time < #{horizonEnd}
            WHERE l.deleted_flag = 0 AND l.status IN ('ENABLE', 'RUNNING')
            GROUP BY l.line_id, l.line_code, l.line_name, l.daily_capacity
            ORDER BY l.line_id
            """)
    List<Map<String, Object>> lineLoadRatio(@Param("horizonStart") LocalDateTime horizonStart,
                                            @Param("horizonEnd") LocalDateTime horizonEnd);
}
