package com.neuedu.ems.service.mfg;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.entity.mdm.*;
import com.neuedu.ems.entity.mfg.MfgWorkOrder;
import com.neuedu.ems.entity.process.*;
import com.neuedu.ems.entity.qms.*;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.mdm.*;
import com.neuedu.ems.mapper.process.*;
import com.neuedu.ems.mapper.qms.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/** 下达事务内读取并锁定设计依据；旧版本不在此原地升级。 */
@Service
@RequiredArgsConstructor
public class WorkOrderSnapshotService {
    private final ObjectMapper json;
    private final ExecutionSnapshotPolicy policy;
    private final MdmProductMapper products;
    private final MdmBomMapper boms;
    private final MdmBomItemMapper bomItems;
    private final MdmMaterialMapper materials;
    private final MdmUnitMapper units;
    private final ProcessRouteMapper routes;
    private final ProcessRouteItemMapper routeItems;
    private final ProcessOperationMapper operations;
    private final MdmProductParameterMapper parameters;
    private final QmsInspectionSchemeMapper schemes;
    private final QmsInspectionSchemeItemMapper schemeItems;
    private final QmsInspectionStandardMapper standards;
    private final QmsInspectionItemMapper inspectionItems;

    @Transactional(propagation = Propagation.MANDATORY)
    public String freeze(MfgWorkOrder order, LocalDate effectiveDate, LocalDateTime frozenAt, Long operatorId) {
        require(order != null && order.getSnapshotJson() == null && order.getSnapshotHash() == null,
                "已有冻结依据不可覆盖，请按撤回重建或受控变更处理");
        require(effectiveDate != null && frozenAt != null,"缺少冻结适用日期或时点");
        require(operatorId!=null && operatorId>0,"冻结操作人不能为空");
        MdmProduct product=lock(products,"product_id",order.getProductId());
        require("ENABLE".equals(product.getStatus()),"产品未启用");
        ObjectNode root=json.createObjectNode();
        root.put("formatVersion",ExecutionSnapshotPolicy.FORMAT);
        root.put("workOrderId",order.getWorkOrderId()); root.put("productId",order.getProductId());
        root.put("bomId",order.getBomId()); root.put("routeId",order.getRouteId());
        root.put("plannedQuantity",order.getPlannedQuantity()); root.put("workOrderType",order.getWorkOrderType());
        root.put("productVersion",product.getVersion()); root.put("snapshotTime",frozenAt.toString());
        root.put("frozenBy",operatorId);
        root.put("effectiveDate",effectiveDate.toString());
        root.set("outputMaterial",material(product.getMaterialId()));

        Map<Long,ObjectNode> tree=new LinkedHashMap<>();
        collectBom(order.getBomId(),order.getProductId(),effectiveDate,tree,new HashSet<>(),0);
        ArrayNode frozenTree=root.putArray("bomTree");
        tree.values().forEach(frozenTree::add);
        root.set("bomItems",tree.get(order.getBomId()).path("bomItems").deepCopy());

        ProcessRoute route=lock(routes,"route_id",order.getRouteId());
        require(Objects.equals(route.getProductId(),order.getProductId()) && "RELEASED".equals(route.getStatus()),"工艺路线未发布或不属于本产品");
        root.put("routeVersion",route.getVersion());
        freezeRouteItems(root,route,order);
        freezeParameters(root,order);
        freezeInspectionSchemes(root,order);
        policy.validate(root,order);
        return root.toString();
    }

    /** 冻结工艺路线工序清单，逐条校验工序启用状态并记录是否为产出节点。 */
    private void freezeRouteItems(ObjectNode root,ProcessRoute route,MfgWorkOrder order) {
        List<ProcessRouteItem> steps=routeItems.selectList(new QueryWrapper<ProcessRouteItem>()
                .eq("route_id",route.getRouteId()).eq("deleted_flag",0).orderByAsc("sequence_no","route_item_id").last("FOR UPDATE"));
        ArrayNode frozenSteps=root.putArray("routeItems");
        for(int index=0;index<steps.size();index++) {
            ProcessRouteItem step=steps.get(index);
            ProcessOperation operation=lock(operations,"operation_id",step.getOperationId());
            require("ENABLE".equals(operation.getStatus()),"路线工序未启用");
            ObjectNode row=json.valueToTree(step);
            row.put("operationCode",operation.getOperationCode()); row.put("operationName",operation.getOperationName());
            row.put("operationVersion",operation.getVersion()); row.put("outputNode",index==steps.size()-1);
            frozenSteps.add(row);
        }
    }

    /** 冻结产品参数，只记录已确认的参数，必需参数未确认时拒绝下达。 */
    private void freezeParameters(ObjectNode root,MfgWorkOrder order) {
        ArrayNode frozenParameters=root.putArray("parameters");
        for(MdmProductParameter parameter:parameters.selectList(new QueryWrapper<MdmProductParameter>()
                .eq("product_id",order.getProductId()).eq("deleted_flag",0).orderByAsc("parameter_id").last("FOR UPDATE"))) {
            require(!Integer.valueOf(1).equals(parameter.getRequiredFlag()) || "CONFIRMED".equals(parameter.getStatus()),"必需产品参数尚未确认");
            if("CONFIRMED".equals(parameter.getStatus())) frozenParameters.add(json.valueToTree(parameter));
        }
    }

    /** 冻结检验方案及其检验标准项目，逐条校验项目、产品与工序的一致性。 */
    private void freezeInspectionSchemes(ObjectNode root,MfgWorkOrder order) {
        ArrayNode frozenSchemes=root.putArray("inspectionSchemes");
        for(QmsInspectionScheme scheme:schemes.selectList(new QueryWrapper<QmsInspectionScheme>()
                .eq("product_id",order.getProductId()).eq("status","RELEASED").eq("deleted_flag",0).orderByAsc("scheme_id").last("FOR UPDATE"))) {
            ObjectNode frozen=json.valueToTree(scheme);
            ArrayNode frozenStandards=frozen.putArray("standards");
            for(QmsInspectionSchemeItem item:schemeItems.selectList(new QueryWrapper<QmsInspectionSchemeItem>()
                    .eq("scheme_id",scheme.getSchemeId()).eq("deleted_flag",0).orderByAsc("sequence_no","id").last("FOR UPDATE"))) {
                frozenStandards.add(freezeSchemeItem(item,scheme,order));
            }
            frozenSchemes.add(frozen);
        }
    }

    /** 冻结单条检验方案项目，并带出检验标准与检验项目的口径字段。 */
    private ObjectNode freezeSchemeItem(QmsInspectionSchemeItem item,QmsInspectionScheme scheme,MfgWorkOrder order) {
        QmsInspectionStandard standard=lock(standards,"standard_id",item.getStandardId());
        QmsInspectionItem inspectionItem=lock(inspectionItems,"item_id",item.getItemId());
        require(Objects.equals(standard.getItemId(),item.getItemId()) && Objects.equals(standard.getProductId(),order.getProductId()),"检验标准项目或产品不匹配");
        require(standard.getOperationId()==null || Objects.equals(standard.getOperationId(),scheme.getOperationId()),"检验标准工序不匹配");
        ObjectNode frozenStandard=json.valueToTree(item);
        frozenStandard.put("schemeItemId",item.getId());
        frozenStandard.put("standardProductId",standard.getProductId());
        frozenStandard.put("standardOperationId",standard.getOperationId());
        frozenStandard.put("standardVersion",standard.getVersion()); frozenStandard.put("itemVersion",inspectionItem.getVersion());
        frozenStandard.put("itemCode",inspectionItem.getItemCode()); frozenStandard.put("itemName",inspectionItem.getItemName());
        frozenStandard.put("inspectionMethod",inspectionItem.getInspectionMethod());
        frozenStandard.put("standardValue",standard.getStandardValue()); frozenStandard.put("upperLimit",standard.getUpperLimit());
        frozenStandard.put("lowerLimit",standard.getLowerLimit()); frozenStandard.put("judgementRule",standard.getJudgementRule());
        frozenStandard.put("confirmationStatus",standard.getConfirmationStatus()); frozenStandard.put("sourceReference",standard.getSourceReference());
        frozenStandard.set("unit",inspectionItem.getUnitId()==null ? json.nullNode() : unit(inspectionItem.getUnitId()));
        return frozenStandard;
    }

    private void collectBom(Long bomId,Long expectedProduct,LocalDate date,Map<Long,ObjectNode> tree,Set<Long> path,int depth) {
        require(depth<=32 && !path.contains(bomId),"BOM循环或层级超限");
        if(tree.containsKey(bomId)) {
            require(tree.get(bomId).path("productId").asLong()==expectedProduct,"子BOM产品不匹配"); return;
        }
        path.add(bomId);
        MdmBom bom=lock(boms,"bom_id",bomId);
        require(Objects.equals(bom.getProductId(),expectedProduct) && "RELEASED".equals(bom.getStatus()),"BOM产品不匹配或尚未发布");
        require(bom.getEffectiveDate()!=null && !bom.getEffectiveDate().isAfter(date)
                && (bom.getEffectiveTo()==null || !bom.getEffectiveTo().isBefore(date)),"BOM不在适用有效期");
        MdmProduct product=lock(products,"product_id",bom.getProductId());
        require("ENABLE".equals(product.getStatus()),"子BOM产品未启用");
        ObjectNode node=json.valueToTree(bom);
        node.put("productMaterialId",product.getMaterialId());
        tree.put(bomId,node);
        ArrayNode items=node.putArray("bomItems");
        collectBomItems(bomId,date,tree,path,depth,items);
        path.remove(bomId);
    }

    /** 冻结一层 BOM 的直接子项，遇到子 BOM 时递归收集下层节点。 */
    private void collectBomItems(Long bomId,LocalDate date,Map<Long,ObjectNode> tree,Set<Long> path,int depth,ArrayNode items) {
        for(MdmBomItem item:bomItems.selectList(new QueryWrapper<MdmBomItem>()
                .eq("bom_id",bomId).eq("deleted_flag",0).orderByAsc("sequence_no","bom_item_id").last("FOR UPDATE"))) {
            require(item.getConsumptionOperationId()!=null,"BOM尚未绑定投料工序，请新建有效版本补齐");
            ProcessOperation operation=lock(operations,"operation_id",item.getConsumptionOperationId());
            require("ENABLE".equals(operation.getStatus()),"投料工序未启用");
            ObjectNode line=json.valueToTree(item);
            if(item.getChildBomId()==null) line.putNull("childBomId");
            line.put("lossRate",item.getLossRate()==null? BigDecimal.ZERO:item.getLossRate());
            line.set("material",material(item.getMaterialId()));
            items.add(line);
            if(item.getChildBomId()!=null) {
                MdmBom child=lock(boms,"bom_id",item.getChildBomId());
                collectBom(child.getBomId(),child.getProductId(),date,tree,path,depth+1);
            }
        }
    }

    private ObjectNode material(Long id) {
        MdmMaterial material=lock(materials,"material_id",id);
        require("ENABLE".equals(material.getStatus()),"冻结物料未启用");
        ObjectNode node=json.valueToTree(material); node.set("unit",unit(material.getUnitId())); return node;
    }
    private ObjectNode unit(Long id) {
        MdmUnit unit=lock(units,"unit_id",id);
        require("ENABLE".equals(unit.getStatus()),"冻结计量单位未启用"); return json.valueToTree(unit);
    }
    private <T> T lock(BaseMapper<T> mapper,String key,Long id) {
        require(id!=null && id>0,"冻结来源标识缺失："+key);
        T result=mapper.selectOne(new QueryWrapper<T>().eq(key,id).eq("deleted_flag",0).last("FOR UPDATE"));
        require(result!=null,"冻结来源不存在或已删除："+key); return result;
    }
    private void require(boolean condition,String message) { if(!condition)throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(),message); }
}
