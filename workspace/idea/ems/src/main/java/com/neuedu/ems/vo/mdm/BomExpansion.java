package com.neuedu.ems.vo.mdm;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 需求分析与直接领料口径分别返回；本结果不预留库存、不创建工单或领料单。 */
public record BomExpansion(Long bomId, String bomCode, String bomVersion, LocalDate asOf,
                           BigDecimal quantity, String unitName, List<Node> tree, List<Demand> grossMaterials,
                           List<Demand> netMaterials, List<Demand> directItems,
                           List<ComponentDemand> components, int distinctMaterialCount,
                           long rawMaterialCount, String stockBasis) {
    public record Node(Long itemId, Long materialId, String materialCode, String materialName,
                       String materialType, String unitName, BigDecimal quantity,
                       Long childBomId, String childBomVersion, List<Node> children) {}
    public record Demand(Long materialId, String materialCode, String materialName,
                         String materialType, String unitName, String quantityMode,
                         BigDecimal theoreticalQuantity, BigDecimal executionQuantity,
                         BigDecimal roundingQuantity, BigDecimal issueStep) {}
    public record ComponentDemand(Long bomId, String bomCode, Long materialId, String materialName,
                                  BigDecimal requiredQuantity, BigDecimal availableQuantity,
                                  BigDecimal allocatedQuantity, BigDecimal productionQuantity) {}
}
