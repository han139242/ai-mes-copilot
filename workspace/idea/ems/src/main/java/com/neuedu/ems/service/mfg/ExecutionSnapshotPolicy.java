package com.neuedu.ems.service.mfg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.entity.mdm.MdmMaterial;
import com.neuedu.ems.entity.mfg.MfgWorkOrder;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.service.mdm.MaterialQuantityPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/** 冻结依据的统一读取。只读取工单保存内容，不查询当前主数据。 */
@Service
@RequiredArgsConstructor
public class ExecutionSnapshotPolicy {
    public static final String FORMAT = "LAMP-SNAPSHOT-2";
    private final ObjectMapper json;
    private final MaterialQuantityPolicy quantities;

    /** 仅区分历史非受控读路径；受控产品准入仍须独立校验。 */
    public boolean isCurrentFormat(MfgWorkOrder order) {
        if(order==null || order.getSnapshotJson()==null)return false;
        try { return FORMAT.equals(json.readTree(order.getSnapshotJson()).path("formatVersion").asText()); }
        catch(Exception e) { throw invalid("工单快照无法解析"); }
    }

    public List<com.neuedu.ems.entity.process.ProcessRouteItem> routeItems(MfgWorkOrder order) {
        List<com.neuedu.ems.entity.process.ProcessRouteItem> result=new ArrayList<>();
        for(JsonNode row:read(order).path("routeItems")) {
            var item=new com.neuedu.ems.entity.process.ProcessRouteItem();
            item.setRouteId(order.getRouteId());item.setRouteItemId(row.path("routeItemId").longValue());
            item.setOperationId(row.path("operationId").longValue());item.setSequenceNo(row.path("sequenceNo").intValue());
            item.setStandardTime(row.path("standardTime").decimalValue());item.setResourceType(row.path("resourceType").asText());
            if(row.path("batchCapacity").isNumber())item.setBatchCapacity(row.path("batchCapacity").decimalValue());
            if(row.path("batchDurationMinutes").isNumber())item.setBatchDurationMinutes(row.path("batchDurationMinutes").decimalValue());
            item.setTimeSourceReference(row.path("timeSourceReference").asText());item.setQcRequired(row.path("qcRequired").intValue());
            item.setVersion(row.path("version").intValue());item.setDeletedFlag(0);result.add(item);
        }
        return List.copyOf(result);
    }

    public List<com.neuedu.ems.entity.qms.QmsInspectionScheme> inspectionSchemes(MfgWorkOrder order,Long operationId,String type) {
        List<com.neuedu.ems.entity.qms.QmsInspectionScheme> result=new ArrayList<>();
        for(JsonNode row:read(order).path("inspectionSchemes"))
            if(type.equals(row.path("inspectionType").asText()) && row.path("operationId").isIntegralNumber()
                    && Objects.equals(operationId,row.path("operationId").longValue())) {
                var scheme=new com.neuedu.ems.entity.qms.QmsInspectionScheme();
                scheme.setSchemeId(row.path("schemeId").longValue());scheme.setProductId(order.getProductId());
                scheme.setOperationId(operationId);scheme.setInspectionType(type);scheme.setStatus("RELEASED");
                scheme.setVersion(row.path("version").intValue());scheme.setDeletedFlag(0);result.add(scheme);
            }
        return List.copyOf(result);
    }

    public JsonNode read(MfgWorkOrder order) {
        try {
            require(order != null && order.getSnapshotJson() != null && order.getSnapshotHash() != null,
                    "工单缺少冻结快照，请按版本流程重建");
            require(order.getSnapshotJson().length() <= 2_000_000, "快照超过支持大小");
            require(hash(order.getSnapshotJson()).equals(order.getSnapshotHash()), "快照摘要不一致");
            JsonNode root = json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .readTree(order.getSnapshotJson());
            validate(root, order);
            return root;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw invalid("冻结快照格式损坏，请保留原快照并按版本流程重建");
        }
    }

    public void validate(JsonNode root, MfgWorkOrder order) {
        require(root != null && root.isObject() && FORMAT.equals(root.path("formatVersion").asText()),
                "冻结快照版本不支持，旧快照不能直接补填或执行");
        same(root, "workOrderId", order.getWorkOrderId());
        same(root, "productId", order.getProductId());
        same(root, "bomId", order.getBomId());
        same(root, "routeId", order.getRouteId());
        require(order.getPlannedQuantity() != null && decimal(root, "plannedQuantity").compareTo(order.getPlannedQuantity()) == 0,
                "冻结生产数量与工单不一致");
        require(Objects.equals(text(root, "workOrderType"), order.getWorkOrderType()), "冻结工单类型不一致");
        LocalDateTime.parse(text(root, "snapshotTime"));
        id(root,"frozenBy");
        JsonNode output = root.path("outputMaterial");
        material(output);
        quantities.validateExecution(frozenMaterial(output), order.getPlannedQuantity());
        require(root.path("productVersion").isIntegralNumber() && root.path("productVersion").canConvertToInt() && root.path("productVersion").intValue() >= 0,
                "缺少产品来源版本");

        Set<Long> operations = new HashSet<>();
        Set<Integer> sequences = new HashSet<>();
        Set<Long> routeRows = new HashSet<>();
        int prior = 0;
        int outputs = 0;
        JsonNode steps = array(root, "routeItems", true);
        for (JsonNode step : steps) {
            require(routeRows.add(id(step, "routeItemId")), "工序来源行重复");
            require(operations.add(id(step, "operationId")), "投料工序在路线中不唯一");
            int sequence = integer(step, "sequenceNo");
            require(sequence > prior && sequences.add(sequence), "工序顺序无效");
            prior = sequence;
            text(step, "operationCode"); text(step, "operationName"); version(step,"operationVersion");
            version(step, "version");
            require(decimal(step, "standardTime").signum() >= 0, "人工工时不能为负");
            String resource = text(step, "resourceType");
            require(Set.of("LABOR", "BATCH_EQUIPMENT").contains(resource), "资源类型无效");
            text(step, "timeSourceReference");
            if ("BATCH_EQUIPMENT".equals(resource)) {
                require(decimal(step, "batchCapacity").signum() > 0
                        && decimal(step, "batchCapacity").stripTrailingZeros().scale() <= 0
                        && decimal(step, "batchDurationMinutes").signum() > 0, "设备批容量或时长无效");
            }
            int qc = integer(step, "qcRequired");
            require(qc == 0 || qc == 1, "检验标记无效");
            require(step.path("outputNode").isBoolean(), "缺少产出节点定义");
            if (step.path("outputNode").booleanValue()) outputs++;
        }
        require(outputs == 1 && steps.get(steps.size()-1).path("outputNode").asBoolean(), "必须且仅末工序为产出节点");
        text(root, "routeVersion");

        Map<Long, JsonNode> nodes = new LinkedHashMap<>();
        Set<Long> sourceRows = new HashSet<>();
        for (JsonNode node : array(root, "bomTree", true)) {
            require(nodes.put(id(node, "bomId"), node) == null, "BOM版本节点重复");
            id(node, "productId"); id(node, "productMaterialId");
            text(node, "bomVersion"); version(node, "version");
            for (JsonNode line : array(node, "bomItems", true)) {
                require(sourceRows.add(id(line, "bomItemId")), "BOM来源行重复");
                same(line, "bomId", id(node, "bomId"));
                id(line, "consumptionOperationId");
                require(decimal(line, "quantity").signum() > 0, "BOM单位用量无效");
                BigDecimal loss = decimal(line, "lossRate");
                require(loss.signum() >= 0 && loss.compareTo(BigDecimal.ONE) <= 0, "损耗率须在0到1之间");
                material(line.path("material"));
                same(line, "materialId", id(line.path("material"), "materialId"));
                version(line, "version");
            }
        }
        JsonNode direct = nodes.get(order.getBomId());
        require(direct != null, "快照缺少根BOM");
        same(direct, "productId", order.getProductId());
        same(direct, "productMaterialId", id(output, "materialId"));
        require(direct.path("bomItems").equals(array(root, "bomItems", true)), "直接需求与冻结BOM不一致");
        for (JsonNode line : direct.path("bomItems"))
            require(operations.contains(id(line, "consumptionOperationId")), "本级投料工序不属于冻结路线");
        Set<Long> visited = new HashSet<>();
        walk(order.getBomId(), nodes, new HashSet<>(), visited, 0);
        require(visited.size() == nodes.size(), "快照含无来源BOM节点");

        Set<String> parameters = new HashSet<>();
        for (JsonNode parameter : array(root, "parameters", true)) {
            same(parameter,"productId",order.getProductId());
            require(parameters.add(text(parameter, "parameterCode")), "产品参数重复");
            id(parameter, "parameterId"); version(parameter, "version");
            require("CONFIRMED".equals(text(parameter, "status")), "产品参数未经确认");
            text(parameter, "parameterValue"); text(parameter, "sourceReference"); text(parameter, "valueType");
        }
        Set<Long> schemes = new HashSet<>();
        Set<Long> qcOperations = new HashSet<>();
        Set<String> executionSchemeKeys=new HashSet<>();
        int finalSchemes=0;
        for (JsonNode scheme : array(root, "inspectionSchemes", true)) {
            require(schemes.add(id(scheme, "schemeId")), "检验方案重复");
            same(scheme, "productId", order.getProductId()); version(scheme, "version");
            require("RELEASED".equals(text(scheme, "status")), "检验方案未发布");
            String type=text(scheme, "inspectionType");
            require(Set.of("INCOMING","PROCESS","FINAL","OUTGOING").contains(type),"检验类型无效");
            if (!scheme.path("operationId").isNull() && Set.of("PROCESS","FINAL").contains(type))
                qcOperations.add(id(scheme, "operationId"));
            if(Set.of("PROCESS","FINAL").contains(type)) {
                require(scheme.path("operationId").isIntegralNumber(),"过程检和终检方案必须显式绑定冻结工序");
                long operation=id(scheme,"operationId");
                require(operations.contains(operation),"检验工序不属于冻结路线");
                require(executionSchemeKeys.add(type+":"+operation),"工序存在歧义检验方案");
                if("FINAL".equals(type)) {
                    require(operation==id(steps.get(steps.size()-1),"operationId"),"终检不属于唯一产出工序");
                    finalSchemes++;
                }
            }
            Set<Long> items = new HashSet<>();
            for (JsonNode standard : array(scheme, "standards", true)) {
                id(standard, "schemeItemId"); id(standard, "standardId");
                same(standard,"standardProductId",order.getProductId());
                require(standard.path("standardOperationId").isNull()
                        || standard.path("standardOperationId").equals(scheme.path("operationId")),"冻结标准工序与方案不一致");
                require(items.add(id(standard, "itemId")), "检验项目重复");
                version(standard, "standardVersion"); version(standard, "itemVersion"); version(standard, "version");
                require("CONFIRMED".equals(text(standard, "confirmationStatus")), "检验标准未确认");
                text(standard, "sourceReference"); text(standard, "judgementRule");
                String sampling = text(standard, "samplingMode");
                require(Set.of("ALL", "FIXED").contains(sampling), "抽样规则无效");
                if ("FIXED".equals(sampling)) require(decimal(standard,"sampleQuantity").signum()>0
                        && decimal(standard,"sampleQuantity").stripTrailingZeros().scale()<=0, "固定抽样数量无效");
                if (!standard.path("unit").isNull()) unit(standard.path("unit"));
                else require(!hasText(standard, "upperLimit") && !hasText(standard, "lowerLimit"), "数值限值缺少计量单位");
            }
        }
        for (JsonNode step : steps) {
            if (step.path("qcRequired").intValue() == 1) {
                require(qcOperations.contains(id(step, "operationId")), "必检工序缺少冻结检验方案");
            }
        }
        require(finalSchemes==1,"缺少唯一产出工序的终检方案");
    }

    /** 按工单、物料、投料工序汇总后取整；不重新读取物料当前粒度。 */
    public Map<Long, BigDecimal> directDemands(JsonNode snapshot, BigDecimal quantity) {
        quantities.validateExecution(frozenMaterial(snapshot.path("outputMaterial")), quantity);
        Map<String, BigDecimal> theoretical = new LinkedHashMap<>();
        Map<String, JsonNode> lines = new LinkedHashMap<>();
        for (JsonNode line : snapshot.path("bomItems")) {
            String key = id(line,"materialId") + ":" + id(line,"consumptionOperationId");
            theoretical.merge(key, quantity.multiply(decimal(line,"quantity"))
                    .multiply(BigDecimal.ONE.add(decimal(line,"lossRate"))), BigDecimal::add);
            lines.put(key,line);
        }
        Map<Long, BigDecimal> result = new LinkedHashMap<>();
        theoretical.forEach((key, amount) -> result.merge(id(lines.get(key),"materialId"),
                quantities.executionDemand(frozenMaterial(lines.get(key).path("material")),amount), BigDecimal::add));
        return result;
    }

    private void walk(Long bomId, Map<Long,JsonNode> nodes, Set<Long> path, Set<Long> visited, int depth) {
        require(depth <= 32 && path.add(bomId), "BOM循环或层级超限");
        JsonNode node = nodes.get(bomId);
        require(node != null, "子BOM版本缺失");
        if(visited.contains(bomId)) { path.remove(bomId); return; }
        visited.add(bomId);
        for (JsonNode line : node.path("bomItems")) if (!line.path("childBomId").isNull()) {
            Long child = id(line,"childBomId");
            require(nodes.containsKey(child), "子BOM版本缺失");
            same(nodes.get(child),"productMaterialId",id(line,"materialId"));
            walk(child,nodes,path,visited,depth+1);
        }
        path.remove(bomId);
    }

    private void material(JsonNode node) {
        id(node,"materialId"); text(node,"materialCode"); version(node,"version");
        require(node.path("quantityMode").isTextual() && node.path("issueStep").isNumber(),"缺少冻结计量规则");
        quantities.validateDefinition(frozenMaterial(node)); unit(node.path("unit"));
    }
    private void unit(JsonNode unit) {
        id(unit,"unitId"); text(unit,"unitCode"); text(unit,"unitName"); version(unit,"version");
        require(integer(unit,"precisionScale") >= 0 && integer(unit,"precisionScale") <= 4,"计量精度无效");
    }
    private MdmMaterial frozenMaterial(JsonNode node) {
        MdmMaterial material = new MdmMaterial();
        material.setQuantityMode(text(node,"quantityMode")); material.setIssueStep(decimal(node,"issueStep"));
        return material;
    }
    private JsonNode array(JsonNode node,String field,boolean nonempty) {
        JsonNode value=node.path(field);
        require(value.isArray() && (!nonempty || !value.isEmpty()),"冻结集合缺失："+field); return value;
    }
    private long id(JsonNode node,String field) {
        require(node.path(field).isIntegralNumber() && node.path(field).canConvertToLong() && node.path(field).longValue()>0,"冻结标识无效："+field);
        return node.path(field).longValue();
    }
    private void same(JsonNode node,String field,Long expected) {
        require(expected!=null && id(node,field)==expected,"冻结来源不一致："+field);
    }
    private int integer(JsonNode node,String field) {
        require(node.path(field).isIntegralNumber() && node.path(field).canConvertToInt(),"冻结整数无效："+field); return node.path(field).intValue();
    }
    private void version(JsonNode node,String field) { require(integer(node,field)>=0,"冻结版本无效："+field); }
    private BigDecimal decimal(JsonNode node,String field) {
        require(node.path(field).isNumber(),"冻结数量缺失："+field); return node.path(field).decimalValue();
    }
    private boolean hasText(JsonNode node,String field) { return node.path(field).isTextual() && !node.path(field).textValue().isBlank(); }
    private String text(JsonNode node,String field) { require(hasText(node,field),"冻结文本缺失："+field);return node.path(field).textValue(); }
    private void require(boolean condition,String message) { if(!condition)throw invalid(message); }
    private BusinessException invalid(String message) { return new BusinessException(ResultCode.BUSINESS_ERROR.getCode(),message); }
    public static String hash(String content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException("SHA-256不可用",e); }
    }
}
