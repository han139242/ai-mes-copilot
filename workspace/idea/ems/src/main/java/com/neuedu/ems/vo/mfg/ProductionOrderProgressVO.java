package com.neuedu.ems.vo.mfg;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 订单全过程的只读投影；阶段各带单位，不能把不同工序或不同物料相加为订单产量。 */
public record ProductionOrderProgressVO(OrderHeader order, Summary summary, List<Stage> stages,
        List<WorkOrderNode> workOrders, List<MaterialCoverage> materials, List<Document> documents,
        List<TimelineEvent> timeline, List<PlanActual> planActual, List<Dependency> dependencies,
        DataQuality dataQuality, LocalDateTime evaluatedAt,
        List<MaterialCoverage> preparationMaterials, List<MaterialCoverage> semiFinishedMaterials) {

    public record OrderHeader(Long productionOrderId, String productionOrderNo, String customerName,
            Long productId, String productCode, String productName, BigDecimal orderQuantity,
            String unit, LocalDate deliveryDate, String status, LocalDateTime createdTime, boolean rehearsal) {}

    public record Summary(String overallStatus, String currentStageCode, String currentStageName,
            BigDecimal productionPercent, BigDecimal shipmentPercent, BigDecimal releasedQuantity,
            BigDecimal inboundQuantity, BigDecimal shippedQuantity, BigDecimal maxAssemblableQuantity,
            LocalDateTime plannedFinishTime, LocalDateTime predictedFinishTime, String predictionBasis,
            int blockerCount, int riskCount, boolean rehearsal) {}

    public record Stage(String code, int seq, String name, String unit, String status,
            BigDecimal plannedQuantity, BigDecimal executedQuantity, BigDecimal reportedQualifiedQuantity,
            BigDecimal releasedQuantity, BigDecimal waitingAuditQuantity, BigDecimal waitingInspectionQuantity,
            BigDecimal defectQuantity, BigDecimal remainingQuantity, LocalDateTime plannedStart,
            LocalDateTime plannedEnd, LocalDateTime actualStart, LocalDateTime actualEnd,
            String actualTimeType, Owner owner, List<Stage> children, List<Blocker> blockers,
            List<Document> documents, List<String> notes) {}

    public record Owner(String teamName, String leaderName, String handlerName, String source) {}

    public record WorkOrderNode(Long workOrderId, String workOrderNo, String workOrderType,
            Long parentWorkOrderId, Long rootWorkOrderId, Long productId, Long materialId,
            String productCode, String productName, String status, BigDecimal plannedQuantity,
            BigDecimal reportedQuantity, BigDecimal releasedQuantity, BigDecimal inboundQuantity,
            String lineName, String bomVersion, String routeVersion, boolean frozen,
            LocalDateTime plannedStart, LocalDateTime plannedEnd, LocalDateTime actualStart,
            LocalDateTime actualEnd, String actualTimeType, Owner owner) {}

    /** requiredQuantity保留累计需求用于齐套计算；base/supplement为展示拆分，依据缺失时为null而非推测零值。 */
    public record MaterialCoverage(Long requirementId, Long workOrderId, String workOrderNo,
            Long materialId, String materialCode, String materialName, String materialType, String unit,
            BigDecimal requiredQuantity, BigDecimal issuedQuantity, BigDecimal reservedQuantity,
            BigDecimal freeAllocatedQuantity, BigDecimal publicAvailableQuantity,
            BigDecimal waitingInspectionQuantity, BigDecimal shortageQuantity, BigDecimal plannedSupplyQuantity,
            BigDecimal maxAssemblableQuantity, String status, List<Long> sourceWorkOrderIds,
            List<Document> sources, BigDecimal qualifiedStockQuantity,
            BigDecimal baseRequiredQuantity, BigDecimal supplementRequiredQuantity) {}

    public record Document(String type, Long docId, String docNo, String status, BigDecimal quantity,
            String unit, LocalDateTime time, String targetResource, Map<String, Object> query,
            Long workOrderId, String relation, String handlerName) {}

    public record Evidence(String label, String value, Document document) {}

    public record Impact(List<String> affectedWorkOrders, List<String> affectedOrders,
            BigDecimal blockedQuantity, String unit, String explanation) {}

    public record Action(String code, String label, String type, String targetResource,
            Map<String, Object> query, String prompt) {}

    public record Blocker(String code, String stageCode, String level, String type, String title,
            String reason, List<Evidence> evidence, Impact impact, List<Action> actions) {}

    public record TimelineEvent(LocalDateTime time, String text, String timeType, Document document) {}

    public record PlanActual(String key, String label, String group, Long workOrderId,
            LocalDateTime plannedStart, LocalDateTime plannedEnd, LocalDateTime actualStart,
            LocalDateTime actualEnd, String actualTimeType, String status, Document document) {}

    public record Dependency(String from, String to, String label, String status, String reason) {}

    public record DataQuality(String snapshotMode, boolean degraded, List<String> missing,
            List<String> notes, List<String> agingVerification, int documentCount) {}
}
