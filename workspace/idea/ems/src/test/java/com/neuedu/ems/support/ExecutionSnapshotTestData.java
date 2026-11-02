package com.neuedu.ems.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.neuedu.ems.entity.mfg.MfgWorkOrder;
import com.neuedu.ems.service.mfg.ExecutionSnapshotPolicy;
import java.math.BigDecimal;

/** 合成的冻结内容，只用于解析/移库测试，不代表真实主数据确认或生产。 */
public final class ExecutionSnapshotTestData {
    private ExecutionSnapshotTestData() {}
    public static ObjectNode snapshot(ObjectMapper json,MfgWorkOrder order,long input,long output) {
        ObjectNode root=json.createObjectNode();
        root.put("formatVersion",ExecutionSnapshotPolicy.FORMAT); root.put("workOrderId",order.getWorkOrderId());
        root.put("productId",order.getProductId()); root.put("bomId",order.getBomId()); root.put("routeId",order.getRouteId());
        root.put("workOrderType",order.getWorkOrderType()); root.put("plannedQuantity",order.getPlannedQuantity());
        root.put("productVersion",0); root.put("snapshotTime","2026-10-24T10:00:00"); root.put("routeVersion","TEST-V1");root.put("frozenBy",1);
        root.set("outputMaterial",material(json,output));
        ObjectNode step=root.putArray("routeItems").addObject();
        step.put("routeItemId",1);step.put("operationId",1);step.put("operationCode","TEST-OP");step.put("operationName","测试末工序");
        step.put("sequenceNo",1);step.put("version",0);step.put("operationVersion",0);step.put("standardTime",1);step.put("resourceType","LABOR");
        step.put("timeSourceReference","TEST-ONLY");step.put("qcRequired",1);step.put("outputNode",true);
        ObjectNode node=root.putArray("bomTree").addObject();
        node.put("bomId",order.getBomId());node.put("productId",order.getProductId());node.put("productMaterialId",output);
        node.put("bomVersion","TEST-V1");node.put("version",0);
        ObjectNode line=node.putArray("bomItems").addObject();
        line.put("bomItemId",1);line.put("bomId",order.getBomId());line.put("materialId",input);line.putNull("childBomId");
        line.put("quantity",BigDecimal.ONE);line.put("lossRate",BigDecimal.ZERO);line.put("version",0);line.put("consumptionOperationId",1);
        line.set("material",material(json,input)); root.set("bomItems",node.path("bomItems").deepCopy());
        ObjectNode parameter=root.putArray("parameters").addObject();
        parameter.put("productId",order.getProductId());
        parameter.put("parameterId",1);parameter.put("parameterCode","TEST-PARAM");parameter.put("version",0);parameter.put("status","CONFIRMED");
        parameter.put("parameterValue","1");parameter.put("sourceReference","TEST-ONLY");parameter.put("valueType","INTEGER");
        ObjectNode scheme=root.putArray("inspectionSchemes").addObject();
        scheme.put("schemeId",1);scheme.put("productId",order.getProductId());scheme.put("version",0);scheme.put("status","RELEASED");
        scheme.put("inspectionType","FINAL");scheme.put("operationId",1);
        ObjectNode standard=scheme.putArray("standards").addObject();
        standard.put("standardProductId",order.getProductId());standard.put("standardOperationId",1);
        standard.put("schemeItemId",1);standard.put("standardId",1);standard.put("itemId",1);standard.put("version",0);
        standard.put("standardVersion",0);standard.put("itemVersion",0);standard.put("confirmationStatus","CONFIRMED");
        standard.put("sourceReference","TEST-ONLY");standard.put("judgementRule","RANGE");standard.put("samplingMode","ALL");
        standard.put("upperLimit","2");standard.put("lowerLimit","0");standard.put("standardValue","1");standard.set("unit",unit(json));
        return root;
    }
    public static void attach(MfgWorkOrder order,ObjectNode snapshot) {
        order.setSnapshotJson(snapshot.toString());order.setSnapshotHash(ExecutionSnapshotPolicy.hash(order.getSnapshotJson()));
    }
    private static ObjectNode material(ObjectMapper json,long id) {
        ObjectNode node=json.createObjectNode();node.put("materialId",id);node.put("materialCode","TEST-"+id);node.put("version",0);
        node.put("quantityMode","DISCRETE");node.put("issueStep",1);node.set("unit",unit(json));return node;
    }
    private static ObjectNode unit(ObjectMapper json) {
        ObjectNode node=json.createObjectNode();node.put("unitId",1);node.put("unitCode","TEST-PC");node.put("unitName","测试件");
        node.put("version",0);node.put("precisionScale",0);return node;
    }
}
