package com.neuedu.ems.service.mfg;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.neuedu.ems.entity.mfg.MfgWorkOrder;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.service.mdm.MaterialQuantityPolicy;
import com.neuedu.ems.support.ExecutionSnapshotTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionSnapshotPolicyTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final ExecutionSnapshotPolicy policy=new ExecutionSnapshotPolicy(json,new MaterialQuantityPolicy());
    MfgWorkOrder order;
    ObjectNode snapshot;
    @BeforeEach void setup() {
        order=new MfgWorkOrder();order.setWorkOrderId(10L);order.setProductId(11L);order.setBomId(12L);order.setRouteId(13L);
        order.setWorkOrderType("COMPONENT");order.setPlannedQuantity(BigDecimal.TEN);
        snapshot=ExecutionSnapshotTestData.snapshot(json,order,20L,21L);
        ExecutionSnapshotTestData.attach(order,snapshot);
    }
    @Test void readsFrozenUnitsAndRoundsPerConsumptionOperationWithoutLiveMappers() {
        var first=(ObjectNode)snapshot.path("bomItems").get(0);
        first.put("quantity",new BigDecimal("0.04"));
        ((ObjectNode)snapshot.path("bomTree").get(0)).set("bomItems",snapshot.path("bomItems").deepCopy());
        ExecutionSnapshotTestData.attach(order,snapshot);
        var read=policy.read(order);
        assertEquals(0,BigDecimal.ONE.compareTo(policy.directDemands(read,BigDecimal.ONE).get(20L)));
        // 调用方修改这次读取的树，不会改动工单保存的内容。
        ((ObjectNode)read.path("bomItems").get(0)).put("quantity",99);
        assertEquals(new BigDecimal("0.04"),policy.read(order).path("bomItems").get(0).path("quantity").decimalValue());
    }
    @Test void rejectsBadHashAndChangedWorkOrderIdentity() {
        order.setSnapshotHash("broken");assertThrows(BusinessException.class,()->policy.read(order));
        ExecutionSnapshotTestData.attach(order,snapshot);order.setProductId(999L);
        assertThrows(BusinessException.class,()->policy.read(order));
    }
    @ParameterizedTest @ValueSource(strings={"formatVersion","productVersion","outputMaterial","bomTree","parameters","inspectionSchemes","routeVersion","snapshotTime"})
    void rejectsMissingFieldsEvenWhenHashMatches(String field) {
        snapshot.remove(field);ExecutionSnapshotTestData.attach(order,snapshot);
        assertThrows(BusinessException.class,()->policy.read(order));
    }
    @Test void rejectsOldFormatWithoutRewritingIt() {
        snapshot.put("formatVersion","LAMP-SNAPSHOT-1");ExecutionSnapshotTestData.attach(order,snapshot);
        String before=order.getSnapshotJson();assertThrows(BusinessException.class,()->policy.read(order));
        assertEquals(before,order.getSnapshotJson());
    }
    @Test void rejectsWrongOperationAndUnconfirmedQualityEvenWithMatchingHash() {
        ((ObjectNode)snapshot.path("bomItems").get(0)).put("consumptionOperationId",99);
        ((ObjectNode)snapshot.path("bomTree").get(0)).set("bomItems",snapshot.path("bomItems").deepCopy());
        ExecutionSnapshotTestData.attach(order,snapshot);assertThrows(BusinessException.class,()->policy.read(order));
        setup();((ObjectNode)snapshot.path("inspectionSchemes").get(0).path("standards").get(0)).put("confirmationStatus","DRAFT");
        ExecutionSnapshotTestData.attach(order,snapshot);assertThrows(BusinessException.class,()->policy.read(order));
    }
    @Test void rejectsCyclesAndDuplicateSourceRows() {
        var line=(ObjectNode)snapshot.path("bomItems").get(0);
        line.put("childBomId",order.getBomId());line.put("materialId",21);
        ((ObjectNode)line.path("material")).put("materialId",21);
        ((ObjectNode)snapshot.path("bomTree").get(0)).set("bomItems",snapshot.path("bomItems").deepCopy());
        ExecutionSnapshotTestData.attach(order,snapshot);assertThrows(BusinessException.class,()->policy.read(order));
    }
    @Test void roundsSeparatelyForDifferentConsumptionOperations() {
        var steps=(com.fasterxml.jackson.databind.node.ArrayNode)snapshot.path("routeItems");
        ObjectNode second=((ObjectNode)steps.get(0)).deepCopy();
        ((ObjectNode)steps.get(0)).put("outputNode",false);
        second.put("routeItemId",2);second.put("operationId",2);second.put("sequenceNo",2);second.put("qcRequired",0);steps.add(second);
        ((ObjectNode)steps.get(0)).put("qcRequired",0);
        ((ObjectNode)snapshot.path("inspectionSchemes").get(0)).put("operationId",2);
        ((ObjectNode)snapshot.path("inspectionSchemes").get(0).path("standards").get(0)).put("standardOperationId",2);
        var lines=(com.fasterxml.jackson.databind.node.ArrayNode)snapshot.path("bomItems");
        ((ObjectNode)lines.get(0)).put("quantity",new BigDecimal("0.04"));
        ObjectNode other=((ObjectNode)lines.get(0)).deepCopy();other.put("bomItemId",2);other.put("consumptionOperationId",2);lines.add(other);
        ((ObjectNode)snapshot.path("bomTree").get(0)).set("bomItems",lines.deepCopy());
        ExecutionSnapshotTestData.attach(order,snapshot);
        assertEquals(0,new BigDecimal("2").compareTo(policy.directDemands(policy.read(order),BigDecimal.ONE).get(20L)));
    }
}
