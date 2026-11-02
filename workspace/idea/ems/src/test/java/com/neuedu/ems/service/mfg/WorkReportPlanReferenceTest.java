package com.neuedu.ems.service.mfg;

import com.neuedu.ems.entity.mfg.MfgTask;
import com.neuedu.ems.entity.mfg.MfgWorkOrder;
import com.neuedu.ems.entity.process.ProcessRouteItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkReportPlanReferenceTest {
    private final ExecutionSnapshotPolicy snapshots = mock(ExecutionSnapshotPolicy.class);
    private final WorkReportPreparationService service = new WorkReportPreparationService(
            null,null,null,null,null,null,null,null,null,null,null,null,snapshots,null,null);
    private final MfgWorkOrder order = new MfgWorkOrder();
    private final MfgTask task = new MfgTask();

    @BeforeEach void setup() {
        task.setOperationId(7L);
        task.setPlannedQuantity(new BigDecimal("100"));
        when(snapshots.isCurrentFormat(order)).thenReturn(true);
    }

    @Test void onlyCurrentOperationAndWholeTaskQuantityContribute() {
        var current = step(7L,"ASSEMBLY", "3.6");
        when(snapshots.routeItems(order)).thenReturn(List.of(current,step(8L,"ASSEMBLY","99")));
        task.setCompletedQuantity(new BigDecimal("90"));
        assertEquals(new BigDecimal("6.0000"),service.referenceWorkHours(order,task));
    }

    @Test void batchEquipmentUsesWholeBatchesInsteadOfUnitStandardTime() {
        var batch = step(7L,"BATCH_EQUIPMENT","99");
        batch.setBatchCapacity(new BigDecimal("50"));
        batch.setBatchDurationMinutes(new BigDecimal("120"));
        task.setPlannedQuantity(new BigDecimal("51"));
        when(snapshots.routeItems(order)).thenReturn(List.of(batch));
        assertEquals(new BigDecimal("4.0000"),service.referenceWorkHours(order,task));
    }

    @Test void missingOrInvalidTimingRemainsUnknown() {
        when(snapshots.routeItems(order)).thenReturn(List.of(step(8L,"ASSEMBLY","2")));
        assertNull(service.referenceWorkHours(order,task));
        when(snapshots.routeItems(order)).thenReturn(List.of(step(7L,"ASSEMBLY","0")));
        assertNull(service.referenceWorkHours(order,task));
        when(snapshots.routeItems(order)).thenReturn(List.of(step(7L,"BATCH_EQUIPMENT","2")));
        assertNull(service.referenceWorkHours(order,task));
    }

    @Test void legacyAndReworkDoNotGuessFromCurrentMasterData() {
        when(snapshots.isCurrentFormat(order)).thenReturn(false);
        assertNull(service.referenceWorkHours(order,task));
        when(snapshots.isCurrentFormat(order)).thenReturn(true);
        task.setTaskType("REWORK");
        assertNull(service.referenceWorkHours(order,task));
        verify(snapshots,never()).routeItems(any());
    }

    private ProcessRouteItem step(Long operation, String type, String minutes) {
        var step = new ProcessRouteItem();
        step.setOperationId(operation);
        step.setResourceType(type);
        step.setStandardTime(new BigDecimal(minutes));
        return step;
    }
}
