package com.neuedu.ems.service.impl.ai;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.neuedu.ems.entity.ai.AiAnomalyScan;
import com.neuedu.ems.entity.eam.EamEquipment;
import com.neuedu.ems.entity.mfg.MfgException;
import com.neuedu.ems.entity.mfg.MfgWorkOrder;
import com.neuedu.ems.entity.qms.QmsInspectionOrder;
import com.neuedu.ems.mapper.ai.AiAnomalyScanMapper;
import com.neuedu.ems.mapper.eam.EamEquipmentMapper;
import com.neuedu.ems.mapper.mfg.MfgExceptionMapper;
import com.neuedu.ems.mapper.mfg.MfgWorkOrderMapper;
import com.neuedu.ems.mapper.qms.QmsInspectionOrderMapper;
import com.neuedu.ems.service.impl.sys.ResponsibilityNotificationSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 主动异常闭环单元测试：验证三类信号能被识别、异常按编号幂等登记、
 * 恢复后自动关闭、同一分钟窗口重复扫描不重复登记。
 */
class AiAnomalyServiceImplTest {

    private AiAnomalyScanMapper scanMapper;
    private MfgExceptionMapper exceptionMapper;
    private QmsInspectionOrderMapper inspectionOrderMapper;
    private MfgWorkOrderMapper workOrderMapper;
    private EamEquipmentMapper equipmentMapper;
    private ResponsibilityNotificationSupport notificationSupport;
    private AiAnomalyServiceImpl service;

    @BeforeEach
    void setUp() {
        // LambdaQueryWrapper 需要先注册实体元数据，否则取参数时会报 lambda cache 缺失
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "mfgException"),
                MfgException.class);
        scanMapper = mock(AiAnomalyScanMapper.class);
        exceptionMapper = mock(MfgExceptionMapper.class);
        inspectionOrderMapper = mock(QmsInspectionOrderMapper.class);
        workOrderMapper = mock(MfgWorkOrderMapper.class);
        equipmentMapper = mock(EamEquipmentMapper.class);
        notificationSupport = mock(ResponsibilityNotificationSupport.class);
        service = new AiAnomalyServiceImpl(scanMapper, exceptionMapper, inspectionOrderMapper,
                workOrderMapper, equipmentMapper, notificationSupport);
        when(scanMapper.insert(any(AiAnomalyScan.class))).thenAnswer(invocation -> {
            AiAnomalyScan scan = invocation.getArgument(0);
            scan.setScanId(9L);
            return 1;
        });
        when(scanMapper.updateById(any(AiAnomalyScan.class))).thenReturn(1);
        when(exceptionMapper.insert(any(MfgException.class))).thenReturn(1);
        when(exceptionMapper.updateById(any(MfgException.class))).thenReturn(1);
        when(notificationSupport.departmentManager(any())).thenReturn(null);
    }

    @Test
    void overdueInspectionIsRegisteredOnceWithStableNumber() {
        QmsInspectionOrder order = new QmsInspectionOrder();
        order.setInspectionId(31L);
        order.setInspectionNo("QI-OVERDUE-31");
        order.setInspectionType("INCOMING");
        order.setStatus("DRAFT");
        order.setDeletedFlag(0);
        order.setCreatedTime(LocalDateTime.now().minusHours(12));
        when(inspectionOrderMapper.selectList(any())).thenReturn(List.of(order));
        when(workOrderMapper.selectReleasedWorkOrders()).thenReturn(List.of());
        when(equipmentMapper.selectList(any())).thenReturn(List.of());
        when(exceptionMapper.selectOne(any())).thenReturn(null);
        when(exceptionMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> result = service.scan(1L);

        assertEquals(1, result.get("openedCount"));
        assertEquals(1, result.get("detectedCount"));
        ArgumentCaptor<MfgException> captor = ArgumentCaptor.forClass(MfgException.class);
        verify(exceptionMapper, times(1)).insert(captor.capture());
        MfgException saved = captor.getValue();
        assertEquals("EXC-AUTO-INSPECTION_OVERDUE-31", saved.getExceptionNo());
        assertEquals("INSPECTION_OVERDUE", saved.getExceptionType());
        assertEquals("SUBMITTED", saved.getStatus());
        assertTrue(saved.getDescription().contains("QI-OVERDUE-31"));
        assertTrue(saved.getDescription().contains("12 小时"));
    }

    @Test
    void existingOpenAnomalyIsNotRegisteredTwice() {
        QmsInspectionOrder order = new QmsInspectionOrder();
        order.setInspectionId(31L);
        order.setInspectionNo("QI-OVERDUE-31");
        order.setInspectionType("INCOMING");
        order.setStatus("DRAFT");
        order.setDeletedFlag(0);
        order.setCreatedTime(LocalDateTime.now().minusHours(20));
        when(inspectionOrderMapper.selectList(any())).thenReturn(List.of(order));
        when(workOrderMapper.selectReleasedWorkOrders()).thenReturn(List.of());
        when(equipmentMapper.selectList(any())).thenReturn(List.of());
        MfgException existing = new MfgException();
        existing.setExceptionId(5L);
        existing.setExceptionNo("EXC-AUTO-INSPECTION_OVERDUE-31");
        existing.setStatus("SUBMITTED");
        when(exceptionMapper.selectOne(any())).thenReturn(existing);
        when(exceptionMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> result = service.scan(1L);

        assertEquals(0, result.get("openedCount"));
        verify(exceptionMapper, never()).insert(any(MfgException.class));
    }

    @Test
    void recoveredInspectionClosesAnomalyAutomatically() {
        when(inspectionOrderMapper.selectList(any())).thenReturn(List.of());
        when(workOrderMapper.selectReleasedWorkOrders()).thenReturn(List.of());
        when(equipmentMapper.selectList(any())).thenReturn(List.of());

        MfgException open = new MfgException();
        open.setExceptionId(7L);
        open.setExceptionNo("EXC-AUTO-INSPECTION_OVERDUE-31");
        open.setExceptionType("INSPECTION_OVERDUE");
        open.setStatus("SUBMITTED");
        open.setDeletedFlag(0);
        when(exceptionMapper.selectList(any())).thenReturn(List.of(open));

        QmsInspectionOrder submitted = new QmsInspectionOrder();
        submitted.setInspectionId(31L);
        submitted.setStatus("FINISHED");
        submitted.setDeletedFlag(0);
        when(inspectionOrderMapper.selectById(31L)).thenReturn(submitted);

        Map<String, Object> result = service.scan(1L);

        assertEquals(1, result.get("closedCount"));
        ArgumentCaptor<MfgException> captor = ArgumentCaptor.forClass(MfgException.class);
        verify(exceptionMapper, times(1)).updateById(captor.capture());
        MfgException closed = captor.getValue();
        assertEquals(7L, closed.getExceptionId());
        assertEquals("CLOSED", closed.getStatus());
        assertTrue(closed.getHandleResult().contains("检验单已提交"));
    }

    @Test
    void idleWorkOrderAndFaultEquipmentAreDetected() {
        when(inspectionOrderMapper.selectList(any())).thenReturn(List.of());
        when(workOrderMapper.selectReleasedWorkOrders())
                .thenReturn(List.of(releasedRow(44L, "WO-IDLE-44", 30, 40)));

        EamEquipment equipment = new EamEquipment();
        equipment.setEquipmentId(8L);
        equipment.setEquipmentCode("LAMP-EQ-FAULT-8");
        equipment.setEquipmentName("贴片机");
        equipment.setStatus("FAULT");
        equipment.setStationId(3L);
        equipment.setDeletedFlag(0);
        equipment.setUpdatedTime(LocalDateTime.now().minusHours(5));
        when(equipmentMapper.selectList(any())).thenReturn(List.of(equipment));
        when(exceptionMapper.selectOne(any())).thenReturn(null);
        when(exceptionMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> result = service.scan(1L);

        assertEquals(2, result.get("openedCount"));
        ArgumentCaptor<MfgException> captor = ArgumentCaptor.forClass(MfgException.class);
        verify(exceptionMapper, times(2)).insert(captor.capture());
        List<String> numbers = new ArrayList<>();
        for (MfgException item : captor.getAllValues()) {
            numbers.add(item.getExceptionNo());
        }
        assertTrue(numbers.contains("EXC-AUTO-WORK_ORDER_IDLE-44"));
        assertTrue(numbers.contains("EXC-AUTO-EQUIPMENT_FAULT-8"));
    }

    @Test
    void sameWindowScansOnlyOnce() {
        AiAnomalyScan done = new AiAnomalyScan();
        done.setScanId(3L);
        done.setScanNo("SCHEDULE:202610250610");
        done.setStatus("SUCCEEDED");
        done.setOpenedCount(4);
        when(scanMapper.selectOne(any())).thenReturn(done);

        int opened = service.scanScheduled();

        // 幂等键命中时返回上一次扫描的登记条数，不重复开新一轮
        assertEquals(4, opened);
        verify(scanMapper, never()).insert(any(AiAnomalyScan.class));
    }

    @Test
    void overviewCountsOpenAnomaliesByType() {
        when(exceptionMapper.selectCount(any())).thenReturn(3L);
        AiAnomalyScan scan = new AiAnomalyScan();
        scan.setScanId(3L);
        scan.setScanNo("SCHEDULE:202610250610");
        scan.setStatus("SUCCEEDED");
        when(scanMapper.selectOne(any())).thenReturn(scan);

        Map<String, Object> overview = service.overview();

        assertEquals(3L, overview.get("openCount"));
        assertEquals(3L, overview.get("inspectionOverdue"));
        assertNotNull(overview.get("lastScan"));
        assertEquals(3, ((List<?>) overview.get("thresholds")).size());
    }

    @Test
    void closedAnomalyIsReopenedInsteadOfInsertingDuplicateNumber() {
        QmsInspectionOrder order = new QmsInspectionOrder();
        order.setInspectionId(31L);
        order.setInspectionNo("QI-OVERDUE-31");
        order.setInspectionType("INCOMING");
        order.setStatus("DRAFT");
        order.setDeletedFlag(0);
        order.setCreatedTime(LocalDateTime.now().minusHours(15));
        when(inspectionOrderMapper.selectList(any())).thenReturn(List.of(order));
        when(workOrderMapper.selectReleasedWorkOrders()).thenReturn(List.of());
        when(equipmentMapper.selectList(any())).thenReturn(List.of());
        MfgException closed = new MfgException();
        closed.setExceptionId(21L);
        closed.setExceptionNo("EXC-AUTO-INSPECTION_OVERDUE-31");
        closed.setStatus("CLOSED");
        closed.setVersion(2);
        closed.setDeletedFlag(0);
        when(exceptionMapper.selectOne(any())).thenReturn(closed);
        when(exceptionMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> result = service.scan(1L);

        // 编号唯一，问题仍在时只能重新打开原记录，不能再插一条
        assertEquals(1, result.get("openedCount"));
        assertEquals(1, result.get("reopenedCount"));
        verify(exceptionMapper, never()).insert(any(MfgException.class));
        ArgumentCaptor<MfgException> captor = ArgumentCaptor.forClass(MfgException.class);
        verify(exceptionMapper, times(1)).updateById(captor.capture());
        assertEquals(21L, captor.getValue().getExceptionId());
        assertEquals("SUBMITTED", captor.getValue().getStatus());
    }

    @Test
    void closingAlreadyClosedAnomalyIsRejected() {
        MfgException closed = new MfgException();
        closed.setExceptionId(11L);
        closed.setStatus("CLOSED");
        closed.setDeletedFlag(0);
        when(exceptionMapper.selectById(11L)).thenReturn(closed);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.closeAnomaly(11L, "重复关闭", 1L));
        assertTrue(ex.getMessage().contains("已关闭"));
    }

    @Test
    void scansAreListedWithPaging() {
        when(scanMapper.selectPage(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.pageScans(1, 10, null);

        verify(scanMapper, times(1)).selectPage(any(), any());
    }

    @Test
    void anomalyPageFiltersAutoNumbersOnly() {
        when(exceptionMapper.selectPage(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.pageAnomalies(1, 10, "SUBMITTED", null);

        ArgumentCaptor<LambdaQueryWrapper<MfgException>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(exceptionMapper, times(1)).selectPage(any(), captor.capture());
        // 渲染一次 SQL 片段才会真正生成参数，只查系统自动登记的异常
        String segment = captor.getValue().getSqlSegment();
        java.util.Map<String, Object> pairs = captor.getValue().getParamNameValuePairs();
        assertTrue(segment.contains("exception_no"), "实际 SQL：" + segment);
        assertTrue(pairs.values().stream().anyMatch(value -> String.valueOf(value).startsWith("EXC-AUTO-")),
                "实际参数：" + pairs);
        assertTrue(pairs.values().stream().anyMatch(value -> "SUBMITTED".equals(value)),
                "实际参数：" + pairs);
    }

    @Test
    void idleWorkOrderCountsFromReleaseTimeNotCreationTime() {
        when(inspectionOrderMapper.selectList(any())).thenReturn(List.of());
        // 建单 30 小时前、但 2 小时前才下达：还没到 12 小时阈值，不能报停滞
        when(workOrderMapper.selectReleasedWorkOrders())
                .thenReturn(List.of(releasedRow(51L, "WO-FRESH-51", 2, 30)));
        when(equipmentMapper.selectList(any())).thenReturn(List.of());
        when(exceptionMapper.selectOne(any())).thenReturn(null);
        when(exceptionMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> result = service.scan(1L);

        assertEquals(0, result.get("detectedCount"));
        verify(exceptionMapper, never()).insert(any(MfgException.class));
    }

    @Test
    void idleWorkOrderHoursComeFromReleaseTime() {
        when(inspectionOrderMapper.selectList(any())).thenReturn(List.of());
        // 建单 1 小时前、下达 20 小时前（历史补录）：按 20 小时判停滞，不能按建单 1 小时算
        when(workOrderMapper.selectReleasedWorkOrders())
                .thenReturn(List.of(releasedRow(52L, "WO-LATE-52", 20, 1)));
        when(equipmentMapper.selectList(any())).thenReturn(List.of());
        when(exceptionMapper.selectOne(any())).thenReturn(null);
        when(exceptionMapper.selectList(any())).thenReturn(List.of());

        service.scan(1L);

        ArgumentCaptor<MfgException> captor = ArgumentCaptor.forClass(MfgException.class);
        verify(exceptionMapper, times(1)).insert(captor.capture());
        String description = captor.getValue().getDescription();
        assertTrue(description.contains("已下达 20 小时"), "实际描述：" + description);
        assertTrue(!description.contains("无下达记录"), "实际描述：" + description);
    }

    @Test
    void workOrderWithoutReleaseLogIsNotReportedAsIdle() {
        when(inspectionOrderMapper.selectList(any())).thenReturn(List.of());
        // 状态是 RELEASED 却没有下达日志：历史脏数据，无从判断等了多久，不该报成停滞
        when(workOrderMapper.selectReleasedWorkOrders())
                .thenReturn(List.of(releasedRow(53L, "WO-NOLOG-53", null, 30)));
        when(equipmentMapper.selectList(any())).thenReturn(List.of());
        when(exceptionMapper.selectOne(any())).thenReturn(null);
        when(exceptionMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> result = service.scan(1L);

        assertEquals(0, result.get("detectedCount"), "没有下达记录就不该报「下达后未开工」");
        verify(exceptionMapper, never()).insert(any(MfgException.class));
    }

    /** 构造 selectReleasedWorkOrders 的一行：createdHoursAgo 与 releasedHoursAgo 为 null 表示没有该时间。 */
    private static Map<String, Object> releasedRow(Long workOrderId, String workOrderNo,
                                                   Integer releasedHoursAgo, int createdHoursAgo) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("workOrderId", workOrderId);
        row.put("workOrderNo", workOrderNo);
        row.put("createdTime", LocalDateTime.now().minusHours(createdHoursAgo));
        row.put("releasedTime", releasedHoursAgo == null ? null
                : LocalDateTime.now().minusHours(releasedHoursAgo));
        return row;
    }
}
