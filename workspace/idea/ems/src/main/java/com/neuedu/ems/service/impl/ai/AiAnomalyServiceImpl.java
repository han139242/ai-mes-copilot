package com.neuedu.ems.service.impl.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.entity.ai.AiAnomalyScan;
import com.neuedu.ems.entity.eam.EamEquipment;
import com.neuedu.ems.entity.mfg.MfgException;
import com.neuedu.ems.entity.mfg.MfgWorkOrder;
import com.neuedu.ems.entity.qms.QmsInspectionOrder;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.ai.AiAnomalyScanMapper;
import com.neuedu.ems.mapper.eam.EamEquipmentMapper;
import com.neuedu.ems.mapper.mfg.MfgExceptionMapper;
import com.neuedu.ems.mapper.mfg.MfgWorkOrderMapper;
import com.neuedu.ems.mapper.qms.QmsInspectionOrderMapper;
import com.neuedu.ems.service.ai.AiAnomalyService;
import com.neuedu.ems.service.impl.sys.ResponsibilityNotificationSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 主动异常闭环实现。
 *
 * <p>三类信号共用同一套处理流程：发现异常 → 按异常编号幂等登记到 mfg_exception 台账 →
 * 通知责任部门 → 条件恢复后自动验证关闭。每轮扫描写一条 ai_anomaly_scan 记录，
 * 便于监控页追溯"什么时候扫的、扫出几条、关掉几条"。</p>
 */
@Service
public class AiAnomalyServiceImpl implements AiAnomalyService {

    private static final Logger log = LoggerFactory.getLogger(AiAnomalyServiceImpl.class);

    /** 检验单超过这么多小时还没提交，视为超期。 */
    private static final long INSPECTION_OVERDUE_HOURS = 8L;

    /** 工单下达后超过这么多小时仍未开工，视为停滞。 */
    private static final long WORK_ORDER_IDLE_HOURS = 12L;

    /** 设备处于故障/维修状态超过这么多小时，视为待处置。 */
    private static final long EQUIPMENT_FAULT_HOURS = 2L;

    private static final String SEVERITY_WARNING = "WARNING";
    private static final String SEVERITY_INFO = "INFO";

    // 部门编码必须与 org_department.department_code 一致；早期写的 LAMP-DEPT-WMS / LAMP-DEPT-QMS
    // 在库里并不存在，departmentManager 查不到人返回 null，通知事件的 recipients 会是空数组，
    // 被 NotificationOutboxDeliveryService 的载荷校验判为 INVALID_NOTIFICATION_EVENT_PAYLOAD。
    private static final String WAREHOUSE_DEPARTMENT = "DEPT-WMS";
    private static final String QUALITY_DEPARTMENT = "DEPT-QMS";

    private static final String INSPECTION_ROUTE = "/qms/inspection-orders";
    private static final String WORK_ORDER_ROUTE = "/mfg/work-orders";
    /**
     * 设备页在路由表和 NotificationRoutePolicy 白名单里都是单数 {@code /eam/equipment}。
     * 早期这里写成复数 {@code /eam/equipments}，载荷校验 hasValidHeader 查白名单不过，
     * 通知会被判成 INVALID_NOTIFICATION_EVENT_PAYLOAD 并重试到上限，用户永远收不到设备故障提醒。
     */
    private static final String EQUIPMENT_ROUTE = "/eam/equipment";

    private static final DateTimeFormatter WINDOW_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private final AiAnomalyScanMapper scanMapper;
    private final MfgExceptionMapper exceptionMapper;
    private final QmsInspectionOrderMapper inspectionOrderMapper;
    private final MfgWorkOrderMapper workOrderMapper;
    private final EamEquipmentMapper equipmentMapper;
    private final ResponsibilityNotificationSupport notificationSupport;

    @Autowired
    public AiAnomalyServiceImpl(AiAnomalyScanMapper scanMapper,
                                MfgExceptionMapper exceptionMapper,
                                QmsInspectionOrderMapper inspectionOrderMapper,
                                MfgWorkOrderMapper workOrderMapper,
                                EamEquipmentMapper equipmentMapper,
                                ResponsibilityNotificationSupport notificationSupport) {
        this.scanMapper = scanMapper;
        this.exceptionMapper = exceptionMapper;
        this.inspectionOrderMapper = inspectionOrderMapper;
        this.workOrderMapper = workOrderMapper;
        this.equipmentMapper = equipmentMapper;
        this.notificationSupport = notificationSupport;
    }

    @Override
    public Map<String, Object> overview() {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("openCount", countExceptions(STATUS_SUBMITTED));
        data.put("closedCount", countExceptions(STATUS_CLOSED));
        data.put("inspectionOverdue", countExceptions(STATUS_SUBMITTED, TYPE_INSPECTION_OVERDUE));
        data.put("workOrderIdle", countExceptions(STATUS_SUBMITTED, TYPE_WORK_ORDER_IDLE));
        data.put("equipmentFault", countExceptions(STATUS_SUBMITTED, TYPE_EQUIPMENT_FAULT));
        data.put("lastScan", lastScan());
        data.put("scannedAt", now);
        data.put("thresholds", thresholds());
        return data;
    }

    @Override
    public Map<String, Object> scan(Long operatorId) {
        return execute("MANUAL", "MANUAL:" + System.currentTimeMillis(), operatorId);
    }

    @Override
    public int scanScheduled() {
        LocalDateTime now = LocalDateTime.now();
        // 定时扫描按分钟窗口做幂等键：同一分钟重复触发只登记一次。
        String window = now.format(WINDOW_FORMAT);
        Map<String, Object> result = execute("SCHEDULE", "SCHEDULE:" + window, null);
        Object opened = result.get("openedCount");
        return opened instanceof Number number ? number.intValue() : 0;
    }

    private Map<String, Object> execute(String triggerType, String runKey, Long operatorId) {
        AiAnomalyScan duplicated = scanMapper.selectOne(new LambdaQueryWrapper<AiAnomalyScan>()
                .eq(AiAnomalyScan::getScanNo, runKey)
                .eq(AiAnomalyScan::getDeletedFlag, 0)
                .last("LIMIT 1"));
        if (duplicated != null) {
            return idempotentResult(duplicated);
        }

        LocalDateTime startedAt = LocalDateTime.now();
        long beginMillis = System.currentTimeMillis();
        AiAnomalyScan scan = newScan(triggerType, runKey, operatorId, startedAt);
        scanMapper.insert(scan);

        List<Map<String, Object>> detected = new ArrayList<>();
        try {
            detected.addAll(detectOverdueInspections(startedAt));
            detected.addAll(detectIdleWorkOrders(startedAt));
            detected.addAll(detectFaultEquipments(startedAt));

            ScanCounters counters = registerExceptions(detected, operatorId, startedAt);
            int closed = closeRecovered(startedAt);
            return finishScan(scan, detected, counters, closed, beginMillis);
        } catch (RuntimeException ex) {
            markFailed(scan, beginMillis, ex);
            throw ex;
        }
    }

    /** 幂等命中时直接回放原扫描结果。 */
    private Map<String, Object> idempotentResult(AiAnomalyScan duplicated) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scanId", duplicated.getScanId());
        data.put("scanNo", duplicated.getScanNo());
        data.put("status", duplicated.getStatus());
        data.put("openedCount", duplicated.getOpenedCount());
        data.put("summary", "本次触发已扫描过，按幂等键返回原结果");
        data.put("idempotent", true);
        return data;
    }

    /** 建一条 RUNNING 状态的扫描记录对象。 */
    private AiAnomalyScan newScan(String triggerType, String runKey, Long operatorId, LocalDateTime startedAt) {
        AiAnomalyScan scan = new AiAnomalyScan();
        scan.setScanNo(runKey);
        scan.setTriggerType(triggerType);
        scan.setStatus("RUNNING");
        scan.setDetectedCount(0);
        scan.setOpenedCount(0);
        scan.setClosedCount(0);
        scan.setNotifiedCount(0);
        scan.setVersion(0);
        scan.setCreatedBy(operatorId);
        scan.setCreatedTime(startedAt);
        scan.setUpdatedTime(startedAt);
        scan.setDeletedFlag(0);
        return scan;
    }

    /** 本次扫描的新增、重开、已通知条数。 */
    private static final class ScanCounters {
        private int opened;
        private int reopened;
        private int notified;
    }

    /** 把识别到的异常信号登记成异常单：已存在未关闭的跳过，已关闭的重开，其余新建。 */
    private ScanCounters registerExceptions(List<Map<String, Object>> detected,
                                            Long operatorId,
                                            LocalDateTime startedAt) {
        ScanCounters counters = new ScanCounters();
        for (Map<String, Object> item : detected) {
            registerException(item, operatorId, startedAt, counters);
        }
        return counters;
    }

    /** 单条信号落库，按「已存在且未关闭 / 已关闭需重开 / 全新」三种情况处理。 */
    private void registerException(Map<String, Object> item,
                                   Long operatorId,
                                   LocalDateTime startedAt,
                                   ScanCounters counters) {
        String exceptionNo = String.valueOf(item.get("exceptionNo"));
        String exceptionType = String.valueOf(item.get("exceptionType"));
        // 同编号异常已存在且未关闭就不重复登记，只补齐通知
        MfgException existing = findAutoException(exceptionNo);
        if (existing != null && !STATUS_CLOSED.equals(existing.getStatus())) {
            return;
        }
        // 编号在库表上是唯一的：已关闭但问题仍在时重新打开原记录，不能新插一条
        if (existing != null) {
            reopenException(existing, exceptionType, item, operatorId, startedAt, counters);
            return;
        }
        insertException(exceptionNo, exceptionType, item, operatorId, startedAt, counters);
    }

    /** 重开一条已关闭的自动异常单。 */
    private void reopenException(MfgException existing,
                                 String exceptionType,
                                 Map<String, Object> item,
                                 Long operatorId,
                                 LocalDateTime startedAt,
                                 ScanCounters counters) {
        MfgException reopen = new MfgException();
        reopen.setExceptionId(existing.getExceptionId());
        reopen.setExceptionType(exceptionType);
        reopen.setDescription(trim(String.valueOf(item.get("description"))));
        reopen.setHandleResult(null);
        reopen.setStatus(STATUS_SUBMITTED);
        reopen.setVersion(existing.getVersion());
        reopen.setUpdatedBy(operatorId);
        reopen.setUpdatedTime(startedAt);
        if (exceptionMapper.updateById(reopen) != 1) {
            // 版本冲突或记录已被改动时不能算重开成功，否则本轮统计会虚报。
            throw new BusinessException(ResultCode.DATA_CONFLICT.getCode(),
                    "异常单 " + existing.getExceptionNo() + " 已被其他请求修改，本次没有重开，请稍后重试");
        }
        counters.reopened++;
        if (notify(existing, item)) {
            counters.notified++;
        }
    }

    /** 新建一条自动异常单。 */
    private void insertException(String exceptionNo,
                                 String exceptionType,
                                 Map<String, Object> item,
                                 Long operatorId,
                                 LocalDateTime startedAt,
                                 ScanCounters counters) {
        MfgException exception = new MfgException();
        exception.setExceptionNo(exceptionNo);
        exception.setExceptionType(exceptionType);
        exception.setTaskId((Long) item.get("taskId"));
        exception.setStationId((Long) item.get("stationId"));
        exception.setDescription(trim(String.valueOf(item.get("description"))));
        exception.setStatus(STATUS_SUBMITTED);
        exception.setCreatedBy(operatorId);
        exception.setCreatedTime(startedAt);
        exception.setUpdatedTime(startedAt);
        exception.setVersion(0);
        exception.setDeletedFlag(0);
        if (exceptionMapper.insert(exception) != 1) {
            throw new BusinessException(ResultCode.DATA_CONFLICT.getCode(),
                    "自动异常单 " + exceptionNo + " 写入没有生效，本轮统计不记为已登记");
        }
        counters.opened++;
        if (notify(exception, item)) {
            counters.notified++;
        }
    }

    /** 扫描成功后回填汇总字段并组织返回结构。 */
    private Map<String, Object> finishScan(AiAnomalyScan scan,
                                           List<Map<String, Object>> detected,
                                           ScanCounters counters,
                                           int closed,
                                           long beginMillis) {
        scan.setStatus("SUCCEEDED");
        scan.setDetectedCount(detected.size());
        scan.setOpenedCount(counters.opened + counters.reopened);
        scan.setClosedCount(closed);
        scan.setNotifiedCount(counters.notified);
        scan.setElapsedMs(System.currentTimeMillis() - beginMillis);
        scan.setSummary("识别 " + detected.size() + " 条信号，新登记 " + counters.opened
                + " 条，重新打开 " + counters.reopened + " 条，通知 " + counters.notified
                + " 条，关闭已恢复 " + closed + " 条");
        scanMapper.updateById(scan);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scanId", scan.getScanId());
        data.put("scanNo", scan.getScanNo());
        data.put("status", scan.getStatus());
        data.put("detectedCount", detected.size());
        data.put("openedCount", counters.opened + counters.reopened);
        data.put("reopenedCount", counters.reopened);
        data.put("notifiedCount", counters.notified);
        data.put("closedCount", closed);
        data.put("summary", scan.getSummary());
        data.put("elapsedMs", scan.getElapsedMs());
        data.put("idempotent", false);
        return data;
    }

    /** 扫描失败时把现场写回扫描记录，再原样抛出。 */
    private void markFailed(AiAnomalyScan scan, long beginMillis, RuntimeException ex) {
        scan.setStatus("FAILED");
        scan.setElapsedMs(System.currentTimeMillis() - beginMillis);
        scan.setErrorMessage(trim(ex.getMessage()));
        scanMapper.updateById(scan);
        log.warn("主动异常扫描失败：{}", ex.getMessage());
    }

    /** 检验单建单超过阈值小时仍未提交。 */
    private List<Map<String, Object>> detectOverdueInspections(LocalDateTime now) {
        LocalDateTime deadline = now.minusHours(INSPECTION_OVERDUE_HOURS);
        List<QmsInspectionOrder> orders = inspectionOrderMapper.selectList(
                new LambdaQueryWrapper<QmsInspectionOrder>()
                        .eq(QmsInspectionOrder::getDeletedFlag, 0)
                        .eq(QmsInspectionOrder::getStatus, "DRAFT")
                        .isNotNull(QmsInspectionOrder::getCreatedTime)
                        .lt(QmsInspectionOrder::getCreatedTime, deadline)
                        .orderByAsc(QmsInspectionOrder::getInspectionId)
                        .last("LIMIT 200"));
        List<Map<String, Object>> items = new ArrayList<>();
        for (QmsInspectionOrder order : orders) {
            long hours = Duration.between(order.getCreatedTime(), now).toHours();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("exceptionNo", AUTO_EXCEPTION_PREFIX + TYPE_INSPECTION_OVERDUE + "-" + order.getInspectionId());
            item.put("exceptionType", TYPE_INSPECTION_OVERDUE);
            item.put("taskId", order.getTaskId());
            item.put("stationId", null);
            item.put("description", "检验单 " + order.getInspectionNo() + "（" + order.getInspectionType()
                    + "）已建单 " + hours + " 小时仍未提交，超过 " + INSPECTION_OVERDUE_HOURS + " 小时阈值");
            item.put("bizNo", order.getInspectionNo());
            item.put("aggregateType", "QMS_INSPECTION_ORDER");
            item.put("aggregateId", order.getInspectionId());
            item.put("employeeId", notificationSupport.departmentManager(QUALITY_DEPARTMENT));
            item.put("permission", "qms:inspection-order:view");
            item.put("route", INSPECTION_ROUTE);
            item.put("category", "QMS");
            item.put("severity", SEVERITY_WARNING);
            item.put("title", "检验单超期未提交");
            items.add(item);
        }
        return items;
    }

    /**
     * 工单已下达但超过阈值小时仍未开工。
     *
     * <p>停滞时长从实际下达时间算起，不用建单时间：工单建好后常要等计划确认才下达，
     * 用建单时间会把等待下达的时间也算成停滞。实际下达时间取状态变更日志中首次
     * 变为 RELEASED 的时间。没有下达记录、却已经是 RELEASED 的工单是历史脏数据，
     * 无从判断它到底等了多久，直接跳过——按建单时间硬算只会把从没下达过的旧单报成停滞。</p>
     */
    private List<Map<String, Object>> detectIdleWorkOrders(LocalDateTime now) {
        LocalDateTime deadline = now.minusHours(WORK_ORDER_IDLE_HOURS);
        List<Map<String, Object>> rows = workOrderMapper.selectReleasedWorkOrders();
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Long workOrderId = toLong(row.get("workOrderId"));
            LocalDateTime basis = toDateTime(row.get("releasedTime"));
            if (basis == null) {
                continue;
            }
            if (!basis.isBefore(deadline)) {
                continue;
            }
            long hours = Duration.between(basis, now).toHours();
            String no = String.valueOf(row.get("workOrderNo"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("exceptionNo", AUTO_EXCEPTION_PREFIX + TYPE_WORK_ORDER_IDLE + "-" + workOrderId);
            item.put("exceptionType", TYPE_WORK_ORDER_IDLE);
            item.put("taskId", null);
            item.put("stationId", null);
            item.put("description", "工单 " + no + " 已下达 " + hours + " 小时仍未开工，超过 "
                    + WORK_ORDER_IDLE_HOURS + " 小时阈值");
            item.put("bizNo", no);
            item.put("aggregateType", "MFG_WORK_ORDER");
            item.put("aggregateId", workOrderId);
            item.put("employeeId", notificationSupport.departmentManager(WAREHOUSE_DEPARTMENT));
            item.put("permission", "mfg:work-order:view");
            item.put("route", WORK_ORDER_ROUTE);
            item.put("category", "MFG");
            item.put("severity", SEVERITY_INFO);
            item.put("title", "工单下达后未开工");
            items.add(item);
        }
        return items;
    }

    /** 设备处于故障或维修中超过阈值小时。 */
    private List<Map<String, Object>> detectFaultEquipments(LocalDateTime now) {
        LocalDateTime deadline = now.minusHours(EQUIPMENT_FAULT_HOURS);
        List<EamEquipment> equipments = equipmentMapper.selectList(
                new LambdaQueryWrapper<EamEquipment>()
                        .eq(EamEquipment::getDeletedFlag, 0)
                        .in(EamEquipment::getStatus, "FAULT", "REPAIRING")
                        .isNotNull(EamEquipment::getUpdatedTime)
                        .lt(EamEquipment::getUpdatedTime, deadline)
                        .orderByAsc(EamEquipment::getEquipmentId)
                        .last("LIMIT 200"));
        List<Map<String, Object>> items = new ArrayList<>();
        for (EamEquipment equipment : equipments) {
            long hours = Duration.between(equipment.getUpdatedTime(), now).toHours();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("exceptionNo", AUTO_EXCEPTION_PREFIX + TYPE_EQUIPMENT_FAULT + "-" + equipment.getEquipmentId());
            item.put("exceptionType", TYPE_EQUIPMENT_FAULT);
            item.put("taskId", null);
            item.put("stationId", equipment.getStationId());
            item.put("description", "设备 " + equipment.getEquipmentName() + "（" + equipment.getEquipmentCode()
                    + "）处于" + equipment.getStatus() + "状态已 " + hours + " 小时，超过 "
                    + EQUIPMENT_FAULT_HOURS + " 小时阈值");
            item.put("bizNo", equipment.getEquipmentCode());
            item.put("aggregateType", "EAM_EQUIPMENT");
            item.put("aggregateId", equipment.getEquipmentId());
            item.put("employeeId", notificationSupport.departmentManager(WAREHOUSE_DEPARTMENT));
            item.put("permission", "eam:equipment:view");
            item.put("route", EQUIPMENT_ROUTE);
            item.put("category", "EAM");
            item.put("severity", SEVERITY_WARNING);
            item.put("title", "设备故障待处置");
            items.add(item);
        }
        return items;
    }

    /**
     * 条件恢复后自动验证关闭：检验单已提交、工单已开工/完工、设备已恢复，
     * 对应的待处理异常自动置为已关闭并写明依据。
     */
    private int closeRecovered(LocalDateTime now) {
        List<MfgException> open = exceptionMapper.selectList(new LambdaQueryWrapper<MfgException>()
                .eq(MfgException::getDeletedFlag, 0)
                .eq(MfgException::getStatus, STATUS_SUBMITTED)
                .likeRight(MfgException::getExceptionNo, AUTO_EXCEPTION_PREFIX)
                .orderByAsc(MfgException::getExceptionId)
                .last("LIMIT 500"));
        int closed = 0;
        for (MfgException exception : open) {
            String reason = recoveredReason(exception);
            if (reason == null) {
                continue;
            }
            MfgException update = new MfgException();
            update.setExceptionId(exception.getExceptionId());
            update.setStatus(STATUS_CLOSED);
            update.setHandleResult(trim("系统自动验证通过：" + reason));
            update.setUpdatedTime(now);
            exceptionMapper.updateById(update);
            notificationSupport.cancelEvents("MFG_EXCEPTION",
                    exception.getExceptionId(),
                    Set.of("AI_ANOMALY_INSPECTION_OVERDUE", "AI_ANOMALY_WORK_ORDER_IDLE", "AI_ANOMALY_EQUIPMENT_FAULT"),
                    null);
            closed++;
        }
        return closed;
    }

    /** 判断异常条件是否已恢复，恢复则返回依据文本，否则返回 null。 */
    private String recoveredReason(MfgException exception) {
        String type = exception.getExceptionType() == null ? "" : exception.getExceptionType();
        String no = exception.getExceptionNo() == null ? "" : exception.getExceptionNo();
        String suffix = no.substring(no.lastIndexOf('-') + 1);
        try {
            return switch (type) {
                case TYPE_INSPECTION_OVERDUE -> inspectionRecoveredReason(suffix);
                case TYPE_WORK_ORDER_IDLE -> workOrderRecoveredReason(suffix);
                case TYPE_EQUIPMENT_FAULT -> equipmentRecoveredReason(suffix);
                default -> null;
            };
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 检验单类异常：单子删了或已提交就算恢复。 */
    private String inspectionRecoveredReason(String suffix) {
        QmsInspectionOrder order = inspectionOrderMapper.selectById(Long.valueOf(suffix));
        if (order == null || !Integer.valueOf(0).equals(order.getDeletedFlag())) {
            return "检验单已删除";
        }
        return "DRAFT".equals(order.getStatus()) ? null : "检验单已提交，状态：" + order.getStatus();
    }

    /** 工单停滞类异常：单子删了或状态已推进就算恢复。 */
    private String workOrderRecoveredReason(String suffix) {
        MfgWorkOrder order = workOrderMapper.selectById(Long.valueOf(suffix));
        if (order == null || !Integer.valueOf(0).equals(order.getDeletedFlag())) {
            return "工单已删除";
        }
        return "RELEASED".equals(order.getStatus()) ? null : "工单状态已推进至：" + order.getStatus();
    }

    /** 设备故障类异常：设备删了或状态不再是故障/维修中就算恢复。 */
    private String equipmentRecoveredReason(String suffix) {
        EamEquipment equipment = equipmentMapper.selectById(Long.valueOf(suffix));
        if (equipment == null || !Integer.valueOf(0).equals(equipment.getDeletedFlag())) {
            return "设备已删除";
        }
        boolean stillFault = "FAULT".equals(equipment.getStatus()) || "REPAIRING".equals(equipment.getStatus());
        return stillFault ? null : "设备状态已恢复为：" + equipment.getStatus();
    }

    private boolean notify(MfgException exception, Map<String, Object> item) {
        Long employeeId = (Long) item.get("employeeId");
        String eventType = "AI_ANOMALY_" + exception.getExceptionType();
        String eventKey = "AI_ANOMALY:" + exception.getExceptionNo();
        List<com.neuedu.ems.common.NotificationEventPayload.RecipientTarget> targets =
                employeeId == null ? Collections.emptyList()
                        : Collections.singletonList(notificationSupport.target(employeeId,
                        "AUTOMATION_OWNER", String.valueOf(item.get("permission"))));
        notificationSupport.publish(eventKey,
                String.valueOf(item.get("aggregateType")),
                (Long) item.get("aggregateId"),
                eventType,
                String.valueOf(item.get("category")),
                String.valueOf(item.get("severity")),
                String.valueOf(item.get("title")),
                trim(String.valueOf(item.get("description"))),
                String.valueOf(item.get("bizNo")),
                String.valueOf(item.get("route")),
                null, true, targets);
        return employeeId != null;
    }

    private MfgException findAutoException(String exceptionNo) {
        return exceptionMapper.selectOne(new LambdaQueryWrapper<MfgException>()
                .eq(MfgException::getExceptionNo, exceptionNo)
                .eq(MfgException::getDeletedFlag, 0)
                .last("LIMIT 1"));
    }

    private long countExceptions(String status) {
        return exceptionMapper.selectCount(new LambdaQueryWrapper<MfgException>()
                .eq(MfgException::getDeletedFlag, 0)
                .eq(MfgException::getStatus, status)
                .likeRight(MfgException::getExceptionNo, AUTO_EXCEPTION_PREFIX));
    }

    private long countExceptions(String status, String type) {
        return exceptionMapper.selectCount(new LambdaQueryWrapper<MfgException>()
                .eq(MfgException::getDeletedFlag, 0)
                .eq(MfgException::getStatus, status)
                .eq(MfgException::getExceptionType, type)
                .likeRight(MfgException::getExceptionNo, AUTO_EXCEPTION_PREFIX));
    }

    private Map<String, Object> lastScan() {
        AiAnomalyScan scan = scanMapper.selectOne(new LambdaQueryWrapper<AiAnomalyScan>()
                .eq(AiAnomalyScan::getDeletedFlag, 0)
                .orderByDesc(AiAnomalyScan::getScanId)
                .last("LIMIT 1"));
        if (scan == null) {
            return null;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scanId", scan.getScanId());
        data.put("scanNo", scan.getScanNo());
        data.put("status", scan.getStatus());
        data.put("detectedCount", scan.getDetectedCount());
        data.put("openedCount", scan.getOpenedCount());
        data.put("closedCount", scan.getClosedCount());
        data.put("summary", scan.getSummary());
        data.put("createdTime", scan.getCreatedTime());
        data.put("elapsedMs", scan.getElapsedMs());
        return data;
    }

    @Override
    public Page<AiAnomalyScan> pageScans(Integer current, Integer size, String status) {
        Page<AiAnomalyScan> page = new Page<>(current == null ? 1 : current, size == null ? 10 : size);
        return scanMapper.selectPage(page, new LambdaQueryWrapper<AiAnomalyScan>()
                .eq(AiAnomalyScan::getDeletedFlag, 0)
                .eq(status != null && !status.isBlank(), AiAnomalyScan::getStatus, status)
                .orderByDesc(AiAnomalyScan::getScanId));
    }

    @Override
    public Page<MfgException> pageAnomalies(Integer current, Integer size, String status, String exceptionType) {
        Page<MfgException> page = new Page<>(current == null ? 1 : current, size == null ? 10 : size);
        Set<String> autoTypes = new LinkedHashSet<>(List.of(
                TYPE_INSPECTION_OVERDUE, TYPE_WORK_ORDER_IDLE, TYPE_EQUIPMENT_FAULT));
        return exceptionMapper.selectPage(page, new LambdaQueryWrapper<MfgException>()
                .eq(MfgException::getDeletedFlag, 0)
                .likeRight(MfgException::getExceptionNo, AUTO_EXCEPTION_PREFIX)
                .eq(status != null && !status.isBlank(), MfgException::getStatus, status)
                .eq(exceptionType != null && !exceptionType.isBlank(), MfgException::getExceptionType, exceptionType)
                .in(exceptionType == null || exceptionType.isBlank(), MfgException::getExceptionType, autoTypes)
                .orderByDesc(MfgException::getExceptionId));
    }

    @Override
    public boolean closeAnomaly(Long exceptionId, String handleResult, Long operatorId) {
        MfgException existing = exceptionMapper.selectById(exceptionId);
        if (existing == null || !Integer.valueOf(0).equals(existing.getDeletedFlag())) {
            throw new IllegalStateException("异常不存在或已删除");
        }
        if (STATUS_CLOSED.equals(existing.getStatus())) {
            throw new IllegalStateException("该异常已关闭，无需重复处理");
        }
        MfgException update = new MfgException();
        update.setExceptionId(exceptionId);
        update.setStatus(STATUS_CLOSED);
        update.setHandleResult(trim(handleResult == null || handleResult.isBlank() ? "人工确认已处理" : handleResult));
        update.setUpdatedBy(operatorId);
        update.setUpdatedTime(LocalDateTime.now());
        return exceptionMapper.updateById(update) > 0;
    }

    @Override
    public List<Map<String, Object>> thresholds() {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(threshold("INSPECTION_OVERDUE", "检验单超期未提交", "小时",
                INSPECTION_OVERDUE_HOURS, "检验单建单后超过该时长仍未提交，登记质量异常并通知质量部"));
        list.add(threshold("WORK_ORDER_IDLE", "工单下达后未开工", "小时",
                WORK_ORDER_IDLE_HOURS, "工单下达后超过该时长仍未开工，登记生产异常并通知仓库与计划"));
        list.add(threshold("EQUIPMENT_FAULT", "设备故障待处置", "小时",
                EQUIPMENT_FAULT_HOURS, "设备处于故障或维修中超过该时长，登记设备异常并通知责任部门"));
        return list;
    }

    private Map<String, Object> threshold(String code, String label, String unit, long value, String description) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("code", code);
        item.put("label", label);
        item.put("unit", unit);
        item.put("value", value);
        item.put("description", description);
        return item;
    }

    private String trim(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.length() <= 480 ? value : value.substring(0, 480);
    }

    /** 查询结果里的 bigint 在不同驱动下可能是 Long 或 BigInteger，统一转 Long。 */
    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : Long.valueOf(text);
    }

    /** 查询结果里的 datetime 可能是 LocalDateTime 或 Timestamp，统一转 LocalDateTime。 */
    private static LocalDateTime toDateTime(Object value) {
        LocalDateTime result = null;
        if (value instanceof LocalDateTime time) {
            result = time;
        } else if (value instanceof java.sql.Timestamp timestamp) {
            result = timestamp.toLocalDateTime();
        } else if (value instanceof java.util.Date date) {
            result = LocalDateTime.ofInstant(date.toInstant(), java.time.ZoneId.systemDefault());
        } else if (value != null) {
            result = LocalDateTime.parse(String.valueOf(value).replace(' ', 'T'));
        }
        return result;
    }
}
