package com.neuedu.ems.service.impl.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.neuedu.ems.entity.ai.AiBlock;
import com.neuedu.ems.entity.ai.AiColumn;
import com.neuedu.ems.entity.ai.AiQueryResult;
import com.neuedu.ems.entity.mdm.MdmMaterial;
import com.neuedu.ems.entity.mdm.MdmProduct;
import com.neuedu.ems.entity.wms.WmsWarehouse;
import com.neuedu.ems.exception.AiServiceException;
import com.neuedu.ems.mapper.ai.AiDataQueryMapper;
import com.neuedu.ems.mapper.mdm.MdmMaterialMapper;
import com.neuedu.ems.mapper.mdm.MdmProductMapper;
import com.neuedu.ems.mapper.wms.WmsWarehouseMapper;
import com.neuedu.ems.service.ai.AiDataQueryService;
import com.neuedu.ems.vo.mfg.ScheduleSimulationComparison;
import com.neuedu.ems.vo.mdm.BomExpansion;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * AI 结构化数据查询服务实现。
 * 所有结论都由数据库真实数据直接计算，不经过大模型，保证与人工查询逐字段一致。
 */
@Service
public class AiDataQueryServiceImpl implements AiDataQueryService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.neuedu.ems.service.sys.BusinessNumberService documentNumbers;

    private String currentDocumentNumber(String value) {
        String normalized = value.trim();
        return documentNumbers == null ? normalized : documentNumbers.canonical(normalized);
    }


    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int MAX_DETAIL_ROWS = 200;
    private static final String IN = "IN";
    private static final String OUT = "OUT";

    /**
     * 仓库类型到中文名称的展示映射。
     */
    private static final Map<String, String> WAREHOUSE_TYPE_NAMES = Map.of(
            "RAW", "原料仓",
            "LINE_SIDE", "线边仓",
            "SEMI_FINISHED", "半成品仓",
            "FINISHED", "成品仓",
            "DEFECTIVE", "不合格隔离仓");

    /**
     * 工单状态中文名称。
     */
    private static final Map<String, String> WORK_ORDER_STATUS_NAMES = Map.of(
            "DRAFT", "新建",
            "APPROVED", "已审核",
            "RELEASED", "已下达",
            "RUNNING", "生产中",
            "FINISHED", "已完成",
            "CLOSED", "已关闭");

    @org.springframework.beans.factory.annotation.Autowired
    private com.neuedu.ems.service.ai.AiLineCalendarService lineCalendars;
    @org.springframework.beans.factory.annotation.Autowired
    private com.neuedu.ems.service.mdm.MaterialQuantityPolicy quantityPolicy;

    private final AiDataQueryMapper queryMapper;
    private final MdmMaterialMapper materialMapper;
    private final MdmProductMapper productMapper;
    private final WmsWarehouseMapper warehouseMapper;

    /**
     * 排程推演复用排程服务同一套班次与产能算法。
     * 用懒代理注入：排程服务本身依赖 AiService，直接构造器注入会形成循环依赖。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private com.neuedu.ems.service.mfg.MfgScheduleScenarioService scheduleScenarioService;

    /**
     * 低于安全库存的查询复用自动化规则同一套阈值与可用量口径，避免页面、规则和 AI 三处算出三个数。
     * 用字段注入并容许缺失：只读查询服务在单测里可以直接 new 出来。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.neuedu.ems.service.ai.AiAutomationService automationService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.neuedu.ems.service.mfg.ResourceConflictService resourceConflictService;

    /**
     * BOM 用量分析复用主数据同一套递归展开，避免 AI 算出与工单领料不一致的数量。
     * 字段注入并容许缺失：只读查询服务在单测里可以直接 new 出来。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.neuedu.ems.service.mdm.BomExpansionService bomExpansionService;

    public AiDataQueryServiceImpl(AiDataQueryMapper queryMapper, MdmMaterialMapper materialMapper,
                                  MdmProductMapper productMapper, WmsWarehouseMapper warehouseMapper) {
        this.queryMapper = queryMapper;
        this.materialMapper = materialMapper;
        this.productMapper = productMapper;
        this.warehouseMapper = warehouseMapper;
    }

    @Override
    public AiQueryResult queryInventoryByMaterial(String keyword, String warehouseType,
                                                  LocalDate dateFrom, LocalDate dateTo) {
        LocalDate[] range = normalizeRange(dateFrom, dateTo, 30);
        LocalDateTime start = range[0].atStartOfDay();
        LocalDateTime end = range[1].atTime(LocalTime.MAX);
        List<Long> materialIds = resolveMaterialIds(keyword);
        if (materialIds != null && materialIds.isEmpty()) {
            return emptyResult("物料收发存",
                    "未找到与「" + keyword + "」匹配的物料，请确认物料名称或编码。",
                    inventoryFlowColumns());
        }
        String normalizedWarehouseType = normalizeWarehouseType(warehouseType);
        List<Map<String, Object>> rawRows = queryMapper.selectInventoryFlow(materialIds, normalizedWarehouseType, start, end);

        // 主表按物料汇总，明细区块按仓库展开，保证“品类维”和“仓库维”都能逐字段核对。
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        List<Map<String, Object>> detailRows = new ArrayList<>();
        for (Map<String, Object> row : rawRows) {
            mergeInventoryFlowRow(row, merged, detailRows);
        }

        List<Map<String, Object>> rows = merged.values().stream()
                .map(this::scaleSummaryRow)
                .toList();

        List<AiBlock> blocks = new ArrayList<>();
        if (!detailRows.isEmpty()) {
            blocks.add(new AiBlock("按仓库明细", inventoryFlowDetailColumns(), detailRows, null));
            if (countMaterials(rows) == 1) {
                blocks.add(new AiBlock("期末库存", List.of(), List.of(),
                        barChart("期末库存", rows.stream().map(item -> text(item.get("materialName"))).toList(),
                                "期末库存", rows.stream().map(item -> item.get("closingQuantity")).toList())));
            }
        }

        String title = (StringUtils.hasText(keyword) ? keyword : "全部物料")
                + (StringUtils.hasText(normalizedWarehouseType) ? "（" + warehouseTypeName(normalizedWarehouseType) + "）" : "")
                + "收发存";
        String summary = buildInventoryFlowSummary(rows, range[0], range[1]);
        return new AiQueryResult(title, summary, inventoryFlowColumns(), rows, blocks);
    }

    /** 把一条库存流水行累加到按物料汇总表，并追加一条按仓库展开的明细行。 */
    private void mergeInventoryFlowRow(Map<String, Object> row, Map<String, Map<String, Object>> merged,
                                       List<Map<String, Object>> detailRows) {
        String materialCode = text(row.get("materialCode"));
        Map<String, Object> summary = merged.computeIfAbsent(materialCode,
                code -> newInventorySummaryRow(code, row));
        BigDecimal opening = decimal(row.get("openingQuantity"));
        BigDecimal inbound = decimal(row.get("inboundQuantity"));
        BigDecimal outbound = decimal(row.get("outboundQuantity"));
        BigDecimal closing = decimal(row.get("closingQuantity"));
        summary.put("openingQuantity", decimal(summary.get("openingQuantity")).add(opening));
        summary.put("inboundQuantity", decimal(summary.get("inboundQuantity")).add(inbound));
        summary.put("outboundQuantity", decimal(summary.get("outboundQuantity")).add(outbound));
        summary.put("closingQuantity", decimal(summary.get("closingQuantity")).add(closing));
        summary.put("warehouseCount", (Integer) summary.get("warehouseCount") + 1);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("materialCode", materialCode);
        detail.put("materialName", text(row.get("materialName")));
        detail.put("warehouseName", text(row.get("warehouseName")));
        detail.put("warehouseTypeName", warehouseTypeName(text(row.get("warehouseType"))));
        detail.put("openingQuantity", scale(opening));
        detail.put("inboundQuantity", scale(inbound));
        detail.put("outboundQuantity", scale(outbound));
        detail.put("closingQuantity", scale(closing));
        detail.put("unitName", text(row.get("unitName")));
        detailRows.add(detail);
    }

    /** 初始化按物料汇总行，各数量列先置 0。 */
    private Map<String, Object> newInventorySummaryRow(String materialCode, Map<String, Object> row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("materialCode", materialCode);
        item.put("materialName", text(row.get("materialName")));
        item.put("unitName", text(row.get("unitName")));
        item.put("openingQuantity", BigDecimal.ZERO);
        item.put("inboundQuantity", BigDecimal.ZERO);
        item.put("outboundQuantity", BigDecimal.ZERO);
        item.put("closingQuantity", BigDecimal.ZERO);
        item.put("warehouseCount", 0);
        return item;
    }

    /** 汇总行的四个数量列统一保留两位小数。 */
    private Map<String, Object> scaleSummaryRow(Map<String, Object> item) {
        item.put("openingQuantity", scale(decimal(item.get("openingQuantity"))));
        item.put("inboundQuantity", scale(decimal(item.get("inboundQuantity"))));
        item.put("outboundQuantity", scale(decimal(item.get("outboundQuantity"))));
        item.put("closingQuantity", scale(decimal(item.get("closingQuantity"))));
        return item;
    }

    @Override
    public AiQueryResult queryInventoryByWarehouse(String warehouseCode, String warehouseType, String materialKeyword) {
        String normalizedCode = StringUtils.hasText(warehouseCode) ? warehouseCode.trim().toUpperCase(Locale.ROOT) : null;
        String normalizedType = normalizeWarehouseType(warehouseType);
        List<Long> materialIds = resolveMaterialIds(materialKeyword);
        if (materialIds != null && materialIds.isEmpty()) {
            return emptyResult("库存明细",
                    "未找到与「" + materialKeyword + "」匹配的物料，请确认物料名称或编码。",
                    inventoryDetailColumns());
        }
        List<Map<String, Object>> rawRows = queryMapper.selectInventoryDetail(normalizedCode, normalizedType, materialIds);
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal totalAvailable = BigDecimal.ZERO;
        for (Map<String, Object> raw : rawRows) {
            rows.add(inventoryDetailRow(raw));
            totalAvailable = totalAvailable.add(decimal(raw.get("availableQuantity")));
        }

        String warehouseName = warehouseLabel(normalizedCode, normalizedType);
        String keywordText = StringUtils.hasText(materialKeyword) ? materialKeyword : "全部物料";
        String title = warehouseName + " · " + keywordText + " 库存明细";
        String summary = buildInventoryDetailSummary(rows, totalAvailable);

        List<AiBlock> blocks = new ArrayList<>();
        if (!rows.isEmpty() && countMaterials(rows) == 1) {
            Map<String, BigDecimal> byWarehouse = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                byWarehouse.merge(text(row.get("warehouseName")), decimal(row.get("availableQuantity")), BigDecimal::add);
            }
            blocks.add(new AiBlock("仓库可用量分布", List.of(), List.of(),
                    barChart("仓库可用量", new ArrayList<>(byWarehouse.keySet()),
                            "可用数量", new ArrayList<>(byWarehouse.values()))));
        }
        return new AiQueryResult(title, summary, inventoryDetailColumns(), rows, blocks);
    }

    @Override
    public AiQueryResult queryInventoryBelowSafety(String warehouseType, String materialKeyword) {
        String normalizedType = StringUtils.hasText(warehouseType)
                ? warehouseType.trim().toUpperCase(Locale.ROOT) : null;
        if (automationService == null) {
            throw new AiServiceException("安全库存预警服务未就绪，请稍后重试");
        }
        List<Map<String, Object>> rawRows = automationService.lowStockMaterials(normalizedType, materialKeyword);
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal totalShortage = BigDecimal.ZERO;
        for (Map<String, Object> raw : rawRows) {
            rows.add(belowSafetyRow(raw));
            totalShortage = totalShortage.add(decimal(raw.get("shortageQuantity")));
        }
        String typeText = StringUtils.hasText(normalizedType)
                ? warehouseTypeName(normalizedType) : "全部仓库";
        String keywordText = StringUtils.hasText(materialKeyword) ? materialKeyword : "全部物料";
        String title = typeText + " · " + keywordText + " 低于安全库存";
        String summary = buildBelowSafetySummary(rows, totalShortage, normalizedType);
        List<AiBlock> blocks = new ArrayList<>();
        if (!rows.isEmpty() && countMaterials(rows) == 1) {
            Map<String, BigDecimal> byMaterial = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                byMaterial.merge(text(row.get("materialName")), decimal(row.get("shortageQuantity")),
                        BigDecimal::add);
            }
            List<String> names = new ArrayList<>(byMaterial.keySet());
            List<BigDecimal> gaps = new ArrayList<>();
            for (String name : names) {
                gaps.add(byMaterial.get(name));
            }
            blocks.add(new AiBlock("各物料缺口数量", List.of(), List.of(),
                    barChart("缺口数量", names, "缺口数量", gaps)));
        }
        return new AiQueryResult(title, summary, belowSafetyColumns(), rows, blocks);
    }

    /** 把一条缺口记录转换为展示行。 */
    private Map<String, Object> belowSafetyRow(Map<String, Object> raw) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("materialCode", text(raw.get("materialCode")));
        row.put("materialName", text(raw.get("materialName")));
        row.put("warehouseTypeName", warehouseTypeName(text(raw.get("warehouseType"))));
        row.put("availableQuantity", scale(decimal(raw.get("availableQuantity"))));
        row.put("thresholdQuantity", scale(decimal(raw.get("thresholdQuantity"))));
        row.put("shortageQuantity", scale(decimal(raw.get("shortageQuantity"))));
        row.put("ruleName", text(raw.get("ruleName")));
        row.put("ruleStatusName", ruleStatusName(text(raw.get("ruleStatus"))));
        return row;
    }

    /** 低于安全库存的汇总文案：说清口径、条数、缺口合计与规则状态。 */
    private String buildBelowSafetySummary(List<Map<String, Object>> rows, BigDecimal totalShortage,
                                           String normalizedType) {
        String scope = StringUtils.hasText(normalizedType)
                ? warehouseTypeName(normalizedType) : "已配置规则的仓库类型";
        if (rows.isEmpty()) {
            return scope + "下当前没有低于安全库存的物料。阈值取自安全库存预警规则，"
                    + "可用量按该仓库类型下全部仓库的可用库存合计；这是「没有触发预警」，"
                    + "不等于「所有物料都充足」。";
        }
        long materialCount = countMaterials(rows);
        boolean paused = rows.stream()
                .anyMatch(row -> !"启用".equals(text(row.get("ruleStatusName"))));
        String statusNote = paused
                ? "注意其中有规则未处于启用状态，结论按现有阈值算出，规则暂停时不会自动生成风险与补货建议。"
                : "规则处于启用状态，会按同一口径自动生成风险与补货建议。";
        String quantity = materialCount == 1 ? "，缺口数量合计 " + num(totalShortage)
                : "，各物料缺口请查看明细";
        return scope + "共 " + rows.size() + " 条物料低于安全库存，涉及 " + materialCount
                + " 种物料" + quantity + "。阈值取自安全库存预警规则，"
                + "可用量按该仓库类型下全部仓库的可用库存合计。" + statusNote;
    }

    /** 规则状态中文名。 */
    private String ruleStatusName(String status) {
        return switch (status == null ? "" : status) {
            case "ENABLED" -> "启用";
            case "PAUSED" -> "暂停";
            case "DRAFT" -> "草稿";
            case "DISABLED" -> "停用";
            default -> StringUtils.hasText(status) ? status : "未知";
        };
    }

    private List<AiColumn> belowSafetyColumns() {
        return List.of(
                new AiColumn("materialCode", "物料编码"),
                new AiColumn("materialName", "物料名称"),
                new AiColumn("warehouseTypeName", "仓库类型"),
                new AiColumn("availableQuantity", "可用数量"),
                new AiColumn("thresholdQuantity", "安全库存"),
                new AiColumn("shortageQuantity", "缺口数量"),
                new AiColumn("ruleName", "预警规则"),
                new AiColumn("ruleStatusName", "规则状态"));
    }

    /** 仓库展示名，未指定仓库时代表全部。 */
    private String warehouseLabel(String normalizedCode, String normalizedType) {
        if (StringUtils.hasText(normalizedCode)) {
            return resolveWarehouseName(normalizedCode);
        }
        if (StringUtils.hasText(normalizedType)) {
            return warehouseTypeName(normalizedType);
        }
        return "全部仓库";
    }

    /** 把一条库存明细原始行转换为展示行。 */
    private Map<String, Object> inventoryDetailRow(Map<String, Object> raw) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("materialCode", text(raw.get("materialCode")));
        row.put("materialName", text(raw.get("materialName")));
        row.put("warehouseName", text(raw.get("warehouseName")));
        row.put("locationCode", text(raw.get("locationCode")));
        row.put("batchNo", text(raw.get("batchNo")));
        row.put("qualityStatus", qualityStatusName(text(raw.get("qualityStatus"))));
        row.put("availableQuantity", scale(decimal(raw.get("availableQuantity"))));
        row.put("lockedQuantity", scale(decimal(raw.get("lockedQuantity"))));
        row.put("inspectionQuantity", scale(decimal(raw.get("inspectionQuantity"))));
        row.put("totalQuantity", scale(decimal(raw.get("totalQuantity"))));
        row.put("workOrderNo", text(raw.get("workOrderNo")));
        row.put("unitName", text(raw.get("unitName")));
        return row;
    }

    /** 库存明细的汇总文案，无数据时给出提示。 */
    private String buildInventoryDetailSummary(List<Map<String, Object>> rows, BigDecimal totalAvailable) {
        if (rows.isEmpty()) {
            return "当前没有符合条件的库存记录。";
        }
        long materialCount = countMaterials(rows);
        if (materialCount == 1) {
            String unit = text(rows.get(0).get("unitName"));
            return "共 " + rows.size() + " 条库存记录，可用数量合计 " + num(totalAvailable)
                    + (StringUtils.hasText(unit) ? " " + unit : "") + "，涉及 1 种物料。";
        }
        return "共 " + rows.size() + " 条库存记录，涉及 " + materialCount + " 种物料；各物料可用量请查看明细。";
    }

    private long countMaterials(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> text(row.get("materialCode"))).distinct().count();
    }

    @Override
    public AiQueryResult queryWorkOrderDetail(String workOrderNo) {
        if (!StringUtils.hasText(workOrderNo)) {
            throw new AiServiceException("工单号不能为空");
        }
        Map<String, Object> header = queryMapper.selectWorkOrderHeader(currentDocumentNumber(workOrderNo));
        if (header == null || header.isEmpty()) {
            return emptyResult("工单全息",
                    "未找到工单「" + workOrderNo.trim() + "」，请确认工单号。",
                    workOrderHeaderColumns());
        }
        Long workOrderId = toLong(header.get("workOrderId"));

        Map<String, Object> mainRow = new LinkedHashMap<>();
        mainRow.put("workOrderNo", text(header.get("workOrderNo")));
        mainRow.put("productCode", text(header.get("productCode")));
        mainRow.put("productName", text(header.get("productName")));
        mainRow.put("workOrderType", workOrderTypeName(text(header.get("workOrderType"))));
        mainRow.put("plannedQuantity", scale(decimal(header.get("plannedQuantity"))));
        mainRow.put("completedQuantity", scale(decimal(header.get("completedQuantity"))));
        mainRow.put("status", workOrderStatusName(text(header.get("status"))));
        mainRow.put("parentWorkOrderNo", text(header.get("parentWorkOrderNo")));
        mainRow.put("rootWorkOrderNo", text(header.get("rootWorkOrderNo")));
        mainRow.put("createdTime", formatDateTime(header.get("createdTime")));

        List<Map<String, Object>> materialRows = queryMapper.selectWorkOrderMaterials(workOrderId);
        List<Map<String, Object>> reportRows = queryMapper.selectWorkOrderReports(workOrderId);
        List<Map<String, Object>> inspectionRows = queryMapper.selectWorkOrderInspections(workOrderId);
        List<Map<String, Object>> lotRows = queryMapper.selectWorkOrderLots(workOrderId);

        List<AiBlock> blocks = new ArrayList<>();
        blocks.add(new AiBlock("领料明细", materialColumns(), limit(materialRows), null));
        blocks.add(new AiBlock("报工明细", reportColumns(), limit(reportRows), null));
        blocks.add(new AiBlock("质检单", inspectionColumns(), limit(inspectionRows), null));
        blocks.add(new AiBlock("产品批次", productLotColumns(), limit(lotRows), null));

        String summary = "工单 " + text(header.get("workOrderNo")) + "（" + text(header.get("productName")) + "）"
                + "计划 " + num(header.get("plannedQuantity"))
                + "、完成 " + num(header.get("completedQuantity"))
                + "，状态 " + workOrderStatusName(text(header.get("status"))) + "；"
                + "用料 " + materialRows.size() + " 项，按物料查看已发与缺料；"
                + "报工 " + reportRows.size() + " 条，按工序查看合格与不良；"
                + "质检 " + inspectionRows.size() + " 单，产出批次 " + lotRows.size() + " 个。";
        return new AiQueryResult("工单全息 " + text(header.get("workOrderNo")), summary,
                workOrderHeaderColumns(), List.of(mainRow), blocks);
    }

    @Override
    public AiQueryResult queryWorkOrderReadiness(String workOrderNo) {
        if (!StringUtils.hasText(workOrderNo)) {
            throw new AiServiceException("工单号不能为空");
        }
        Map<String, Object> header = queryMapper.selectWorkOrderHeader(currentDocumentNumber(workOrderNo));
        if (header == null || header.isEmpty()) {
            return emptyResult("开工条件核查",
                    "未找到工单「" + workOrderNo.trim() + "」，请确认工单号。",
                    readinessColumns());
        }
        Long workOrderId = toLong(header.get("workOrderId"));
        Long productId = toLong(header.get("productId"));
        String status = text(header.get("status"));
        String workOrderNoText = text(header.get("workOrderNo"));

        List<Map<String, Object>> checks = new ArrayList<>();
        List<String> blockers = new ArrayList<>();

        // 一、制造准备闸门：产品存在、已启用、且关联了成品物料。
        // 与 ManufacturingPreparationGate.assertExecutionSupported 同源，只是不抛异常。
        checkManufacturingPreparation(productId, checks, blockers);
        // 二、单据状态必须是已下达。
        checkDocumentStatus(status, checks, blockers);
        // 三到五、用料需求、齐套情况（组件件与库存件分开说，因为处置办法不同）。
        List<Map<String, Object>> materials = queryMapper.selectWorkOrderReadinessMaterials(workOrderId);
        checkMaterialRequirement(materials, checks, blockers);
        ShortageRows shortageRows = collectShortages(materials);
        checkKitting(shortageRows, checks, blockers);
        // 六到七、工序任务是否已生成、是否全部待开始。
        checkTaskReadiness(workOrderId, checks, blockers);

        List<AiBlock> blocks = new ArrayList<>();
        if (!shortageRows.componentRows().isEmpty()) {
            blocks.add(new AiBlock("待组件工单产出的物料", componentColumns(), shortageRows.componentRows(), null));
        }
        if (!shortageRows.stockRows().isEmpty()) {
            blocks.add(new AiBlock("等待仓库发料的物料", componentColumns(), shortageRows.stockRows(), null));
        }
        boolean canStart = blockers.isEmpty();
        return new AiQueryResult("开工条件核查 " + workOrderNoText,
                readinessSummary(workOrderNoText, canStart, blockers, shortageRows.componentNames(),
                        shortageRows.draftComponentNos(), shortageRows.stockShortNames()),
                readinessColumns(), checks, blocks);
    }

    /** 缺料明细的五份并行清单，供结论文案与明细区块共用。 */
    private record ShortageRows(List<Map<String, Object>> componentRows, List<Map<String, Object>> stockRows,
                                List<String> componentNames, List<String> draftComponentNos,
                                List<String> stockShortNames) {
    }

    /** 一、制造准备闸门：产品存在、已启用、且关联了成品物料。 */
    private void checkManufacturingPreparation(Long productId, List<Map<String, Object>> checks,
                                               List<String> blockers) {
        MdmProduct product = productId == null ? null : productMapper.selectById(productId);
        if (product == null || !Integer.valueOf(0).equals(product.getDeletedFlag())) {
            checks.add(readinessRow("制造准备", false, "产品不存在或已删除", ""));
            blockers.add("产品不存在或已删除");
        } else if (!"ENABLE".equals(product.getStatus())) {
            checks.add(readinessRow("制造准备", false, "产品未启用", "产品状态为 " + text(product.getStatus())));
            blockers.add("产品未启用");
        } else if (product.getMaterialId() == null) {
            checks.add(readinessRow("制造准备", false, "产品未关联成品物料", ""));
            blockers.add("产品未关联成品物料");
        } else {
            checks.add(readinessRow("制造准备", true, "产品已启用且已关联成品物料",
                    text(product.getProductName())));
        }
    }

    /** 二、单据状态必须是已下达。 */
    private void checkDocumentStatus(String status, List<Map<String, Object>> checks, List<String> blockers) {
        boolean released = "RELEASED".equals(status);
        checks.add(readinessRow("单据状态", released,
                released ? "已下达，可以开工" : "当前状态为" + workOrderStatusName(status) + "，只有已下达的工单可以开工",
                ""));
        if (!released) {
            blockers.add("工单状态为" + workOrderStatusName(status) + "，不是已下达");
        }
    }

    /** 三、工单必须有用料需求。 */
    private void checkMaterialRequirement(List<Map<String, Object>> materials, List<Map<String, Object>> checks,
                                          List<String> blockers) {
        boolean hasRequirement = !materials.isEmpty();
        checks.add(readinessRow("用料需求", hasRequirement,
                hasRequirement ? "共 " + materials.size() + " 项用料" : "工单没有有效用料需求，不能开工", ""));
        if (!hasRequirement) {
            blockers.add("工单没有有效用料需求");
        }
    }

    /** 把缺料的用料需求分成组件件与库存件两组。 */
    private ShortageRows collectShortages(List<Map<String, Object>> materials) {
        List<Map<String, Object>> componentRows = new ArrayList<>();
        List<Map<String, Object>> stockRows = new ArrayList<>();
        List<String> componentNames = new ArrayList<>();
        List<String> draftComponentNos = new ArrayList<>();
        List<String> stockShortNames = new ArrayList<>();
        for (Map<String, Object> row : materials) {
            BigDecimal required = decimal(row.get("requiredQuantity"));
            BigDecimal issued = decimal(row.get("issuedQuantity"));
            BigDecimal shortage = required.subtract(issued);
            if (shortage.signum() <= 0) {
                continue;
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("materialCode", text(row.get("materialCode")));
            detail.put("materialName", text(row.get("materialName")));
            detail.put("requiredQuantity", scale(required));
            detail.put("issuedQuantity", scale(issued));
            detail.put("shortageQuantity", scale(shortage));
            detail.put("unitName", text(row.get("unitName")));
            if ("COMPONENT_WORK_ORDER".equals(text(row.get("supplyType")))) {
                addComponentShortage(row, detail, shortage, componentRows, componentNames, draftComponentNos);
            } else {
                detail.put("sourceWorkOrderNo", "");
                detail.put("sourceWorkOrderStatus", "");
                detail.put("status", "等待仓库发料");
                stockRows.add(detail);
                stockShortNames.add(text(row.get("materialName")) + " 缺 " + num(shortage) + text(row.get("unitName")));
            }
        }
        return new ShortageRows(componentRows, stockRows, componentNames, draftComponentNos, stockShortNames);
    }

    /** 组件件缺料：补充来源工单信息，草稿状态的来源工单单独记下。 */
    private void addComponentShortage(Map<String, Object> row, Map<String, Object> detail, BigDecimal shortage,
                                      List<Map<String, Object>> componentRows, List<String> componentNames,
                                      List<String> draftComponentNos) {
        String subNo = text(row.get("sourceWorkOrderNo"));
        String subStatus = text(row.get("sourceWorkOrderStatus"));
        detail.put("sourceWorkOrderNo", subNo);
        detail.put("sourceWorkOrderStatus", subStatus);
        detail.put("status", "待组件工单产出");
        componentRows.add(detail);
        componentNames.add(text(row.get("materialName")) + " " + num(shortage) + text(row.get("unitName")));
        if ("DRAFT".equals(subStatus) && !subNo.isBlank()) {
            draftComponentNos.add(subNo);
        }
    }

    /** 四、物料是否已齐套。 */
    private void checkKitting(ShortageRows shortageRows, List<Map<String, Object>> checks, List<String> blockers) {
        boolean kitted = shortageRows.componentRows().isEmpty() && shortageRows.stockRows().isEmpty();
        int shortageCount = shortageRows.componentRows().size() + shortageRows.stockRows().size();
        checks.add(readinessRow("物料齐套", kitted,
                kitted ? "全部用料已足额发到本工单" : "还有 " + shortageCount + " 种物料未足额发到本工单",
                ""));
        if (!kitted) {
            blockers.add("物料未齐套");
        }
    }

    /** 六到七、工序任务是否已生成、是否全部待开始。 */
    private void checkTaskReadiness(Long workOrderId, List<Map<String, Object>> checks, List<String> blockers) {
        Map<String, Object> taskStats = queryMapper.selectWorkOrderTaskStats(workOrderId, "WAIT_START");
        long taskCount = taskStatValue(taskStats, "taskCount");
        long nonWaiting = taskStatValue(taskStats, "nonWaitingTaskCount");
        boolean hasTask = taskCount > 0;
        checks.add(readinessRow("工序任务", hasTask,
                hasTask ? "已生成 " + taskCount + " 道工序任务" : "尚未生成工序任务，不能开工", ""));
        if (!hasTask) {
            blockers.add("工单尚未生成工序任务");
            return;
        }
        boolean tasksReady = nonWaiting == 0;
        checks.add(readinessRow("任务状态", tasksReady,
                tasksReady ? "全部工序任务待开始" : nonWaiting + " 道工序任务不是待开始，请先修正",
                ""));
        if (!tasksReady) {
            blockers.add("存在非待开始工序任务");
        }
    }

    /** 读取工序任务统计中的整数值，统计缺失时按 0 处理。 */
    private long taskStatValue(Map<String, Object> taskStats, String key) {
        if (taskStats == null) {
            return 0L;
        }
        return toLong(taskStats.get(key));
    }

    /**
     * 开工核查的一句话结论：先说能不能开，再说卡在哪、该按什么顺序解。
     *
     * <p>文案刻意写成「先做什么、再做什么」，因为用户要的不是一句「不满足条件」，
     * 而是拿到下一步能点的动作。</p>
     */
    private String readinessSummary(String workOrderNo, boolean canStart, List<String> blockers,
                                    List<String> componentNames, List<String> draftComponentNos,
                                    List<String> stockShortNames) {
        if (canStart) {
            return "工单 " + workOrderNo + " 的前置条件全部满足，可以开始生产。";
        }
        StringBuilder summary = new StringBuilder("工单 ").append(workOrderNo).append(" 当前不能直接开工。");
        if (!componentNames.isEmpty()) {
            summary.append("组件件缺 ").append(String.join("、", componentNames)).append("；");
            if (!draftComponentNos.isEmpty()) {
                summary.append("对应组件工单 ").append(String.join("、", draftComponentNos))
                        .append(" 目前还是草稿状态，尚未下达；");
            }
        }
        if (!stockShortNames.isEmpty()) {
            summary.append("库存件").append(String.join("、", stockShortNames)).append("，需要仓库发料；");
        }
        summary.append("另外还卡在：").append(String.join("；", blockers)).append("。");
        summary.append("建议先").append(readinessSuggestion(componentNames, draftComponentNos, stockShortNames))
                .append("，完成后复核开工条件。");
        return summary.toString();
    }

    /**
     * 按缺什么给处置顺序，顺序遵循「先让组件有得做、再让仓库发得出手、最后才谈开工」。
     */
    private String readinessSuggestion(List<String> componentNames, List<String> draftComponentNos,
                                      List<String> stockShortNames) {
        List<String> steps = new ArrayList<>();
        if (!draftComponentNos.isEmpty()) {
            steps.add("下达并完成 " + draftComponentNos.size() + " 张组件工单的生产、检验与入库");
        } else if (!componentNames.isEmpty()) {
            steps.add("跟进组件工单产出组件的检验与入库");
        }
        if (!stockShortNames.isEmpty()) {
            steps.add("为本工单生成领料单并把库存件转到线边仓");
        }
        if (steps.isEmpty()) {
            steps.add("按阻塞原因逐项补齐");
        }
        return String.join("，再", steps);
    }

    private Map<String, Object> readinessRow(String item, boolean passed, String message, String detail) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("item", item);
        row.put("passed", passed ? "通过" : "未通过");
        row.put("message", message);
        row.put("detail", detail);
        return row;
    }

    private List<AiColumn> readinessColumns() {
        return List.of(
                new AiColumn("item", "检查项"),
                new AiColumn("passed", "结论"),
                new AiColumn("message", "说明"),
                new AiColumn("detail", "补充"));
    }

    private List<AiColumn> componentColumns() {
        return List.of(
                new AiColumn("materialCode", "物料编码"),
                new AiColumn("materialName", "物料名称"),
                new AiColumn("requiredQuantity", "需求数量"),
                new AiColumn("issuedQuantity", "已发数量"),
                new AiColumn("shortageQuantity", "缺口数量"),
                new AiColumn("unitName", "单位"),
                new AiColumn("sourceWorkOrderNo", "来源工单"),
                new AiColumn("sourceWorkOrderStatus", "来源工单状态"),
                new AiColumn("status", "处置说明"));
    }

    @Override
    public AiQueryResult queryWorkOrderStartPlan(String workOrderNo) {
        if (!StringUtils.hasText(workOrderNo)) {
            throw new AiServiceException("工单号不能为空");
        }
        String no = currentDocumentNumber(workOrderNo);
        AiQueryResult readiness = queryWorkOrderReadiness(no);
        if (readiness.getRows().isEmpty()) {
            return readiness;
        }
        Map<String, Object> header = queryMapper.selectWorkOrderHeader(no);
        if (header == null || header.isEmpty()) {
            return readiness;
        }
        Long workOrderId = toLong(header.get("workOrderId"));
        BigDecimal planned = decimal(header.get("plannedQuantity"));

        // 只把「未通过」的检查项翻成方案步骤，通过的不占篇幅。
        List<Map<String, Object>> pending = failingChecks(readiness.getRows());
        List<Map<String, Object>> componentOrders = queryMapper.selectComponentWorkOrders(workOrderId);
        ComponentPlanSummary componentPlan = summarizeComponentPlans(componentOrders);

        BigDecimal rootUnitMinutes = BigDecimal.ZERO;
        BigDecimal unitMinutes = queryMapper.selectWorkOrderUnitMinutes(workOrderId);
        if (unitMinutes != null) {
            rootUnitMinutes = unitMinutes;
        }
        BigDecimal rootMinutes = rootUnitMinutes.multiply(planned);

        List<Map<String, Object>> steps = buildPlanSteps(pending, componentOrders);
        List<Map<String, Object>> comparisons = buildPathComparison(
                pending, componentOrders, rootMinutes, componentPlan.totalMinutes());

        String summary = startPlanSummary(no, steps.size(), componentPlan.draftCount(), componentOrders.size(),
                componentPlan.totalMinutes(), rootMinutes, pending.isEmpty());
        List<AiBlock> blocks = new ArrayList<>();
        blocks.add(new AiBlock("处置步骤", planStepColumns(), steps, null));
        if (!componentPlan.rows().isEmpty()) {
            blocks.add(new AiBlock("组件工单工作量", componentPlanColumns(), componentPlan.rows(), null));
        }
        blocks.add(new AiBlock("两条路径对比", pathComparisonColumns(), comparisons, null));
        return new AiQueryResult("开工处置方案 " + text(header.get("workOrderNo")), summary,
                planColumns(), List.of(planMainRow(header, steps.size(), componentPlan.totalMinutes(), rootMinutes)),
                blocks);
    }

    /** 组件工单工作量汇总：展示行、合计工时、草稿数。 */
    private record ComponentPlanSummary(List<Map<String, Object>> rows, BigDecimal totalMinutes, int draftCount) {
    }

    /** 取出未通过的核查项。 */
    private List<Map<String, Object>> failingChecks(List<Map<String, Object>> checks) {
        List<Map<String, Object>> pending = new ArrayList<>();
        for (Map<String, Object> check : checks) {
            if ("未通过".equals(text(check.get("passed")))) {
                pending.add(check);
            }
        }
        return pending;
    }

    /** 汇总组件工单的工作量与展示行。 */
    private ComponentPlanSummary summarizeComponentPlans(List<Map<String, Object>> componentOrders) {
        List<Map<String, Object>> componentRows = new ArrayList<>();
        BigDecimal componentMinutes = BigDecimal.ZERO;
        int draftCount = 0;
        for (Map<String, Object> item : componentOrders) {
            String status = text(item.get("status"));
            BigDecimal unitMinutes = decimal(item.get("unitMinutes"));
            BigDecimal qty = decimal(item.get("plannedQuantity"));
            BigDecimal totalMinutes = unitMinutes.multiply(qty);
            if (draftOrApproved(status)) {
                draftCount++;
            }
            if (notStarted(status)) {
                componentMinutes = componentMinutes.add(totalMinutes);
            }
            componentRows.add(componentPlanRow(item, status, unitMinutes, qty, totalMinutes));
        }
        return new ComponentPlanSummary(componentRows, componentMinutes, draftCount);
    }

    /** 单个组件工单的展示行。 */
    private Map<String, Object> componentPlanRow(Map<String, Object> item, String status, BigDecimal unitMinutes,
                                                 BigDecimal qty, BigDecimal totalMinutes) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("workOrderNo", text(item.get("workOrderNo")));
        row.put("productName", text(item.get("productName")));
        row.put("status", workOrderStatusName(status));
        row.put("planNo", text(item.get("planNo")));
        row.put("planStatus", planStatusName(text(item.get("planStatus"))));
        row.put("plannedQuantity", scale(qty));
        row.put("stepCount", toLong(item.get("stepCount")));
        row.put("unitMinutes", scale(unitMinutes));
        row.put("totalMinutes", scale(totalMinutes));
        row.put("needAction", notStarted(status));
        return row;
    }

    /** 组件工单处于草稿或已审批状态。 */
    private static boolean draftOrApproved(String status) {
        return "DRAFT".equals(status) || "APPROVED".equals(status);
    }

    /**
     * 把未通过的检查项翻成有序的处置步骤：先让来源计划生效，再解组件、再发料，最后才是开工。
     *
     * <p>来源计划这一步不能省：组件工单下达的前置检查要求来源生产计划已生效，
     * 计划还是草稿时，直接点「下达组件工单」会被拦下来。方案里必须把这一步排在最前面。</p>
     */
    private List<Map<String, Object>> buildPlanSteps(List<Map<String, Object>> pending,
                                                     List<Map<String, Object>> componentOrders) {
        List<Map<String, Object>> steps = new ArrayList<>();
        addIfPresent(steps, pendingPlanStep(componentOrders));
        steps.addAll(componentOrderSteps(componentOrders));
        addIfPresent(steps, materialIssueStep(pending));
        addIfPresent(steps, taskStep(pending));
        if (steps.isEmpty() && !pending.isEmpty()) {
            steps.add(fallbackStep(pending));
        }
        return numberSteps(steps);
    }

    /** 按最终顺序给步骤编号，保持 stepNo 为首个字段。 */
    private static List<Map<String, Object>> numberSteps(List<Map<String, Object>> steps) {
        List<Map<String, Object>> numbered = new ArrayList<>(steps.size());
        for (int index = 0; index < steps.size(); index++) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("stepNo", index + 1);
            step.putAll(steps.get(index));
            numbered.add(step);
        }
        return numbered;
    }

    /** 非空步骤才加入列表。 */
    private static void addIfPresent(List<Map<String, Object>> steps, Map<String, Object> step) {
        if (step != null) {
            steps.add(step);
        }
    }

    /** 来源生产计划还没生效时，第一步先把计划下达。 */
    private Map<String, Object> pendingPlanStep(List<Map<String, Object>> componentOrders) {
        List<String> pendingPlans = componentOrders.stream()
                .filter(item -> notStarted(text(item.get("status"))))
                .filter(item -> !"RELEASED".equals(text(item.get("planStatus"))))
                .map(item -> text(item.get("planNo")))
                .filter(no -> !no.isBlank())
                .distinct()
                .toList();
        if (pendingPlans.isEmpty()) {
            return null;
        }
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("action", "先让 " + pendingPlans.size() + " 张来源生产计划生效");
        step.put("target", String.join("、", pendingPlans));
        step.put("reason", "组件工单的来源计划仍是草稿，计划不生效时工单下达会被前置检查拦住");
        step.put("document", String.join("、", pendingPlans));
        step.put("howTo", "在生产计划页把这几张计划下达，再回来下达组件工单");
        return step;
    }

    /** 逐张未开始的组件工单各生成一步。 */
    private List<Map<String, Object>> componentOrderSteps(List<Map<String, Object>> componentOrders) {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (Map<String, Object> item : componentOrders) {
            if (!notStarted(text(item.get("status")))) {
                continue;
            }
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("action", "下达并完成组件工单 " + text(item.get("workOrderNo")));
            step.put("target", text(item.get("productName")));
            step.put("reason", "组件工单当前为" + workOrderStatusName(text(item.get("status"))) + "，未产出组件");
            step.put("document", text(item.get("workOrderNo")));
            step.put("howTo", "生产工单页下达后派工、报工、终检，再完工入库");
            steps.add(step);
        }
        return steps;
    }

    /** 物料没齐套时补一步领料转线边。 */
    private Map<String, Object> materialIssueStep(List<Map<String, Object>> pending) {
        if (!hasFailedCheck(pending, "物料齐套")) {
            return null;
        }
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("action", "生成领料单并转线边仓");
        step.put("target", "库存件齐套");
        step.put("reason", "部分库存件尚未足额发到本工单");
        step.put("document", "");
        step.put("howTo", "在本工单点生成领料单，再领料转线边");
        return step;
    }

    /** 工序任务未就绪时补一步展开核对。 */
    private Map<String, Object> taskStep(List<Map<String, Object>> pending) {
        boolean needTask = hasFailedCheck(pending, "工序任务") || hasFailedCheck(pending, "任务状态");
        if (!needTask) {
            return null;
        }
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("action", "生成并核对工序任务");
        step.put("target", "工序任务就绪");
        step.put("reason", "工单尚未生成工序任务，或存在非待开始的工序任务");
        step.put("document", "");
        step.put("howTo", "下达后由系统展开工序任务，全部置为待开始");
        return step;
    }

    /** 兜底步骤：按核查表逐项补齐。 */
    private Map<String, Object> fallbackStep(List<Map<String, Object>> pending) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("action", "按未通过项逐条补齐");
        step.put("target", "前置条件");
        step.put("reason", String.join("；", pending.stream().map(row -> text(row.get("message"))).toList()));
        step.put("document", "");
        step.put("howTo", "按核查表逐项处理");
        return step;
    }

    /** 未通过项里是否存在指定名称的核查项。 */
    private boolean hasFailedCheck(List<Map<String, Object>> pending, String item) {
        return pending.stream().anyMatch(row -> item.equals(text(row.get("item"))));
    }

    /**
     * 两条路径的只读比较：直接开工 vs 先补齐前置条件。
     *
     * <p>「直接开工」可行与否必须以开工核查的未通过项为准，不能只看组件工单：
     * 工单已下达、物料也齐套、但工序任务还没生成时，点开始生产同样会被领域服务拒绝。
     * 所以只要 {@code pending} 非空就判不可行，并把具体卡在哪几项写进结论。</p>
     */
    private List<Map<String, Object>> buildPathComparison(List<Map<String, Object>> pending,
                                                          List<Map<String, Object>> componentOrders,
                                                          BigDecimal rootMinutes, BigDecimal componentMinutes) {
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean blocked = !pending.isEmpty();
        Map<String, Object> direct = new LinkedHashMap<>();
        direct.put("path", "直接开工");
        direct.put("canProceed", blocked ? "不可行" : "可行");
        direct.put("workMinutes", scale(BigDecimal.ZERO));
        direct.put("documentCount", 0L);
        direct.put("conclusion", blocked
                ? "开工校验会拒绝：" + blockingItems(pending)
                : "前置条件已满足，可直接点开始生产");
        rows.add(direct);

        Map<String, Object> planned = new LinkedHashMap<>();
        planned.put("path", "先补齐前置条件再开工");
        planned.put("canProceed", "可行");
        planned.put("workMinutes", scale(componentMinutes.add(rootMinutes)));
        planned.put("documentCount", (long) componentOrders.size());
        planned.put("conclusion", componentOrders.isEmpty()
                ? "按处置步骤补齐后即可开工生产本工单"
                : "推进 " + componentOrders.size() + " 张组件工单并完成领料后，再开工生产本工单");
        rows.add(planned);
        return rows;
    }

    /** 把未通过的核查项拼成一句「卡在哪」，用核查项名称而不是笼统说缺料。 */
    private String blockingItems(List<Map<String, Object>> pending) {
        List<String> items = new ArrayList<>();
        for (Map<String, Object> check : pending) {
            String item = text(check.get("item"));
            if (!item.isEmpty() && !items.contains(item)) {
                items.add(item);
            }
        }
        return items.isEmpty() ? "前置条件未满足" : String.join("、", items) + "未通过";
    }

    private String startPlanSummary(String workOrderNo, int stepCount, int draftCount,
                                    int componentCount, BigDecimal componentMinutes,
                                    BigDecimal rootMinutes, boolean alreadyReady) {
        if (alreadyReady) {
            return "工单 " + workOrderNo + " 的前置条件已经全部满足，不需要额外的处置方案，可以直接开始生产。";
        }
        StringBuilder summary = new StringBuilder();
        summary.append("工单 ").append(workOrderNo).append(" 的开工处置方案共 ").append(stepCount).append(" 步。");
        if (componentCount > 0) {
            summary.append("其中 ").append(draftCount).append(" 张组件工单还是草稿，")
                    .append("组件生产标准工时合计 ").append(num(componentMinutes)).append(" 分钟；");
        }
        summary.append("本工单自身标准工时约 ").append(num(rootMinutes)).append(" 分钟。");
        summary.append("这几个数字只汇总工艺路线标准工时，不含排队、换型与等待，")
                .append("用来比较两条路径的量级，不能当交期承诺。");
        return summary.toString();
    }

    private Map<String, Object> planMainRow(Map<String, Object> header, int stepCount,
                                            BigDecimal componentMinutes, BigDecimal rootMinutes) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("workOrderNo", text(header.get("workOrderNo")));
        row.put("productName", text(header.get("productName")));
        row.put("plannedQuantity", scale(decimal(header.get("plannedQuantity"))));
        row.put("currentStatus", workOrderStatusName(text(header.get("status"))));
        row.put("stepCount", (long) stepCount);
        row.put("componentMinutes", scale(componentMinutes));
        row.put("rootMinutes", scale(rootMinutes));
        return row;
    }

    private List<AiColumn> planColumns() {
        return List.of(
                new AiColumn("workOrderNo", "工单号"),
                new AiColumn("productName", "产品"),
                new AiColumn("plannedQuantity", "计划数量"),
                new AiColumn("currentStatus", "当前状态"),
                new AiColumn("stepCount", "处置步骤数"),
                new AiColumn("componentMinutes", "组件标准工时"),
                new AiColumn("rootMinutes", "本工单标准工时"));
    }

    private List<AiColumn> planStepColumns() {
        return List.of(
                new AiColumn("stepNo", "步骤"),
                new AiColumn("action", "要做的事"),
                new AiColumn("target", "作用对象"),
                new AiColumn("reason", "为什么要做"),
                new AiColumn("document", "涉及单据"),
                new AiColumn("howTo", "怎么做"));
    }

    private List<AiColumn> componentPlanColumns() {
        return List.of(
                new AiColumn("workOrderNo", "组件工单"),
                new AiColumn("productName", "组件"),
                new AiColumn("status", "当前状态"),
                new AiColumn("planNo", "来源生产计划"),
                new AiColumn("planStatus", "计划状态"),
                new AiColumn("plannedQuantity", "计划数量"),
                new AiColumn("stepCount", "工序数"),
                new AiColumn("unitMinutes", "单件工时"),
                new AiColumn("totalMinutes", "合计工时"),
                new AiColumn("needAction", "是否待推进"));
    }

    private List<AiColumn> pathComparisonColumns() {
        return List.of(
                new AiColumn("path", "路径"),
                new AiColumn("canProceed", "可行性"),
                new AiColumn("workMinutes", "标准工时合计"),
                new AiColumn("documentCount", "需推进单据数"),
                new AiColumn("conclusion", "结论"));
    }

    private static boolean notStarted(String status) {
        return "DRAFT".equals(status) || "APPROVED".equals(status) || "RELEASED".equals(status);
    }

    @Override
    public AiQueryResult queryProductionByDate(LocalDate dateFrom, LocalDate dateTo) {
        LocalDate[] range = normalizeRange(dateFrom, dateTo, 7);
        LocalDateTime start = range[0].atStartOfDay();
        LocalDateTime end = range[1].atTime(LocalTime.MAX);
        List<Map<String, Object>> dailyRows = queryMapper.selectDailyProduction(start, end);

        // 补齐区间内没有报工的日期，保证时间维逐日可核对。
        Map<String, Map<String, Object>> byDate = dailyRows.stream()
                .collect(Collectors.toMap(row -> text(row.get("reportDate")), row -> row, (left, right) -> left));
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal totalQualified = BigDecimal.ZERO;
        BigDecimal totalDefect = BigDecimal.ZERO;
        long totalReports = 0;
        for (LocalDate day = range[0]; !day.isAfter(range[1]); day = day.plusDays(1)) {
            String key = day.format(DATE_FORMATTER);
            Map<String, Object> raw = byDate.get(key);
            BigDecimal qualified = rawQuantity(raw, "qualifiedQuantity");
            BigDecimal defect = rawQuantity(raw, "defectQuantity");
            rows.add(dailyProductionRow(key, raw, qualified, defect));
            totalQualified = totalQualified.add(qualified);
            totalDefect = totalDefect.add(defect);
            totalReports += rawCount(raw, "reportCount");
        }

        BigDecimal totalOutput = totalQualified.add(totalDefect);
        String summary = range[0] + " 至 " + range[1] + " 末工序报工 " + totalReports + " 条，报工合格 "
                + num(totalQualified) + "，不良 " + num(totalDefect) + "，不良率 "
                + defectRate(totalDefect, totalOutput) + "。";

        List<AiBlock> blocks = new ArrayList<>();
        blocks.add(new AiBlock("产量趋势", List.of(), List.of(),
                barChart("末工序合格报工趋势", rows.stream().map(row -> text(row.get("reportDate"))).toList(),
                        "合格数量", rows.stream().map(row -> row.get("qualifiedQuantity")).toList())));
        return new AiQueryResult("产量与不良统计", summary, productionColumns(), rows, blocks);
    }

    /** 单日报工的展示行，当天没有报工时各数量按 0 展示。 */
    private Map<String, Object> dailyProductionRow(String key, Map<String, Object> raw,
                                                   BigDecimal qualified, BigDecimal defect) {
        BigDecimal total = qualified.add(defect);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("reportDate", key);
        row.put("workOrderCount", rawCount(raw, "workOrderCount"));
        row.put("reportCount", rawCount(raw, "reportCount"));
        row.put("qualifiedQuantity", scale(qualified));
        row.put("defectQuantity", scale(defect));
        row.put("defectRate", defectRate(defect, total));
        row.put("actualWorkHours", scale(rawQuantity(raw, "actualWorkHours")));
        return row;
    }

    /** 从统计行里取数量，行缺失时按 0 处理。 */
    private BigDecimal rawQuantity(Map<String, Object> raw, String key) {
        return raw == null ? BigDecimal.ZERO : decimal(raw.get(key));
    }

    /** 从统计行里取整数，行缺失时按 0 处理。 */
    private long rawCount(Map<String, Object> raw, String key) {
        return raw == null ? 0L : toLong(raw.get(key));
    }

    /** 不良率：分母为 0 时显示 0.00%。 */
    private static String defectRate(BigDecimal defect, BigDecimal total) {
        if (total.signum() == 0) {
            return "0.00%";
        }
        return defect.multiply(BigDecimal.valueOf(100)).divide(total, 2, RoundingMode.HALF_UP) + "%";
    }

    @Override
    public AiQueryResult queryDefectByPeriod(LocalDate dateFrom, LocalDate dateTo, String productKeyword) {
        LocalDate[] range = normalizeRange(dateFrom, dateTo, 7);
        LocalDateTime start = range[0].atStartOfDay();
        LocalDateTime end = range[1].atTime(LocalTime.MAX);
        String product = trimOrNull(productKeyword);
        List<Map<String, Object>> productRows = queryMapper.selectDefectByProduct(start, end, product);
        List<Map<String, Object>> operationRows = queryMapper.selectDefectByOperation(start, end, product);

        BigDecimal totalDefect = BigDecimal.ZERO;
        BigDecimal totalInspection = BigDecimal.ZERO;
        BigDecimal totalReport = BigDecimal.ZERO;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> raw : productRows) {
            BigDecimal inspection = decimal(raw.get("inspectionDefect"));
            BigDecimal report = decimal(raw.get("reportDefect"));
            BigDecimal defect = decimal(raw.get("defectQuantity"));
            totalDefect = totalDefect.add(defect);
            totalInspection = totalInspection.add(inspection);
            totalReport = totalReport.add(report);
            rows.add(defectProductRow(raw, inspection, report, defect));
        }

        List<Map<String, Object>> operationDetail = new ArrayList<>();
        for (Map<String, Object> raw : operationRows) {
            operationDetail.add(defectOperationRow(raw));
        }

        String summary = defectSummary(range, totalDefect, totalInspection, totalReport, rows, operationDetail,
                product);
        List<AiBlock> blocks = new ArrayList<>();
        if (!rows.isEmpty()) {
            blocks.add(new AiBlock("物料/产品不良分布图", List.of(), List.of(),
                    barChart("物料/产品不良数量", rows.stream().map(row -> text(row.get("productName"))).toList(),
                            "不良数量", rows.stream().map(row -> row.get("defectQuantity")).toList())));
        }
        blocks.add(new AiBlock("工序不良分布", operationColumns(), operationDetail,
                operationChart(operationDetail)));
        return new AiQueryResult("不良分布", summary, defectColumns(), rows, blocks);
    }

    @Override
    public AiQueryResult queryQualityYield(LocalDate dateFrom, LocalDate dateTo, String productKeyword) {
        LocalDate[] range = normalizeRange(dateFrom, dateTo, 30);
        LocalDateTime start = range[0].atStartOfDay();
        LocalDateTime end = range[1].atTime(LocalTime.MAX);
        String product = trimOrNull(productKeyword);
        Map<String, Object> raw = queryMapper.selectQualityYield(start, end, product);
        long inspectionCount = raw == null ? 0L : toLong(raw.get("inspectionCount"));
        long evaluated = raw == null ? 0L : toLong(raw.get("evaluatedCount"));
        long qualified = raw == null ? 0L : toLong(raw.get("qualifiedCount"));
        long unqualified = raw == null ? 0L : toLong(raw.get("unqualifiedCount"));
        BigDecimal sample = raw == null ? BigDecimal.ZERO : decimal(raw.get("sampleQuantity"));
        BigDecimal rate = evaluated == 0 ? null
                : BigDecimal.valueOf(qualified).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(evaluated), 2, RoundingMode.HALF_UP);
        String scope = product == null ? "" : "「" + product + "」相关产品的";

        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("inspectionCount", inspectionCount);
        row.put("evaluatedCount", evaluated);
        row.put("qualifiedCount", qualified);
        row.put("unqualifiedCount", unqualified);
        row.put("sampleQuantity", scale(sample));
        row.put("qualifiedRate", rate == null ? "—" : scale(rate) + "%");
        rows.add(row);

        String summary;
        if (evaluated == 0) {
            summary = range[0] + " 至 " + range[1] + " 没有已判定的" + scope + "检验单，算不出良率。"
                    + "口径：按检验结论统计，未判定的草稿检验单不计入。";
        } else {
            summary = range[0] + " 至 " + range[1] + scope + "已判定检验单 " + evaluated + " 张"
                    + "（本期共 " + inspectionCount + " 张，其余未出结论），合格 " + qualified
                    + " 张，不合格 " + unqualified + " 张，合格率 " + scale(rate) + "%；"
                    + "涉及抽检 " + num(sample) + "。"
                    + "口径：合格率 = 合格检验单数 ÷ 已判定检验单数，与质量看板同源；"
                    + "报工登记的不良是另一套口径，两者不能互相替代。";
        }
        List<AiBlock> blocks = new ArrayList<>();
        if (evaluated > 0) {
            blocks.add(new AiBlock("合格与不合格构成", List.of(), List.of(),
                    barChart("合格与不合格检验单数", List.of("合格", "不合格"), "检验单数",
                            List.of(qualified, unqualified))));
        }
        return new AiQueryResult("质量良率", summary, qualityYieldColumns(), rows, blocks);
    }

    private List<AiColumn> qualityYieldColumns() {
        return List.of(
                new AiColumn("inspectionCount", "本期检验单"),
                new AiColumn("evaluatedCount", "已判定"),
                new AiColumn("qualifiedCount", "合格"),
                new AiColumn("unqualifiedCount", "不合格"),
                new AiColumn("sampleQuantity", "抽检数量"),
                new AiColumn("qualifiedRate", "合格率"));
    }

    /** 单个物料/产品的不良展示行。 */    private Map<String, Object> defectProductRow(Map<String, Object> raw, BigDecimal inspection,
                                                 BigDecimal report, BigDecimal defect) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("productCode", text(raw.get("productCode")));
        row.put("productName", text(raw.get("productName")));
        row.put("inspectionDefect", scale(inspection));
        row.put("reportDefect", scale(report));
        row.put("defectQuantity", scale(defect));
        row.put("docCount", toLong(raw.get("docCount")));
        return row;
    }

    /** 单个工序的不良展示行。 */
    private Map<String, Object> defectOperationRow(Map<String, Object> raw) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("operationCode", text(raw.get("operationCode")));
        row.put("operationName", text(raw.get("operationName")));
        row.put("inspectionDefect", scale(decimal(raw.get("inspectionDefect"))));
        row.put("reportDefect", scale(decimal(raw.get("reportDefect"))));
        row.put("defectQuantity", scale(decimal(raw.get("defectQuantity"))));
        row.put("docCount", toLong(raw.get("docCount")));
        return row;
    }

    /** 工序不良柱图，没有明细时不画图。 */
    private Map<String, Object> operationChart(List<Map<String, Object>> operationDetail) {
        if (operationDetail.isEmpty()) {
            return null;
        }
        return barChart("工序不良数量",
                operationDetail.stream().map(row -> text(row.get("operationName"))).toList(),
                "不良数量", operationDetail.stream().map(row -> row.get("defectQuantity")).toList());
    }

    /** 不良分布的一句话结论，没有不良时换成无记录提示。 */
    private String defectSummary(LocalDate[] range, BigDecimal totalDefect, BigDecimal totalInspection,
                                 BigDecimal totalReport, List<Map<String, Object>> rows,
                                 List<Map<String, Object>> operationDetail, String product) {
        // 限定到某个产品时，结论里必须写出筛选范围，否则用户没法判断这个数是不是全厂的。
        String scope = product == null ? "" : "「" + product + "」相关产品的";
        if (totalDefect.signum() == 0) {
            if (product != null) {
                return range[0] + " 至 " + range[1] + " 未发现" + scope + "不良记录（检验判定 0，报工登记 0）。";
            }
            return range[0] + " 至 " + range[1] + " 未发现不良记录（检验判定 0，报工登记 0）。";
        }
        String topProduct = rows.isEmpty() ? "无" : text(rows.get(0).get("productName"));
        String topOperation = operationDetail.isEmpty() ? "无" : text(operationDetail.get(0).get("operationName"));
        return range[0] + " 至 " + range[1] + scope + "不良合计 " + num(totalDefect)
                + "（检验判定 " + num(totalInspection) + "，报工登记 " + num(totalReport) + "）"
                + "，按物料/产品最多的是 " + topProduct + "，按工序最多的是 " + topOperation + "。";
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public AiQueryResult queryTraceByBatch(String batchNo) {
        if (!StringUtils.hasText(batchNo)) {
            throw new AiServiceException("批次号不能为空");
        }
        String normalized = currentDocumentNumber(batchNo);
        Map<String, Object> batch = queryMapper.selectBatchInfo(normalized);
        Map<String, Object> productLot = queryMapper.selectProductLot(normalized);
        if ((batch == null || batch.isEmpty()) && (productLot == null || productLot.isEmpty())) {
            return emptyResult("批次追溯",
                    "未找到批次「" + normalized + "」，请确认批次号。",
                    traceBatchColumns());
        }

        Long batchId = batch == null ? null : toLong(batch.get("batchId"));
        Long productLotId = traceProductLotId(productLot, batch);
        if (batchId != null && productLot != null
                && !Objects.equals(productLotId, traceProductLotId(null, batch))) {
            return emptyResult("批次追溯", "批次号「" + normalized
                    + "」在仓储和生产中指向两个独立批次，请在原台账核对编号。", traceBatchColumns());
        }

        Map<String, Object> mainRow = traceMainRow(batch, productLot);

        BatchTraceRows traceRows = traceRows(batchId, productLotId);
        List<Map<String, Object>> inboundRows = traceRows.inbound();
        List<Map<String, Object>> issueRows = traceRows.issue();
        List<Map<String, Object>> reportRows = traceRows.report();
        List<Map<String, Object>> inspectionRows = traceRows.inspection();
        List<Map<String, Object>> finishedBatchRows = traceRows.finishedBatch();

        var upstream = productLotId == null ? List.<Map<String, Object>>of()
                : queryMapper.selectBatchConsumption(null, productLotId);
        var downstream = batchId == null ? List.<Map<String, Object>>of()
                : queryMapper.selectBatchConsumption(batchId, null);
        List<AiBlock> blocks = new ArrayList<>();
        StringBuilder relationSummary = new StringBuilder();
        if (productLotId != null) {
            String lotNo = productLot == null ? text(batch.get("sourceLotNo")) : text(productLot.get("lotNo"));
            if (batchId != null && StringUtils.hasText(lotNo)) {
                relationSummary.append("仓储批次的来源生产批为 ").append(lotNo).append("。");
            }
            appendConsumption(blocks, relationSummary, "反查来源物料批", upstream, "inputBatchNo", "个来源物料批");
        }
        if (batchId != null) {
            appendConsumption(blocks, relationSummary, "正查关联产品批", downstream, "outputLotNo", "个关联产品批");
        }
        blocks.add(new AiBlock("来源入库单", traceInboundColumns(), limit(inboundRows), null));
        blocks.add(new AiBlock("领用工单", traceIssueColumns(), limit(issueRows), null));
        blocks.add(new AiBlock("报工消耗", traceReportColumns(), limit(reportRows), null));
        blocks.add(new AiBlock("检验记录", inspectionColumns(), limit(inspectionRows), null));
        blocks.add(new AiBlock("来源产品批入库后的仓储批次", traceFinishedBatchColumns(), limit(finishedBatchRows), null));

        String summary = traceSummary(normalized, mainRow, inboundRows, issueRows, reportRows,
                inspectionRows, finishedBatchRows) + relationSummary;
        return new AiQueryResult("批次追溯 " + normalized, summary, traceBatchColumns(), List.of(mainRow), blocks);
    }

    /** 产品批次 ID：先用产品批次自身，取不到时回落到物料批次上记的来源产品批次。 */
    private Long traceProductLotId(Map<String, Object> productLot, Map<String, Object> batch) {
        Object id = productLot != null ? productLot.get("productLotId")
                : batch == null ? null : batch.get("sourceProductLotId");
        return id == null ? null : toLong(id);
    }

    private void appendConsumption(List<AiBlock> blocks, StringBuilder summary, String title,
                                   List<Map<String, Object>> rows, String key, String unit) {
        if (rows.isEmpty()) {
            summary.append(title).append("未找到有效的直接用料记录。");
            return;
        }
        var visible = limit(rows);
        long count = visible.stream().map(row -> row.get(key)).distinct().count();
        summary.append(title).append(rows.size() > MAX_DETAIL_ROWS ? "当前展示至少 " : "命中 ")
                .append(count).append(' ').append(unit).append("。");
        if (rows.size() > MAX_DETAIL_ROWS) {
            summary.append("明细仅显示前 ").append(MAX_DETAIL_ROWS).append(" 条。");
        }
        blocks.add(new AiBlock(title, List.of(
                new AiColumn("usageId", "追溯记录 ID"),
                new AiColumn("inputBatchNo", "投入物料批"),
                new AiColumn("outputLotNo", "产出产品批"),
                new AiColumn("materialCode", "物料编码"),
                new AiColumn("materialName", "物料"),
                new AiColumn("usedQuantity", "记录用量（含报废）"),
                new AiColumn("unitName", "物料单位"),
                new AiColumn("productName", "产出产品"),
                new AiColumn("workOrderNo", "工单号"),
                new AiColumn("reportNo", "报工单号")), visible, null));
    }

    /** 批次追溯要展示的五组关联数据。 */
    private record BatchTraceRows(List<Map<String, Object>> inbound, List<Map<String, Object>> issue,
                                  List<Map<String, Object>> report, List<Map<String, Object>> inspection,
                                  List<Map<String, Object>> finishedBatch) {}

    /** 按批次 ID / 产品批次 ID 取追溯关联数据，标识缺失时返回空列表。 */
    private BatchTraceRows traceRows(Long batchId, Long productLotId) {
        List<Map<String, Object>> inboundRows = List.of();
        List<Map<String, Object>> issueRows = List.of();
        List<Map<String, Object>> reportRows = List.of();
        if (batchId != null) {
            inboundRows = queryMapper.selectBatchInbound(batchId);
            issueRows = queryMapper.selectBatchIssues(batchId);
            reportRows = queryMapper.selectBatchReports(batchId);
        }
        List<Map<String, Object>> inspectionRows = queryMapper.selectBatchInspections(batchId, productLotId);
        List<Map<String, Object>> finishedBatchRows = productLotId == null
                ? List.of() : queryMapper.selectBatchByProductLot(productLotId);
        return new BatchTraceRows(inboundRows, issueRows, reportRows, inspectionRows, finishedBatchRows);
    }

    /** 批次追溯的主行：优先用物料批次，其次用产品批次。 */
    private Map<String, Object> traceMainRow(Map<String, Object> batch, Map<String, Object> productLot) {
        Map<String, Object> mainRow = new LinkedHashMap<>();
        if (batch != null && !batch.isEmpty()) {
            mainRow.put("batchNo", text(batch.get("batchNo")));
            mainRow.put("materialCode", text(batch.get("materialCode")));
            mainRow.put("materialName", text(batch.get("materialName")));
            mainRow.put("qualityStatus", qualityStatusName(text(batch.get("qualityStatus"))));
            mainRow.put("productionDate", formatDate(batch.get("productionDate")));
            mainRow.put("receivedDate", formatDate(batch.get("receivedDate")));
            return mainRow;
        }
        mainRow.put("batchNo", text(productLot.get("lotNo")));
        mainRow.put("materialCode", text(productLot.get("productCode")));
        mainRow.put("materialName", text(productLot.get("productName")));
        mainRow.put("qualityStatus", "");
        mainRow.put("productionDate", formatDate(productLot.get("createdTime")));
        mainRow.put("receivedDate", "");
        return mainRow;
    }

    /** 批次追溯的一句话结论。 */
    private String traceSummary(String normalized, Map<String, Object> mainRow,
                                List<Map<String, Object>> inboundRows, List<Map<String, Object>> issueRows,
                                List<Map<String, Object>> reportRows, List<Map<String, Object>> inspectionRows,
                                List<Map<String, Object>> finishedBatchRows) {
        String affectedOrders = issueRows.stream()
                .map(row -> text(row.get("workOrderNo")))
                .filter(StringUtils::hasText)
                .distinct()
                .collect(Collectors.joining("、"));
        String orderSuffix = StringUtils.hasText(affectedOrders) ? "（" + affectedOrders + "）" : "";
        return "批次 " + normalized + "（" + text(mainRow.get("materialName")) + "）"
                + "来源入库 " + inboundRows.size() + " 单，被 " + issueRows.size() + " 个工单领用"
                + orderSuffix
                + "，报工消耗 " + reportRows.size() + " 条，检验 " + inspectionRows.size() + " 单，"
                + "来源产品批关联仓储批次 " + finishedBatchRows.size() + " 个。";
    }

    @Override
    public AiQueryResult queryScheduleCapacity(LocalDate dateFrom, LocalDate dateTo) {
        return queryScheduleCapacity(dateFrom, dateTo, null, false);
    }

    @Override
    public AiQueryResult queryScheduleCapacity(LocalDate dateFrom, LocalDate dateTo, String lineKeyword) {
        return queryScheduleCapacity(dateFrom, dateTo, lineKeyword, false);
    }

    @Override
    public AiQueryResult queryScheduleCapacity(LocalDate dateFrom, LocalDate dateTo, String lineKeyword, boolean lateOnly) {
        LocalDate[] range = normalizeRange(dateFrom, dateTo, 7);
        LocalDateTime start = range[0].atStartOfDay();
        LocalDateTime end = range[1].atTime(LocalTime.MAX);
        List<Map<String, Object>> lineRows = queryMapper.selectScheduleByLine(start, end);
        List<Map<String, Object>> itemRows = queryMapper.selectScheduleItems(start, end);

        long workDays = Math.max(1, range[0].datesUntil(range[1].plusDays(1)).count());
        var calendar = lineCalendars.availability(range[0], range[1]);
        String lineFilter = StringUtils.hasText(lineKeyword) ? lineKeyword.trim() : null;

        List<Map<String, Object>> rows = collectScheduleLines(lineRows, calendar, lineFilter);
        List<Map<String, Object>> itemDetail = collectScheduleItems(itemRows, lineFilter);

        BigDecimal totalLoad = rows.stream().map(row -> decimal(row.get("loadMinutes")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalAvailable = rows.stream().map(row -> decimal(row.get("availableMinutes")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalRemaining = totalAvailable.subtract(totalLoad);
        BigDecimal overallUtilization = utilizationOf(totalLoad, totalAvailable);
        long totalLate = rows.stream().mapToLong(row -> toLong(row.get("lateCount"))).sum();
        long totalConflict = rows.stream().mapToLong(row -> toLong(row.get("conflictCount"))).sum();
        long scheduledLines = rows.stream().filter(row -> toLong(row.get("itemCount")) > 0).count();

        AiQueryResult empty = emptyScheduleResult(range, lineFilter, rows, lateOnly);
        if (empty != null) {
            return empty;
        }
        if (lateOnly) {
            rows = rows.stream().filter(row -> toLong(row.get("lateCount")) > 0).collect(Collectors.toList());
            itemDetail = itemDetail.stream().filter(row -> decimal(row.get("tardinessMinutes")).signum() > 0)
                    .collect(Collectors.toList());
        }
        String summary = scheduleSummary(range, workDays, lineFilter, rows, totalAvailable, totalLoad,
                totalRemaining, overallUtilization, itemDetail.size(), scheduledLines, totalLate, totalConflict,
                lateOnly);
        if (itemDetail.isEmpty() && !lineRows.isEmpty()) {
            summary = summary + "当前没有已确认的排程项，可在智能排产工作台确认候选排程后再查看逐项明细。";
        }

        List<AiBlock> blocks = new ArrayList<>();
        blocks.add(new AiBlock("排程明细", scheduleItemColumns(), limit(itemDetail), scheduleChart(rows)));
        String conflictNote = appendConflictBlocks(blocks, start, end, lineFilter);
        return new AiQueryResult("排程与产能占用", summary + conflictNote, scheduleLineColumns(), rows, blocks);
    }

    /**
     * 追加实时资源冲突扫描结果，并返回要拼到摘要末尾的说明。
     *
     * <p>未注入冲突服务时只提示去冲突页面核对；扫描结果结构异常时直接报错，
     * 避免把「没扫到」误当成「没有冲突」。</p>
     */
    private String appendConflictBlocks(List<AiBlock> blocks, LocalDateTime start, LocalDateTime end,
                                        String lineFilter) {
        if (resourceConflictService == null) {
            return " 未执行实时资源冲突扫描，请在跨层资源冲突页面核对。";
        }
        Map<String, Object> scan = resourceConflictService.scan(start, end);
        Object rawConflicts = scan.get("items");
        if (!(rawConflicts instanceof List<?> conflicts)) {
            throw new AiServiceException("资源冲突扫描结果不可用，请稍后重试");
        }
        List<Map<String, Object>> visibleConflicts = new ArrayList<>();
        for (Object conflict : conflicts) {
            if (conflict instanceof Map<?, ?> raw) {
                Map<String, Object> item = new LinkedHashMap<>();
                raw.forEach((key, value) -> item.put(String.valueOf(key), value));
                if (isConflictVisible(item, lineFilter)) {
                    visibleConflicts.add(item);
                }
            }
        }
        if (!visibleConflicts.isEmpty()) {
            blocks.add(new AiBlock("资源冲突", List.of(
                    new AiColumn("conflictLabel", "冲突类型"), new AiColumn("lineName", "产线"),
                    new AiColumn("planNo", "计划"), new AiColumn("relatedPlanNo", "相关计划"),
                    new AiColumn("reason", "原因")), limit(visibleConflicts), null));
        }
        return " 活跃计划资源冲突扫描发现 " + visibleConflicts.size() + " 处；"
                + "包括同线重叠及组件时序问题，不含已完工计划。";
    }

    /** 冲突项是否通过产线过滤：没给过滤条件就全看，给了就按冲突行里的产线字段匹配。 */
    private boolean isConflictVisible(Map<String, Object> item, String lineFilter) {
        return lineFilter == null || matchesLine(item, lineFilter);
    }

    /** 空结果场景（产线过滤无匹配、只看延期但无延期）统一在此判定。 */
    private AiQueryResult emptyScheduleResult(LocalDate[] range, String lineFilter,
                                              List<Map<String, Object>> rows, boolean lateOnly) {
        if (lineFilter != null && rows.isEmpty()) {
            return new AiQueryResult("排程与产能占用",
                    range[0] + " 至 " + range[1] + " 没有名称或编码包含「" + lineFilter + "」的产线排程。",
                    scheduleLineColumns(), List.of(), List.of());
        }
        boolean noLate = lateOnly && rows.stream().noneMatch(row -> toLong(row.get("lateCount")) > 0);
        if (!noLate) {
            return null;
        }
        String scope = lineFilter == null
                ? range[0] + " 至 " + range[1]
                : "产线「" + lineFilter + "」在 " + range[0] + " 至 " + range[1];
        return new AiQueryResult("排程与产能占用", scope + " 没有延期订单。",
                scheduleLineColumns(), List.of(), List.of());
    }

    /** 逐条产线的负荷行，按关键字过滤。 */
    private List<Map<String, Object>> collectScheduleLines(List<Map<String, Object>> lineRows,
                                                           Map<Long, com.neuedu.ems.service.ai.AiLineCalendarService.Availability> calendar,
                                                           String lineFilter) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> raw : lineRows) {
            var lineCalendar = calendar.getOrDefault(toLong(raw.get("lineId")),
                    new com.neuedu.ems.service.ai.AiLineCalendarService.Availability(BigDecimal.ZERO, BigDecimal.ZERO));
            rows.add(scheduleLineRow(raw, lineCalendar));
        }
        if (lineFilter == null) {
            return rows;
        }
        return rows.stream().filter(row -> matchesLine(row, lineFilter)).collect(Collectors.toList());
    }

    /** 单条产线的负荷展示行。 */
    private Map<String, Object> scheduleLineRow(Map<String, Object> raw,
                                                com.neuedu.ems.service.ai.AiLineCalendarService.Availability lineCalendar) {
        BigDecimal availableMinutes = lineCalendar.available();
        BigDecimal loadMinutes = availableMinutes.subtract(lineCalendar.remaining());
        BigDecimal utilization = utilizationOf(loadMinutes, availableMinutes);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("lineCode", text(raw.get("lineCode")));
        row.put("lineName", text(raw.get("lineName")));
        row.put("lineStatus", lineStatusName(text(raw.get("lineStatus"))));
        row.put("itemCount", toLong(raw.get("itemCount")));
        row.put("plannedQuantity", scale(decimal(raw.get("plannedQuantity"))));
        row.put("loadMinutes", scale(loadMinutes));
        row.put("availableMinutes", scale(availableMinutes));
        row.put("remainingMinutes", scale(availableMinutes.subtract(loadMinutes)));
        row.put("utilizationRate", availableMinutes.signum() == 0 ? "未配置有效周历" : utilization + "%");
        row.put("lateCount", toLong(raw.get("lateCount")));
        row.put("conflictCount", toLong(raw.get("conflictCount")));
        return row;
    }

    /** 负荷率：可用工时为 0 时按 0 计。 */
    private static BigDecimal utilizationOf(BigDecimal loadMinutes, BigDecimal availableMinutes) {
        if (availableMinutes.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return loadMinutes.multiply(BigDecimal.valueOf(100)).divide(availableMinutes, 2, RoundingMode.HALF_UP);
    }

    /** 逐条排程项明细，按关键字过滤。 */
    private List<Map<String, Object>> collectScheduleItems(List<Map<String, Object>> itemRows, String lineFilter) {
        List<Map<String, Object>> itemDetail = new ArrayList<>();
        for (Map<String, Object> raw : itemRows) {
            itemDetail.add(scheduleItemRow(raw));
        }
        if (lineFilter == null) {
            return itemDetail;
        }
        return itemDetail.stream().filter(row -> matchesLine(row, lineFilter)).collect(Collectors.toList());
    }

    /** 单条排程项的展示行。 */
    private Map<String, Object> scheduleItemRow(Map<String, Object> raw) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("lineName", text(raw.get("lineName")));
        row.put("productionOrderNo", text(raw.get("productionOrderNo")));
        row.put("productName", text(raw.get("productName")));
        row.put("plannedQuantity", scale(decimal(raw.get("plannedQuantity"))));
        row.put("plannedStartTime", formatDateTime(raw.get("plannedStartTime")));
        row.put("plannedEndTime", formatDateTime(raw.get("plannedEndTime")));
        row.put("estimatedMinutes", scale(decimal(raw.get("estimatedMinutes"))));
        row.put("tardinessMinutes", scale(decimal(raw.get("tardinessMinutes"))));
        row.put("status", text(raw.get("status")));
        row.put("conflictReason", text(raw.get("conflictReason")));
        return row;
    }

    /** 产线负荷柱图，没有产线时不画图。 */
    private Map<String, Object> scheduleChart(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        return barChart("产线负荷工时", rows.stream().map(row -> text(row.get("lineName"))).toList(),
                "占用工时(分钟)", rows.stream().map(row -> row.get("loadMinutes")).toList());
    }

    /** 排程与产能占用的一句话结论。 */
    private String scheduleSummary(LocalDate[] range, long workDays, String lineFilter,
                                   List<Map<String, Object>> rows, BigDecimal totalAvailable,
                                   BigDecimal totalLoad, BigDecimal totalRemaining, BigDecimal overallUtilization,
                                   int itemCount, long scheduledLines, long totalLate, long totalConflict,
                                   boolean lateOnly) {
        String lineScope = lineFilter == null ? rows.size() + " 条产线" : "产线「" + lineFilter + "」";
        return range[0] + " 至 " + range[1] + " 共 " + workDays + " 个自然日，"
                + lineScope
                + "按已生效班次周历可用工时 " + num(totalAvailable) + " 分钟，"
                + "其中已下达计划占用 " + num(totalLoad) + " 分钟，剩余可排 " + num(totalRemaining)
                + " 分钟，整体负荷率 " + overallUtilization + "%；"
                + "已确认排程项 " + itemCount + " 个（" + scheduledLines + " 条产线有排程），延期任务 "
                + totalLate + " 个、排程记录中标记冲突 " + totalConflict + " 个。"
                + (lateOnly ? " 当前只列出延期任务。" : "");
    }

    @Override
    public AiQueryResult queryOrderProgress(String orderNo) {
        if (!StringUtils.hasText(orderNo)) {
            throw new AiServiceException("生产订单号不能为空");
        }
        Map<String, Object> header = queryMapper.selectOrderHeader(currentDocumentNumber(orderNo));
        if (header == null || header.isEmpty()) {
            return emptyResult("生产订单进度",
                    "未找到生产订单「" + orderNo.trim() + "」，请确认订单号。",
                    orderProgressColumns());
        }
        OrderProgressSource source = loadOrderProgressSource(header);
        Map<String, Object> mainRow = orderProgressMainRow(header, source.orderQuantity(),
                source.plannedQuantity(), source.completedQuantity(), source.inboundQuantity(),
                source.shippedQuantity());
        List<AiBlock> blocks = orderProgressBlocks(source);
        String summary = orderProgressSummary(header, source.orderQuantity(), source.plannedQuantity(),
                source.completedQuantity(), source.inboundQuantity(), source.shippedQuantity())
                + " " + orderNextStep(header, source.planRows(), source.allWorkOrders(),
                source.orderQuantity(), source.inboundQuantity(), source.shippedQuantity());
        return new AiQueryResult("订单进度 " + text(header.get("productionOrderNo")), summary,
                orderProgressColumns(), List.of(mainRow), blocks);
    }

    /** 订单进度取数快照。allWorkOrders 保留组件工单，供明细和下一步建议使用；workOrders 只计整机进度。 */
    private record OrderProgressSource(List<Map<String, Object>> planRows,
                                       List<Map<String, Object>> allWorkOrders,
                                       List<Map<String, Object>> workOrders,
                                       List<Map<String, Object>> inboundRows,
                                       List<Map<String, Object>> shipmentRows,
                                       BigDecimal orderQuantity,
                                       BigDecimal plannedQuantity,
                                       BigDecimal completedQuantity,
                                       BigDecimal inboundQuantity,
                                       BigDecimal shippedQuantity) {
    }

    /**
     * 汇总订单进度所需的全部原始数据。
     *
     * <p>组件工单与已关闭工单不计入整机完工进度，入库与发货只认已审核通过的行。</p>
     */
    private OrderProgressSource loadOrderProgressSource(Map<String, Object> header) {
        Long productionOrderId = toLong(header.get("productionOrderId"));
        List<Map<String, Object>> planRows = queryMapper.selectOrderPlans(productionOrderId);
        List<Map<String, Object>> allWorkOrders = queryMapper.selectOrderWorkOrders(productionOrderId);
        List<Map<String, Object>> workOrders = allWorkOrders.stream()
                .filter(row -> !"COMPONENT".equals(row.get("workOrderType")))
                .filter(row -> !"CLOSED".equals(row.get("status")))
                .toList();
        List<Long> workOrderIds = workOrders.stream()
                .map(row -> toLong(row.get("workOrderId")))
                .filter(Objects::nonNull)
                .toList();
        List<Map<String, Object>> inboundRows = queryMapper.selectOrderInbound(workOrderIds);
        List<Map<String, Object>> shipmentRows = queryMapper.selectOrderShipments(productionOrderId);
        return new OrderProgressSource(planRows, allWorkOrders, workOrders, inboundRows, shipmentRows,
                decimal(header.get("orderQuantity")),
                plannedOrderQuantity(planRows),
                sumColumn(workOrders, "completedQuantity"),
                sumColumn(approvedRows(inboundRows), "inboundQuantity"),
                sumColumn(approvedRows(shipmentRows), "outboundQuantity"));
    }

    /** 订单进度的明细区块与一张对比柱图。有未结束的组件工单时单独成块。 */
    private List<AiBlock> orderProgressBlocks(OrderProgressSource source) {
        List<Map<String, Object>> planDetail = new ArrayList<>();
        for (Map<String, Object> raw : source.planRows()) {
            planDetail.add(orderPlanRow(raw));
        }
        List<AiBlock> blocks = new ArrayList<>();
        addComponentWorkOrderBlock(blocks, source.allWorkOrders());
        blocks.add(new AiBlock("计划与工单", orderPlanColumns(), limit(planDetail), null));
        blocks.add(new AiBlock("成品入库", orderInboundColumns(), limit(source.inboundRows()), null));
        blocks.add(new AiBlock("发货记录", orderShipmentColumns(), limit(source.shipmentRows()), null));
        blocks.add(new AiBlock("订单进度对比", List.of(), List.of(),
                barChart("订单进度", List.of("订单量", "已排产", "已完工", "已入库", "已发货"),
                        "数量", List.of(scale(source.orderQuantity()), scale(source.plannedQuantity()),
                                scale(source.completedQuantity()), scale(source.inboundQuantity()),
                                scale(source.shippedQuantity())))));
        return blocks;
    }

    /** 未关闭、未取消的组件工单单独成块，避免和整机完工数量混在一起。 */
    private void addComponentWorkOrderBlock(List<AiBlock> blocks, List<Map<String, Object>> allWorkOrders) {
        List<Map<String, Object>> components = allWorkOrders.stream()
                .filter(row -> "COMPONENT".equals(text(row.get("workOrderType"))))
                .filter(row -> !List.of("CLOSED", "CANCELLED").contains(text(row.get("status"))))
                .map(row -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("workOrderNo", text(row.get("workOrderNo")));
                    item.put("productName", text(row.get("productName")));
                    item.put("plannedQuantity", row.get("plannedQuantity"));
                    String state = text(row.get("status"));
                    item.put("status", WORK_ORDER_STATUS_NAMES.getOrDefault(state, state));
                    return item;
                }).toList();
        if (components.isEmpty()) {
            return;
        }
        blocks.add(new AiBlock("组件工单", List.of(new AiColumn("productName", "组件"),
                new AiColumn("workOrderNo", "工单号"), new AiColumn("plannedQuantity", "计划数量"),
                new AiColumn("status", "状态")), limit(components), null));
    }

    /**
     * 依据已读取的订单、计划和工单给出下一步。
     *
     * <p>推理顺序必须沿着八阶段走完最后两步：订单头置为 FINISHED 只代表工单完工，
     * 不等于货已经进仓和发出。因此先看成品入库量，再看发货量，都齐了才算闭环。
     * 否则「订单已完成」会盖掉「成品入库 0、已发货 0」这个真实缺口。</p>
     */
    private String orderNextStep(Map<String, Object> header, List<Map<String, Object>> plans, List<Map<String, Object>> workOrders, BigDecimal orderQuantity, BigDecimal inboundQuantity, BigDecimal shippedQuantity) {
        String status = text(header.get("status"));
        String orderNo = text(header.get("productionOrderNo"));
        if ("CANCELLED".equals(status) || "CLOSED".equals(status)) {
            return "下一步：订单已" + orderStatusName(status) + "，不需要继续办理。";
        }
        BigDecimal target = orderQuantity == null || orderQuantity.signum() <= 0 ? BigDecimal.ONE : orderQuantity;
        BigDecimal inbound = inboundQuantity == null ? BigDecimal.ZERO : inboundQuantity;
        BigDecimal shipped = shippedQuantity == null ? BigDecimal.ZERO : shippedQuantity;
        // 先核对真实产出与交付数量，再处理计划和工单。
        List<Map<String, Object>> rootWorkOrders = workOrders.stream().filter(row -> "ROOT".equals(text(row.get("workOrderType")))).toList();
        boolean produced = "FINISHED".equals(status) || !rootWorkOrders.isEmpty() && sumColumn(rootWorkOrders, "completedQuantity").compareTo(target) >= 0;
        String delivery = deliveryNextStep(status, target, inbound, shipped, produced);
        return delivery != null ? delivery : planningNextStep(plans, workOrders);
    }

    private String deliveryNextStep(String status, BigDecimal target, BigDecimal inbound, BigDecimal shipped, boolean produced) {
        String result = null;
        if (produced && inbound.compareTo(target) < 0) {
            String gap = plainQuantity(target.subtract(inbound));
            result = "下一步：成品还差 " + gap + " 台没有入库。请把终检合格的产品批次生成生产入库单并确认入库" + "（成品仓入库，第 7 阶段），入库后再走发货。";
        } else if (produced && inbound.compareTo(target) >= 0 && shipped.compareTo(target) < 0) {
            String gap = plainQuantity(target.subtract(shipped));
            result = "下一步：成品已入库但还差 " + gap + " 台没有发出。请按顺序办理：" + "①为订单生成销售出库（发货）单；②生成出货检验单并录入合格结论；③确认发货。" + "三步都办完这张订单才算交付完成。";
        } else if (produced && shipped.compareTo(target) >= 0) {
            result = "下一步：这张订单已完工、已入库、已发货，整条流程闭环，无需再办理。";
        } else if ("FINISHED".equals(status)) {
            result = "下一步：订单头已是已完成，但入库与发货数量还没核对齐，请核对成品入库单与发货记录。";
        }
        return result;
    }

    private String planningNextStep(List<Map<String, Object>> plans, List<Map<String, Object>> workOrders) {
        if (plans.isEmpty()) {
            return "下一步：目前没有查到关联生产计划，请先为这张订单安排生产并确认计划。";
        }
        boolean confirmedPlan = plans.stream().anyMatch(row -> List.of("RELEASED", "RUNNING", "FINISHED").contains(text(row.get("planStatus"))));
        if (!confirmedPlan) {
            return "下一步：已查到生产计划，但没有查到已生效的计划，请先核对计划状态并确认。";
        }
        return workOrdersNextStep(workOrders);
    }

    private String workOrdersNextStep(List<Map<String, Object>> workOrders) {
        List<Map<String, Object>> active = workOrders.stream().filter(row -> !"CLOSED".equals(text(row.get("status")))).toList();
        if (active.isEmpty()) {
            return "下一步：已查到关联生产计划，但没有查到有效工单。请核对计划状态，并按计划生成工单。";
        }
        String components = componentNextStep(active);
        return components != null ? components : activeWorkOrdersNextStep(active);
    }

    private String componentNextStep(List<Map<String, Object>> active) {
        List<Map<String, Object>> components = active.stream().filter(row -> "COMPONENT".equals(text(row.get("workOrderType")))).filter(row -> !"CANCELLED".equals(text(row.get("status")))).toList();
        List<String> pendingComponents = components.stream().filter(row -> "DRAFT".equals(text(row.get("status"))) || "APPROVED".equals(text(row.get("status")))).map(row -> (StringUtils.hasText(text(row.get("productName"))) ? text(row.get("productName")) + "（" : "组件（") + text(row.get("workOrderNo")) + "，计划 " + text(row.get("plannedQuantity")) + "）").toList();
        if (!pendingComponents.isEmpty()) {
            return "下一步：先生产组件：" + String.join("、", pendingComponents) + "。这些组件工单尚未下达；请先核对并下达，再按实际情况领料、派工、生产和检验。组件放行后才能按规则交接给整机工单。";
        }
        List<String> unfinishedComponents = components.stream().filter(row -> !"FINISHED".equals(text(row.get("status")))).map(row -> text(row.get("productName")) + "（" + text(row.get("workOrderNo")) + "）").toList();
        if (!unfinishedComponents.isEmpty()) {
            return "下一步：先完成组件工单：" + String.join("、", unfinishedComponents) + "。请检查领料和派工，逐项开工工序任务并按实际情况报工、检验和交接，再继续整机生产。";
        }
        return null;
    }

    private String activeWorkOrdersNextStep(List<Map<String, Object>> active) {
        List<String> pending = active.stream().filter(row -> "DRAFT".equals(text(row.get("status"))) || "APPROVED".equals(text(row.get("status")))).map(row -> text(row.get("workOrderNo"))).filter(StringUtils::hasText).limit(3).toList();
        if (!pending.isEmpty()) {
            return "下一步：关联工单 " + String.join("、", pending) + " 尚未下达，请先核对工单及组件依赖，再预览下达；能否下达以实际校验为准。";
        }
        boolean running = active.stream().anyMatch(row -> "RELEASED".equals(text(row.get("status"))) || "RUNNING".equals(text(row.get("status"))));
        if (running) {
            return "下一步：关联工单已下达或正在生产，请检查组件依赖、领料和开工条件，再继续生产；缺料或未检验的情况需要先处理。";
        }
        return "下一步：请核对关联工单、检验、入库及发货记录，确认当前待处理环节。";
    }

    @Override
    public AiQueryResult queryOrderScheduleSimulation(String orderNo) {
        if (!StringUtils.hasText(orderNo)) {
            throw new AiServiceException("生产订单号不能为空");
        }
        if (scheduleScenarioService == null) {
            throw new AiServiceException("排程推演服务未就绪，请稍后重试");
        }
        ScheduleSimulationComparison comparison = scheduleScenarioService.simulateOrderSchedule(
                resolveProductionOrderId(orderNo.trim()));
        List<AiColumn> columns = List.of(
                new AiColumn("label", "口径"),
                new AiColumn("feasible", "是否可行"),
                new AiColumn("lineName", "产线"),
                new AiColumn("startTime", "最早开工"),
                new AiColumn("endTime", "预计完工"),
                new AiColumn("workingMinutes", "占用班次分钟"),
                new AiColumn("meetsDelivery", "满足交期"));
        List<Map<String, Object>> rows = simulationRows(comparison);
        List<AiBlock> blocks = new ArrayList<>();
        blocks.add(new AiBlock("各口径的说明与阻塞", List.of(
                new AiColumn("label", "口径"),
                new AiColumn("detail", "说明"),
                new AiColumn("blockers", "阻塞项")), simulationDetailRows(comparison), null));
        appendTextBlock(blocks, "推演假设", "assumption", "假设", comparison.getAssumptions());
        appendTextBlock(blocks, "缺口与未纳入的条件", "missing", "缺口", comparison.getMissing());
        return new AiQueryResult("订单排程推演 " + comparison.getProductionOrderNo(),
                comparison.getConclusion(), columns, rows, blocks);
    }

    /** 每个推演口径一行；缺时间与缺产线都显示空串，避免前端出现 "null"。 */
    private List<Map<String, Object>> simulationRows(ScheduleSimulationComparison comparison) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ScheduleSimulationComparison.Plan plan : comparison.getPlans()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", plan.getLabel());
            row.put("feasible", plan.isFeasible() ? "可行" : "不可行");
            row.put("lineName", plan.getLineName() == null ? "" : plan.getLineName());
            row.put("startTime", plan.getStartTime() == null ? "" : plan.getStartTime().format(DATE_TIME_FORMATTER));
            row.put("endTime", plan.getEndTime() == null ? "" : plan.getEndTime().format(DATE_TIME_FORMATTER));
            row.put("workingMinutes", plan.getWorkingMinutes() == null ? "" : plan.getWorkingMinutes() + " 分钟");
            row.put("meetsDelivery", deliveryJudgement(plan, comparison.getDeliveryDate()));
            rows.add(row);
        }
        return rows;
    }

    /** 说明与阻塞：说明把描述和备注连成一句话，阻塞项没有时写"无"而不是留空。 */
    private List<Map<String, Object>> simulationDetailRows(ScheduleSimulationComparison comparison) {
        List<Map<String, Object>> detailRows = new ArrayList<>();
        for (ScheduleSimulationComparison.Plan plan : comparison.getPlans()) {
            StringBuilder detail = new StringBuilder(plan.getDescription());
            if (plan.getNotes() != null && !plan.getNotes().isEmpty()) {
                detail.append("；").append(String.join("；", plan.getNotes()));
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", plan.getLabel());
            row.put("detail", detail.toString());
            row.put("blockers", plan.getBlockers() == null || plan.getBlockers().isEmpty()
                    ? "无" : String.join("；", plan.getBlockers()));
            detailRows.add(row);
        }
        return detailRows;
    }

    /** 一段文本清单转成单列表格；清单为空时不加块，界面上不出现空表。 */
    private static void appendTextBlock(List<AiBlock> blocks, String title, String column, String header,
                                        List<String> items) {
        if (items == null || items.isEmpty()) return;
        blocks.add(new AiBlock(title, List.of(new AiColumn(column, header)),
                items.stream().map(item -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put(column, item);
                    return row;
                }).toList(), null));
    }

    /**
     * 交期判断。计划的 meetsDelivery 只说明「和订单交期比」的结果，
     * 订单本身没填交期时必须显示未知，不能把未填当成满足。
     */
    private String deliveryJudgement(ScheduleSimulationComparison.Plan plan, LocalDate deliveryDate) {
        String judgement;
        if ("COMPLETED".equals(plan.getCode())) {
            judgement = "不适用（订单已完工）";
        } else if (deliveryDate == null) {
            judgement = "未知（订单未填交期）";
        } else if (plan.getMeetsDelivery() == null) {
            judgement = "未知（该口径排不出完工时间）";
        } else {
            judgement = plan.getMeetsDelivery() ? "是" : "否";
        }
        return judgement;
    }

    /** 订单号换成内部主键；订单不存在时直接报错，不猜一个默认单。 */
    private Long resolveProductionOrderId(String orderNo) {
        Map<String, Object> header = queryMapper.selectOrderHeader(orderNo);
        Long productionOrderId = header == null ? null : toLong(header.get("productionOrderId"));
        if (productionOrderId == null) {
            throw new AiServiceException("未找到生产订单「" + orderNo + "」，请确认订单号");
        }
        return productionOrderId;
    }

    /** 已排产量：非组件计划、状态已生效，并按计划号去重后合计。 */
    private BigDecimal plannedOrderQuantity(List<Map<String, Object>> planRows) {
        return planRows.stream()
                .filter(row -> !"COMPONENT".equals(row.get("planType")))
                .filter(row -> List.of("RELEASED", "RUNNING", "FINISHED").contains(text(row.get("planStatus"))))
                .collect(Collectors.toMap(row -> text(row.get("planNo")),
                        row -> decimal(row.get("plannedQuantity")), (left, right) -> left))
                .values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 只保留审核通过的行。 */
    private List<Map<String, Object>> approvedRows(List<Map<String, Object>> rows) {
        return rows.stream().filter(row -> "APPROVED".equals(row.get("auditStatus"))).toList();
    }

    /** 按列名合计一个数量字段。 */
    private BigDecimal sumColumn(List<Map<String, Object>> rows, String key) {
        return rows.stream().map(row -> decimal(row.get(key))).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 订单进度的主行。 */
    private Map<String, Object> orderProgressMainRow(Map<String, Object> header, BigDecimal orderQuantity,
                                                     BigDecimal plannedQuantity, BigDecimal completedQuantity,
                                                     BigDecimal inboundQuantity, BigDecimal shippedQuantity) {
        Map<String, Object> mainRow = new LinkedHashMap<>();
        mainRow.put("productionOrderNo", text(header.get("productionOrderNo")));
        mainRow.put("productCode", text(header.get("productCode")));
        mainRow.put("productName", text(header.get("productName")));
        mainRow.put("customerName", text(header.get("customerName")));
        mainRow.put("orderQuantity", scale(orderQuantity));
        mainRow.put("plannedQuantity", scale(plannedQuantity));
        mainRow.put("completedQuantity", scale(completedQuantity));
        mainRow.put("inboundQuantity", scale(inboundQuantity));
        mainRow.put("shippedQuantity", scale(shippedQuantity));
        mainRow.put("progressRate", percentOf(completedQuantity, orderQuantity));
        mainRow.put("deliveryDate", formatDate(header.get("deliveryDate")));
        mainRow.put("status", orderStatusName(text(header.get("status"))));
        return mainRow;
    }

    /** 单条生产计划的展示行。 */
    private Map<String, Object> orderPlanRow(Map<String, Object> raw) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("planNo", text(raw.get("planNo")));
        row.put("lineName", text(raw.get("lineName")));
        row.put("plannedQuantity", scale(decimal(raw.get("plannedQuantity"))));
        row.put("plannedStartTime", formatDateTime(raw.get("plannedStartTime")));
        row.put("plannedEndTime", formatDateTime(raw.get("plannedEndTime")));
        row.put("planStatus", text(raw.get("planStatus")));
        row.put("workOrderNo", text(raw.get("workOrderNo")));
        row.put("workOrderPlannedQuantity", scale(decimal(raw.get("workOrderPlannedQuantity"))));
        row.put("workOrderCompletedQuantity", scale(decimal(raw.get("workOrderCompletedQuantity"))));
        row.put("workOrderStatus", workOrderStatusName(text(raw.get("workOrderStatus"))));
        return row;
    }

    /** 占比文案：分母为 0 时显示 0.00%。 */
    private static String percentOf(BigDecimal part, BigDecimal total) {
        if (total.signum() == 0) {
            return "0.00%";
        }
        return part.multiply(BigDecimal.valueOf(100)).divide(total, 2, RoundingMode.HALF_UP) + "%";
    }

    /** 订单进度的一句话结论。 */
    private String orderProgressSummary(Map<String, Object> header, BigDecimal orderQuantity,
                                        BigDecimal plannedQuantity, BigDecimal completedQuantity,
                                        BigDecimal inboundQuantity, BigDecimal shippedQuantity) {
        return "订单 " + text(header.get("productionOrderNo")) + "（" + text(header.get("productName")) + "）"
                + "订单量 " + num(orderQuantity) + "，已排产 " + num(plannedQuantity)
                + "，已完工 " + num(completedQuantity) + "，已入库 " + num(inboundQuantity)
                + "，已发货 " + num(shippedQuantity) + "；交期 " + formatDate(header.get("deliveryDate"))
                + "，状态 " + orderStatusName(text(header.get("status"))) + "。";
    }

    @Override
    public AiQueryResult queryEquipmentImpact(String equipmentKeyword, LocalDate dateFrom, LocalDate dateTo) {
        String keyword = StringUtils.hasText(equipmentKeyword) ? equipmentKeyword.trim() : null;
        LocalDate[] range = dateFrom == null && dateTo == null ? null : normalizeRange(dateFrom, dateTo, 1);
        List<Map<String, Object>> faultRows = queryMapper.selectEquipmentFaults(keyword,
                range == null ? null : range[0].atStartOfDay(),
                range == null ? null : range[1].plusDays(1).atStartOfDay());
        // A historical report only proves what was reported in the period. Show
        // current station associations for the live view, never historical impact.
        List<Long> stationIds = faultRows.stream()
                .map(row -> row.get("stationId"))
                .filter(Objects::nonNull)
                .map(this::toLong)
                .distinct()
                .toList();
        List<Map<String, Object>> taskRows = range != null || stationIds.isEmpty()
                ? List.of() : queryMapper.selectEquipmentAffectedTasks(stationIds);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> raw : faultRows) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("equipmentCode", text(raw.get("equipmentCode")));
            row.put("equipmentName", text(raw.get("equipmentName")));
            row.put("equipmentStatus", equipmentStatusName(text(raw.get("equipmentStatus"))));
            row.put("stationName", text(raw.get("stationName")));
            row.put("repairNo", text(raw.get("repairNo")));
            row.put("repairStatus", text(raw.get("repairStatus")));
            row.put("faultDescription", text(raw.get("faultDescription")));
            row.put("reportedTime", formatDateTime(raw.get("reportedTime")));
            rows.add(row);
        }

        List<Map<String, Object>> affected = new ArrayList<>();
        for (Map<String, Object> raw : taskRows) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("workOrderNo", text(raw.get("workOrderNo")));
            row.put("productionOrderNo", text(raw.get("productionOrderNo")));
            row.put("productName", text(raw.get("productName")));
            row.put("taskNo", text(raw.get("taskNo")));
            row.put("operationName", text(raw.get("operationName")));
            row.put("stationName", text(raw.get("stationName")));
            row.put("plannedQuantity", scale(decimal(raw.get("plannedQuantity"))));
            row.put("completedQuantity", scale(decimal(raw.get("completedQuantity"))));
            row.put("taskStatus", text(raw.get("taskStatus")));
            row.put("deliveryDate", formatDate(raw.get("deliveryDate")));
            affected.add(row);
        }

        String affectedOrders = affected.stream()
                .map(row -> text(row.get("productionOrderNo")))
                .filter(StringUtils::hasText)
                .distinct()
                .collect(Collectors.joining("、"));
        String scope = range == null ? "当前故障和开放报修" : range[0] + " 至 " + range[1] + "的在册设备报修记录";
        String summary;
        if (rows.isEmpty()) {
            summary = scope + "中没有匹配记录。";
        } else {
            long equipmentCount = faultRows.stream().map(row -> row.get("equipmentId")).distinct().count();
            long repairCount = faultRows.stream().map(row -> row.get("repairId")).filter(Objects::nonNull).distinct().count();
            String ordersPart = StringUtils.hasText(affectedOrders) ? "，涉及生产订单 " + affectedOrders : "";
            summary = scope + "涉及 " + equipmentCount + " 台设备、" + repairCount + " 张报修单。";
            if (range == null) {
                if (!stationIds.isEmpty()) {
                    summary += "关联工位当前有 " + affected.size() + " 个未完成任务" + ordersPart
                            + "。请结合排程和现场状态核对影响。";
                }
                long unlinked = faultRows.stream().filter(row -> row.get("stationId") == null)
                        .map(row -> row.get("equipmentId")).distinct().count();
                if (unlinked > 0) {
                    summary += "其中 " + unlinked + " 台设备未关联有效工位，无法核对对应任务。";
                }
            }
        }

        List<AiBlock> blocks = range == null && !stationIds.isEmpty()
                ? List.of(new AiBlock("关联工位的当前未完成任务", equipmentTaskColumns(), limit(affected), null))
                : List.of();
        return new AiQueryResult(range == null ? "设备故障影响" : "设备报修记录",
                summary, equipmentColumns(), rows, blocks);
    }

    @Override
    public AiQueryResult queryCapacityFeasibility(String productKeyword, int extraQuantity,
                                                  LocalDate dateFrom, LocalDate dateTo) {
        if (!StringUtils.hasText(productKeyword)) {
            throw new AiServiceException("产品名称不能为空");
        }
        if (extraQuantity <= 0) {
            throw new AiServiceException("追加数量必须大于0");
        }
        LocalDate[] range = normalizeRange(dateFrom, dateTo, 7);
        List<MdmProduct> products = productMapper.selectList(new LambdaQueryWrapper<MdmProduct>()
                .eq(MdmProduct::getDeletedFlag, 0)
                .and(wrapper -> wrapper.like(MdmProduct::getProductCode, productKeyword.trim())
                        .or().like(MdmProduct::getProductName, productKeyword.trim()))
                .orderByAsc(MdmProduct::getProductId));
        if (products == null || products.isEmpty()) {
            return emptyResult("产能测算",
                    "未找到与「" + productKeyword + "」匹配的产品，请确认产品名称或编码。",
                    capacityColumns());
        }
        MdmProduct product = products.get(0);
        List<Map<String, Object>> bomRows = queryMapper.selectProductBom(product.getProductId());
        List<Long> materialIds = bomRows.stream()
                .map(row -> resolveSingleMaterialId(text(row.get("materialCode"))))
                .filter(Objects::nonNull)
                .toList();
        Map<Long, BigDecimal> stockMap = queryMapper.selectAvailableStock(materialIds).stream()
                .collect(Collectors.toMap(row -> toLong(row.get("materialId")),
                        row -> decimal(row.get("availableQuantity")), (left, right) -> left));
        List<Map<String, Object>> lineRows = queryMapper.selectCapableLines(product.getProductId());

        long workDays = Math.max(1, range[0].datesUntil(range[1].plusDays(1)).count());
        var calendar = lineCalendars.availability(range[0], range[1]);

        BomCapacityRun bomRun = collectBomShortages(bomRows, stockMap, extraQuantity);
        List<Map<String, Object>> rows = bomRun.rows();
        int bottleneck = bomRun.bottleneck();
        LineCapacityRun lineRun = bestLineCapacity(lineRows, calendar, extraQuantity);

        BigDecimal unitMinutes = lineRun.unitMinutes();
        BigDecimal lineCapacity = lineRun.lineCapacity();
        BigDecimal availableMinutes = lineRun.availableMinutes();
        BigDecimal requiredMinutes = unitMinutes.multiply(BigDecimal.valueOf(extraQuantity));
        boolean materialReady = bottleneck >= extraQuantity;
        boolean capacityReady = lineCapacity.compareTo(BigDecimal.valueOf(extraQuantity)) >= 0;
        String conclusion = capacityConclusion(materialReady, capacityReady);

        Map<String, Object> mainRow = capacityMainRow(product, extraQuantity, bomRows.size(), bottleneck,
                unitMinutes, requiredMinutes, availableMinutes, lineCapacity, conclusion);

        List<AiBlock> blocks = new ArrayList<>();
        blocks.add(new AiBlock("BOM 净需求与库存", capacityBomColumns(), rows, null));
        blocks.add(new AiBlock("可用产线", capacityLineColumns(), lineRows, null));

        String summary = capacitySummary(range, product, extraQuantity, bomRows.size(), rows, bottleneck,
                availableMinutes, unitMinutes, lineCapacity, conclusion);
        return new AiQueryResult("产能测算 " + product.getProductName(), summary, capacityColumns(),
                List.of(mainRow), blocks);
    }

    /** BOM 净需求核算的累计结果：逐行展示数据 + 最紧的物料可支持台数。 */
    private record BomCapacityRun(List<Map<String, Object>> rows, int bottleneck) {}

    /** 逐个直接子项核算净需求，并累计最紧的物料上限。 */
    private BomCapacityRun collectBomShortages(List<Map<String, Object>> bomRows, Map<Long, BigDecimal> stockMap,
                                               int extraQuantity) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int bottleneck = Integer.MAX_VALUE;
        for (Map<String, Object> bom : bomRows) {
            Long materialId = resolveSingleMaterialId(text(bom.get("materialCode")));
            BigDecimal unitQuantity = decimal(bom.get("unitQuantity"));
            BigDecimal lossRate = decimal(bom.get("lossRate"));
            BigDecimal grossPerUnit = unitQuantity.multiply(BigDecimal.ONE.add(lossRate));
            BigDecimal required = quantityPolicyDemand(materialId, grossPerUnit, extraQuantity);
            BigDecimal available = materialId == null ? BigDecimal.ZERO : stockMap.getOrDefault(materialId, BigDecimal.ZERO);
            BigDecimal gap = required.subtract(available).max(BigDecimal.ZERO);
            BigDecimal canProduce = grossPerUnit.signum() == 0
                    ? BigDecimal.valueOf(Integer.MAX_VALUE)
                    : available.divide(grossPerUnit, 0, RoundingMode.DOWN);
            bottleneck = Math.min(bottleneck, canProduce.intValue());
            rows.add(bomShortageRow(bom, unitQuantity, lossRate, required, available, gap));
        }
        return new BomCapacityRun(rows, bottleneck);
    }

    /** 按物料数量策略换算实际需求，物料档案缺失时沿用毛需求。 */
    private BigDecimal quantityPolicyDemand(Long materialId, BigDecimal grossPerUnit, int extraQuantity) {
        BigDecimal required = grossPerUnit.multiply(BigDecimal.valueOf(extraQuantity));
        if (materialId == null) {
            return required;
        }
        var material = materialMapper.selectById(materialId);
        if (material == null) {
            return required;
        }
        return quantityPolicy.executionDemand(material, required);
    }

    /** 单条 BOM 子项的缺口展示行。 */
    private Map<String, Object> bomShortageRow(Map<String, Object> bom, BigDecimal unitQuantity,
                                               BigDecimal lossRate, BigDecimal required,
                                               BigDecimal available, BigDecimal gap) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("materialCode", text(bom.get("materialCode")));
        row.put("materialName", text(bom.get("materialName")));
        row.put("unitQuantity", scale(unitQuantity));
        row.put("lossRate", scale(lossRate));
        row.put("requiredQuantity", scale(required));
        row.put("availableQuantity", scale(available));
        row.put("gapQuantity", scale(gap));
        row.put("unitName", text(bom.get("unitName")));
        row.put("stockStatus", gap.signum() == 0 ? "满足" : "缺料");
        return row;
    }

    /** 产线能力核算结果。 */
    private record LineCapacityRun(BigDecimal unitMinutes, BigDecimal lineCapacity, BigDecimal availableMinutes) {}

    /** 在所有可用产线里挑出可支持台数最多的一条，取它的单台工时与剩余工时。 */
    private LineCapacityRun bestLineCapacity(List<Map<String, Object>> lineRows,
                                             Map<Long, com.neuedu.ems.service.ai.AiLineCalendarService.Availability> calendar,
                                             int extraQuantity) {
        BigDecimal unitMinutes = BigDecimal.ZERO;
        BigDecimal lineCapacity = BigDecimal.ZERO;
        BigDecimal availableMinutes = BigDecimal.ZERO;
        for (var line : lineRows) {
            var remaining = calendar.getOrDefault(toLong(line.get("lineId")),
                    new com.neuedu.ems.service.ai.AiLineCalendarService.Availability(BigDecimal.ZERO, BigDecimal.ZERO))
                    .remaining();
            var minutes = decimal(line.get("unitMinutes"));
            var capacity = minutes.signum() <= 0 ? BigDecimal.ZERO : remaining.divide(minutes, 0, RoundingMode.DOWN);
            if (capacity.compareTo(lineCapacity) > 0) {
                lineCapacity = capacity;
                unitMinutes = minutes;
                availableMinutes = remaining;
            }
        }
        return new LineCapacityRun(unitMinutes, lineCapacity, availableMinutes);
    }

    /** 产能初筛结论。 */
    private static String capacityConclusion(boolean materialReady, boolean capacityReady) {
        if (!materialReady) {
            return "存在直接子项缺口，需核对库存或组件补产";
        }
        if (!capacityReady) {
            return "当前日历余量不足，需重新排程";
        }
        return "初步满足，需正式排程验证";
    }

    /** 产能测算的主行。 */
    private Map<String, Object> capacityMainRow(MdmProduct product, int extraQuantity, int bomItemCount,
                                                int bottleneck, BigDecimal unitMinutes,
                                                BigDecimal requiredMinutes, BigDecimal availableMinutes,
                                                BigDecimal lineCapacity, String conclusion) {
        Map<String, Object> mainRow = new LinkedHashMap<>();
        mainRow.put("productCode", product.getProductCode());
        mainRow.put("productName", product.getProductName());
        mainRow.put("extraQuantity", extraQuantity);
        mainRow.put("bomItemCount", bomItemCount);
        mainRow.put("materialLimitQuantity", bottleneck == Integer.MAX_VALUE ? "不限" : bottleneck);
        mainRow.put("lineUnitMinutes", scale(unitMinutes));
        mainRow.put("requiredMinutes", scale(requiredMinutes));
        mainRow.put("availableMinutes", scale(availableMinutes));
        mainRow.put("capacityLimitQuantity", lineCapacity);
        mainRow.put("conclusion", conclusion);
        return mainRow;
    }

    /** 产能测算的一句话结论。 */
    private String capacitySummary(LocalDate[] range, MdmProduct product, int extraQuantity, int bomItemCount,
                                   List<Map<String, Object>> rows, int bottleneck, BigDecimal availableMinutes,
                                   BigDecimal unitMinutes, BigDecimal lineCapacity, String conclusion) {
        long shortageItems = rows.stream().filter(row -> "缺料".equals(row.get("stockStatus"))).count();
        String materialPart = shortageItems == 0
                ? "BOM " + bomItemCount + " 项物料全部满足"
                : "BOM " + bomItemCount + " 项中 " + shortageItems + " 项缺料";
        String limitPart = bottleneck == Integer.MAX_VALUE ? "不限" : String.valueOf(bottleneck);
        return "追加 " + extraQuantity + " 台 " + product.getProductName()
                + "：" + materialPart
                + "，物料最多可支持 " + limitPart + " 台；"
                + range[0] + " 至 " + range[1] + " 可用工时 " + num(availableMinutes)
                + " 分钟，单台工时 " + num(unitMinutes) + " 分钟，产能最多 " + lineCapacity + " 台；"
                + "结论：" + conclusion + "。此为直接BOM和路线工时初筛，尚需核对组件补产、共享工位、老化批次及质检依赖。";
    }

    @Override
    public AiQueryResult queryWorkOrdersByProduct(String productKeyword, String status) {
        String keyword = trimOrNull(productKeyword);
        String normalizedStatus = upperOrNull(status);
        List<Map<String, Object>> rawRows = queryMapper.selectWorkOrdersByProduct(keyword, normalizedStatus);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> raw : rawRows) {
            rows.add(workOrderListRow(raw));
        }
        String scope = keyword == null ? "全部产品" : "「" + keyword + "」相关产品";
        String statusText = workOrderStatusFilterText(normalizedStatus);
        if (rows.isEmpty()) {
            return emptyResult("工单清单", scope + statusText + "工单，一张也没有。", workOrderListColumns());
        }
        BigDecimal planned = rows.stream().map(row -> decimal(row.get("plannedQuantity")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal completed = rows.stream().map(row -> decimal(row.get("completedQuantity")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long finished = rows.stream().filter(row -> "已完成".equals(row.get("status"))).count();
        long componentCount = rows.stream().filter(row -> "组件工单".equals(row.get("workOrderType"))).count();
        String summary = scope + statusText + "共 " + rows.size() + " 张工单（其中组件工单 " + componentCount
                + " 张），计划量合计 " + num(planned) + "，完成量合计 " + num(completed)
                + "，已完成 " + finished + " 张。";
        return new AiQueryResult("工单清单", summary, workOrderListColumns(), limit(rows), List.of());
    }

    /**
     * 按产品与台数逐层展开 BOM 算用量。
     *
     * <p>用户问「L200 的螺钉一共要多少颗」时，要的是数量，不是 BOM 有几条明细。
     * 这里直接复用主数据 BOM 需求分析的递归展开，保证和工单领料、采购需求是同一套口径：
     * 子 BOM 逐层拆到叶子，按发料粒度取整，损耗率计入。</p>
     */
    @Override
    public AiQueryResult queryBomRequirement(String productKeyword, String materialKeyword,
                                             BigDecimal quantity, LocalDate asOf) {
        String keyword = trimOrNull(productKeyword);
        if (keyword == null) {
            throw new AiServiceException("产品名称不能为空");
        }
        BigDecimal produceQuantity = quantity == null || quantity.signum() <= 0 ? BigDecimal.ONE : quantity;
        LocalDate effectiveDate = asOf == null ? LocalDate.now() : asOf;
        List<MdmProduct> products = productMapper.selectList(new LambdaQueryWrapper<MdmProduct>()
                .eq(MdmProduct::getDeletedFlag, 0)
                .and(wrapper -> wrapper.like(MdmProduct::getProductCode, keyword)
                        .or().like(MdmProduct::getProductName, keyword))
                .orderByAsc(MdmProduct::getProductId));
        if (products == null || products.isEmpty()) {
            return emptyResult("BOM 用量",
                    "未找到与「" + keyword + "」匹配的产品，请确认产品名称或编码。",
                    bomRequirementColumns());
        }
        MdmProduct product = products.get(0);
        if (bomExpansionService == null) {
            throw new AiServiceException("BOM 用量分析当前不可用，请稍后重试");
        }
        Map<String, Object> bom = queryMapper.selectReleasedBom(product.getProductId(), effectiveDate);
        if (bom == null || bom.get("bomId") == null) {
            return emptyResult("BOM 用量",
                    product.getProductName() + " 在 " + effectiveDate + " 没有已发布的 BOM，算不出用量。"
                            + "请先在 BOM 管理里确认该产品已发布且生效日期覆盖今天。",
                    bomRequirementColumns());
        }
        Long bomId = toLong(bom.get("bomId"));
        BomExpansion expansion = bomExpansionService.expand(bomId, produceQuantity, effectiveDate, false);
        return bomRequirementResult(product, expansion, produceQuantity, materialKeyword);
    }

    /** 把 BOM 展开结果转成用量回答：主表给合计，附加区块给逐层结构。 */
    private AiQueryResult bomRequirementResult(MdmProduct product, BomExpansion expansion,
                                               BigDecimal produceQuantity, String materialKeyword) {
        List<BomExpansion.Demand> materials = expansion.grossMaterials() == null
                ? List.of() : expansion.grossMaterials();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (BomExpansion.Demand demand : materials) {
            rows.add(bomRequirementRow(demand, produceQuantity));
        }
        List<Map<String, Object>> focused = filterByMaterial(rows, materialKeyword);
        String summary = bomRequirementSummary(product, expansion, produceQuantity, rows, focused, materialKeyword);
        List<AiBlock> blocks = new ArrayList<>();
        List<Map<String, Object>> layerRows = bomLayerRows(expansion.tree(), 1);
        if (!layerRows.isEmpty()) {
            blocks.add(new AiBlock("BOM 逐层结构", bomLayerColumns(), layerRows, null));
        }
        return new AiQueryResult("BOM 用量 " + product.getProductName(), summary,
                bomRequirementColumns(), limit(focused), blocks);
    }

    /** 单条物料用量行；单台用量由合计除以台数还原，方便用户直接看「一台要几个」。 */
    private Map<String, Object> bomRequirementRow(BomExpansion.Demand demand, BigDecimal produceQuantity) {
        BigDecimal total = demand.executionQuantity() == null ? BigDecimal.ZERO : demand.executionQuantity();
        BigDecimal perUnit = produceQuantity.signum() == 0 ? BigDecimal.ZERO
                : total.divide(produceQuantity, 4, RoundingMode.HALF_UP);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("materialCode", demand.materialCode());
        row.put("materialName", demand.materialName());
        row.put("materialType", materialTypeName(demand.materialType()));
        row.put("perUnitQuantity", scale(perUnit));
        row.put("totalQuantity", scale(total));
        row.put("unitName", demand.unitName());
        row.put("issueStep", scale(demand.issueStep()));
        return row;
    }

    /** 用户点名某个物料时，主表只留它；没点名就返回全部。 */
    private List<Map<String, Object>> filterByMaterial(List<Map<String, Object>> rows, String materialKeyword) {
        String keyword = trimOrNull(materialKeyword);
        if (keyword == null) {
            return rows;
        }
        List<Map<String, Object>> matched = rows.stream()
                .filter(row -> text(row.get("materialCode")).contains(keyword)
                        || text(row.get("materialName")).contains(keyword))
                .toList();
        // 点名的物料不在 BOM 里时返回空表，由结论说明「没用这个料」，不能退回全部物料让用户误以为都是它。
        return new ArrayList<>(matched);
    }

    /** 用量结论：先给总数，再点出用户点名物料的数字，最后交代口径。 */
    private String bomRequirementSummary(MdmProduct product, BomExpansion expansion, BigDecimal produceQuantity,
                                         List<Map<String, Object>> rows, List<Map<String, Object>> focused,
                                         String materialKeyword) {
        String scope = "生产 " + num(produceQuantity) + " 台 " + product.getProductName()
                + "（BOM " + expansion.bomCode() + " " + expansion.bomVersion() + "，"
                + expansion.asOf() + " 生效）";
        if (rows.isEmpty()) {
            return scope + " 展开后没有任何物料，请检查 BOM 明细。";
        }
        String keyword = trimOrNull(materialKeyword);
        if (keyword != null && focused.isEmpty()) {
            return scope + " 的 BOM 里没有与「" + keyword + "」匹配的物料，共 " + rows.size()
                    + " 种物料、去重后 " + expansion.distinctMaterialCount() + " 种，可换个名称再问。";
        }
        StringBuilder summary = new StringBuilder(scope).append(" 共需 ").append(rows.size())
                .append(" 种物料（原材料 ").append(expansion.rawMaterialCount()).append(" 种），");
        if (keyword != null) {
            Map<String, Object> hit = focused.get(0);
            summary.append("其中「").append(hit.get("materialName")).append("」需 ")
                    .append(num(hit.get("totalQuantity"))).append(hit.get("unitName"))
                    .append("（单台 ").append(num(hit.get("perUnitQuantity"))).append(hit.get("unitName")).append("）；");
        }
        summary.append("口径：递归展开子 BOM 到叶子物料，按发料粒度取整并计入损耗率，"
                + "与工单领料同一套算法；不预留库存、不生成单据。");
        return summary.toString();
    }

    /** 逐层结构行，层级从 1 开始，方便看清哪一层是半成品、哪一层是叶子。 */
    private List<Map<String, Object>> bomLayerRows(List<BomExpansion.Node> nodes, int level) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (nodes == null) {
            return rows;
        }
        for (BomExpansion.Node node : nodes) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("level", level);
            row.put("materialCode", node.materialCode());
            row.put("materialName", node.materialName());
            row.put("materialType", materialTypeName(node.materialType()));
            row.put("quantity", scale(node.quantity()));
            row.put("unitName", node.unitName());
            row.put("childBomVersion", node.childBomVersion() == null ? "" : node.childBomVersion());
            rows.add(row);
            rows.addAll(bomLayerRows(node.children(), level + 1));
        }
        return rows;
    }

    /** 物料类型的中文名，报表里给编码用户看不懂。 */
    private String materialTypeName(String type) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case "RAW" -> "原材料";
            case "AUXILIARY" -> "辅料";
            case "SEMI_FINISHED" -> "自制半成品";
            case "FINISHED" -> "成品";
            default -> type;
        };
    }

    private List<AiColumn> bomRequirementColumns() {
        return List.of(
                new AiColumn("materialCode", "物料编码"),
                new AiColumn("materialName", "物料名称"),
                new AiColumn("materialType", "物料类型"),
                new AiColumn("perUnitQuantity", "单台用量"),
                new AiColumn("totalQuantity", "合计用量"),
                new AiColumn("unitName", "单位"),
                new AiColumn("issueStep", "发料粒度"));
    }

    private List<AiColumn> bomLayerColumns() {
        return List.of(
                new AiColumn("level", "层级"),
                new AiColumn("materialCode", "物料编码"),
                new AiColumn("materialName", "物料名称"),
                new AiColumn("materialType", "物料类型"),
                new AiColumn("quantity", "本级用量"),
                new AiColumn("unitName", "单位"),
                new AiColumn("childBomVersion", "子 BOM 版本"));
    }

    /** 去掉首尾空白，无内容时返回 null。 */
    private static String trimOrNull(String value) {        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    /** 去空白并转大写，无内容时返回 null。 */
    private static String upperOrNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    /** 单张工单清单的展示行。 */
    private Map<String, Object> workOrderListRow(Map<String, Object> raw) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("workOrderNo", text(raw.get("workOrderNo")));
        row.put("workOrderType", workOrderTypeName(text(raw.get("workOrderType"))));
        row.put("productCode", text(raw.get("productCode")));
        row.put("productName", text(raw.get("productName")));
        row.put("plannedQuantity", scale(decimal(raw.get("plannedQuantity"))));
        row.put("completedQuantity", scale(decimal(raw.get("completedQuantity"))));
        row.put("status", workOrderStatusName(text(raw.get("status"))));
        row.put("productionOrderNo", text(raw.get("productionOrderNo")));
        row.put("lineName", text(raw.get("lineName")));
        row.put("parentWorkOrderNo", text(raw.get("parentWorkOrderNo")));
        row.put("createdTime", formatDateTime(raw.get("createdTime")));
        return row;
    }

    @Override
    public AiQueryResult queryOrdersByCustomer(String customerKeyword) {
        String keyword = StringUtils.hasText(customerKeyword) ? customerKeyword.trim() : null;
        List<Map<String, Object>> rawRows = queryMapper.selectOrdersByCustomer(keyword);
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal orderTotal = BigDecimal.ZERO;
        BigDecimal completedTotal = BigDecimal.ZERO;
        for (Map<String, Object> raw : rawRows) {
            BigDecimal orderQuantity = decimal(raw.get("orderQuantity"));
            BigDecimal completedQuantity = decimal(raw.get("completedQuantity"));
            orderTotal = orderTotal.add(orderQuantity);
            completedTotal = completedTotal.add(completedQuantity);
            rows.add(customerOrderRow(raw, orderQuantity, completedQuantity));
        }
        String scope = keyword == null ? "全部客户" : "客户「" + keyword + "」";
        if (rows.isEmpty()) {
            return emptyResult("客户订单", scope + "下没有符合条件的生产订单。", customerOrderColumns());
        }
        String summary = scope + "共 " + rows.size() + " 张生产订单，订单量合计 " + num(orderTotal)
                + "，已完工合计 " + num(completedTotal) + "。";
        return new AiQueryResult("客户订单", summary, customerOrderColumns(), limit(rows), List.of());
    }

    /** 单张客户生产订单的展示行。 */
    private Map<String, Object> customerOrderRow(Map<String, Object> raw, BigDecimal orderQuantity,
                                                 BigDecimal completedQuantity) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("productionOrderNo", text(raw.get("productionOrderNo")));
        row.put("customerName", text(raw.get("customerName")));
        row.put("productCode", text(raw.get("productCode")));
        row.put("productName", text(raw.get("productName")));
        row.put("orderQuantity", scale(orderQuantity));
        row.put("completedQuantity", scale(completedQuantity));
        row.put("progressRate", percentOf(completedQuantity, orderQuantity));
        row.put("deliveryDate", formatDate(raw.get("deliveryDate")));
        row.put("status", orderStatusName(text(raw.get("status"))));
        return row;
    }

    @Override
    public AiQueryResult queryPendingInspections(String inspectionType) {
        String normalizedType = StringUtils.hasText(inspectionType) ? inspectionType.trim().toUpperCase(Locale.ROOT) : null;
        List<Map<String, Object>> rawRows = queryMapper.selectPendingInspections(normalizedType);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> raw : rawRows) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("inspectionNo", text(raw.get("inspectionNo")));
            row.put("inspectionType", inspectionTypeName(text(raw.get("inspectionType"))));
            row.put("workOrderNo", text(raw.get("workOrderNo")));
            row.put("productName", text(raw.get("productName")));
            row.put("materialName", text(raw.get("materialName")));
            row.put("batchNo", text(raw.get("batchNo")));
            row.put("sampleQuantity", scale(decimal(raw.get("sampleQuantity"))));
            row.put("status", documentStatusName(text(raw.get("status"))));
            row.put("createdTime", formatDateTime(raw.get("createdTime")));
            rows.add(row);
        }
        if (rows.isEmpty()) {
            return emptyResult("待检检验单", "当前没有尚未完成的检验单。", pendingInspectionColumns());
        }
        Map<String, Long> byType = rows.stream()
                .collect(Collectors.groupingBy(row -> text(row.get("inspectionType")), LinkedHashMap::new, Collectors.counting()));
        String detail = byType.entrySet().stream()
                .map(entry -> entry.getKey() + " " + entry.getValue() + " 张")
                .collect(Collectors.joining("、"));
        String summary = "当前共 " + rows.size() + " 张检验单尚未完成：" + detail + "。检验单需在检验单管理中提交检验结果后才会进入放行和入库环节。";
        return new AiQueryResult("待检检验单", summary, pendingInspectionColumns(), limit(rows), List.of());
    }

    /**
     * 按关键词解析物料 ID 列表。返回 null 表示不限制物料，返回空列表表示关键词没有匹配到物料。
     */
    private List<Long> resolveMaterialIds(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return null;
        }
        String trimmed = keyword.trim();
        List<MdmMaterial> materials = materialMapper.selectList(new LambdaQueryWrapper<MdmMaterial>()
                .eq(MdmMaterial::getDeletedFlag, 0)
                .and(wrapper -> wrapper.like(MdmMaterial::getMaterialCode, trimmed)
                        .or().like(MdmMaterial::getMaterialName, trimmed)
                        .or().like(MdmMaterial::getMaterialType, trimmed))
                .orderByAsc(MdmMaterial::getMaterialId));
        if (materials == null || materials.isEmpty()) {
            // 关键词可能是品类中文名，例如“控制板”“传感器”，再按中文品类同义词匹配一次。
            String alias = categoryAlias(trimmed);
            if (alias != null) {
                materials = materialMapper.selectList(new LambdaQueryWrapper<MdmMaterial>()
                        .eq(MdmMaterial::getDeletedFlag, 0)
                        .like(MdmMaterial::getMaterialName, alias)
                        .orderByAsc(MdmMaterial::getMaterialId));
            }
        }
        if (materials == null || materials.isEmpty()) {
            return List.of();
        }
        return materials.stream().map(MdmMaterial::getMaterialId).filter(Objects::nonNull).toList();
    }

    /**
     * 将物料编码精确解析为物料 ID，用于 BOM 测算。
     */
    private Long resolveSingleMaterialId(String materialCode) {
        if (!StringUtils.hasText(materialCode)) {
            return null;
        }
        List<MdmMaterial> materials = materialMapper.selectList(new LambdaQueryWrapper<MdmMaterial>()
                .eq(MdmMaterial::getDeletedFlag, 0)
                .eq(MdmMaterial::getMaterialCode, materialCode)
                .last("LIMIT 1"));
        return materials == null || materials.isEmpty() ? null : materials.get(0).getMaterialId();
    }

    /**
     * 中文品类同义词映射，用于“控制板”“传感器”等口语化品类查询。
     */
    private String categoryAlias(String keyword) {
        String alias = null;
        if (keyword.contains("控制板") || keyword.contains("主控") || keyword.contains("驱动板")) {
            alias = "主控驱动板";
        } else if (keyword.contains("传感器")) {
            alias = "传感器";
        } else if (keyword.contains("灯板") || keyword.contains("LED")) {
            alias = "LED灯板";
        } else if (keyword.contains("外壳")) {
            alias = "外壳";
        } else if (keyword.contains("灯臂")) {
            alias = "灯臂";
        } else if (keyword.contains("底座")) {
            alias = "底座";
        } else if (keyword.contains("螺钉") || keyword.contains("紧固件")) {
            alias = "螺钉";
        }
        return alias;
    }

    /**
     * 将仓库编码解析为仓库名称，未配置仓库时返回编码本身。
     */
    private String resolveWarehouseName(String warehouseCode) {
        List<WmsWarehouse> warehouses = warehouseMapper.selectList(new LambdaQueryWrapper<WmsWarehouse>()
                .eq(WmsWarehouse::getDeletedFlag, 0)
                .eq(WmsWarehouse::getWarehouseCode, warehouseCode)
                .last("LIMIT 1"));
        if (warehouses == null || warehouses.isEmpty() || !StringUtils.hasText(warehouses.get(0).getWarehouseName())) {
            return warehouseCode;
        }
        return warehouses.get(0).getWarehouseName();
    }

    private String buildInventoryFlowSummary(List<Map<String, Object>> rows, LocalDate begin, LocalDate end) {
        if (rows.isEmpty()) {
            return begin + " 至 " + end + " 期间没有符合条件的库存流水记录。";
        }
        if (countMaterials(rows) > 1) {
            return begin + " 至 " + end + " 共 " + rows.size() + " 种物料发生收发；请按物料及单位查看入库、出库和期末数量。"
                    + "期初与期末均按库存流水实际日期边界累加计算。";
        }
        BigDecimal inbound = rows.stream().map(row -> decimal(row.get("inboundQuantity")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal outbound = rows.stream().map(row -> decimal(row.get("outboundQuantity")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal closing = rows.stream().map(row -> decimal(row.get("closingQuantity")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return begin + " 至 " + end + " 共 " + rows.size() + " 种物料发生收发，本期入库合计 "
                + num(inbound) + "，本期出库合计 " + num(outbound) + "，期末合计 " + num(closing)
                + "。期初与期末均按库存流水实际日期边界累加计算。";
    }

    private String normalizeWarehouseType(String warehouseType) {
        if (!StringUtils.hasText(warehouseType)) {
            return null;
        }
        String trimmed = warehouseType.trim().toUpperCase(Locale.ROOT);
        // 未登记的类型保持原值返回，调用方按未知类型处理，不在这里改成别的值。
        return trimmed;
    }

    private LocalDate[] normalizeRange(LocalDate dateFrom, LocalDate dateTo, int defaultDays) {
        LocalDate end = dateTo == null ? LocalDate.now() : dateTo;
        LocalDate begin = dateFrom == null ? end.minusDays(defaultDays - 1L) : dateFrom;
        if (begin.isAfter(end)) {
            LocalDate temp = begin;
            begin = end;
            end = temp;
        }
        if (begin.plusDays(366).isBefore(end)) {
            throw new AiServiceException("查询时间范围不能超过366天");
        }
        return new LocalDate[]{begin, end};
    }

    private List<Map<String, Object>> limit(List<Map<String, Object>> rows) {
        if (rows == null) {
            return List.of();
        }
        return rows.size() <= MAX_DETAIL_ROWS ? rows : new ArrayList<>(rows.subList(0, MAX_DETAIL_ROWS));
    }

    private AiQueryResult emptyResult(String title, String summary, List<AiColumn> columns) {
        return new AiQueryResult(title, summary, columns, List.of(), List.of());
    }

    private Map<String, Object> barChart(String title, List<String> categories, String seriesName, List<?> data) {
        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("type", "bar");
        chart.put("title", title);
        chart.put("categories", categories);
        Map<String, Object> series = new LinkedHashMap<>();
        series.put("name", seriesName);
        series.put("data", data);
        chart.put("series", List.of(series));
        return chart;
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal bigDecimal) {
            return bigDecimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return value == null ? BigDecimal.ZERO : parseDecimal(value.toString());
    }

    /** 文本解析为数量，非法文本按 0 处理。 */
    private BigDecimal parseDecimal(String text) {
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * 数量统一保留两位小数并去掉无意义的末尾零，避免前端出现 0.0000 这类展示。
     */
    private BigDecimal scale(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal rounded = value.setScale(2, RoundingMode.HALF_UP);
        BigDecimal stripped = rounded.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    /** 数量转成不带多余零的整数/小数字符串，用于「还差 N 台」这类提示。 */
    private String plainQuantity(BigDecimal value) {
        return scale(value).toPlainString();
    }

    /**
     * 汇总文案中的数量展示，去掉无意义的末尾零。
     */
    private String num(Object value) {
        return scale(decimal(value)).stripTrailingZeros().toPlainString();
    }

    private long toLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return parseLong(value.toString());
    }

    /** 文本解析为整数，非法文本按 0 处理。 */
    private long parseLong(String text) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private boolean matchesLine(Map<String, Object> row, String lineKeyword) {
        String keyword = lineKeyword.trim();
        return text(row.get("lineName")).contains(keyword) || text(row.get("lineCode")).contains(keyword);
    }

    private String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private String formatDateTime(Object value) {
        LocalDateTime dateTime = toLocalDateTime(value);
        if (dateTime != null) {
            return dateTime.format(DATE_TIME_FORMATTER);
        }
        return value == null ? "" : value.toString();
    }

    /** 把时间类值统一转成 LocalDateTime，非时间类型返回 null。 */
    private LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        return value instanceof java.sql.Timestamp timestamp ? timestamp.toLocalDateTime() : null;
    }

    private String formatDate(Object value) {
        if (value == null) {
            return "";
        }
        LocalDate date = toLocalDate(value);
        if (date == null) {
            return value.toString();
        }
        return date.format(DATE_FORMATTER);
    }

    /** 把日期类值统一转成 LocalDate，非日期类型返回 null。 */
    private LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.toLocalDate();
        }
        return value instanceof java.sql.Date sqlDate ? sqlDate.toLocalDate() : null;
    }

    private String workOrderStatusName(String code) {
        return WORK_ORDER_STATUS_NAMES.getOrDefault(code, code);
    }

    /**
     * 把工单状态过滤条件说成人话，拼进结论里。
     * 「没开工」和「没完工」筛的是两批不同的工单，结论里不写清楚，
     * 用户会以为拿到的是全部工单，或者以为数字算错了。
     */
    private String workOrderStatusFilterText(String status) {
        if (!StringUtils.hasText(status)) {
            return "";
        }
        return switch (status) {
            case "NOT_STARTED" -> "中还没开工的";
            case "UNFINISHED" -> "中未完工的";
            default -> "中" + workOrderStatusName(status) + "的";
        };
    }

    private String workOrderTypeName(String code) {
        return "COMPONENT".equals(code) ? "组件工单" : "整机根工单";
    }

    private String warehouseTypeName(String code) {
        return WAREHOUSE_TYPE_NAMES.getOrDefault(code, code);
    }

    private String qualityStatusName(String code) {
        if (!StringUtils.hasText(code)) {
            return "";
        }
        return switch (code) {
            case "PASS" -> "合格";
            case "FAIL" -> "不合格";
            case "CONCESSION" -> "让步接收";
            case "PENDING" -> "待检";
            default -> code;
        };
    }

    private String orderStatusName(String code) {
        if (!StringUtils.hasText(code)) {
            return "";
        }
        return switch (code) {
            case "DRAFT" -> "新建";
            case "PLANNED" -> "已排产";
            case "RUNNING" -> "生产中";
            case "FINISHED" -> "已完成";
            case "CLOSED" -> "已关闭";
            default -> code;
        };
    }

    /**
     * 生产计划状态中文名称。
     *
     * <p>与 {@link #orderStatusName} 分开，因为计划走的是 RELEASED 这套文档状态，
     * 不是 DRAFT/RUNNING/FINISHED 的生产状态。</p>
     */
    private String planStatusName(String code) {
        if (!StringUtils.hasText(code)) {
            return "";
        }
        return switch (code) {
            case "DRAFT" -> "草稿";
            case "APPROVED" -> "已审批";
            case "RELEASED" -> "已生效";
            case "CLOSED" -> "已关闭";
            default -> code;
        };
    }

    /**
     * 产线状态中文名称。
     */
    private String lineStatusName(String code) {
        if (!StringUtils.hasText(code)) {
            return "";
        }
        return switch (code) {
            case "RUNNING" -> "运行中";
            case "IDLE" -> "空闲";
            case "STOPPED" -> "停机";
            case "MAINTAINING" -> "保养中";
            case "DISABLE" -> "已停用";
            default -> code;
        };
    }

    private String equipmentStatusName(String code) {
        if (!StringUtils.hasText(code)) {
            return "";
        }
        return switch (code) {
            case "RUNNING" -> "运行";
            case "IDLE" -> "空闲";
            case "FAULT" -> "故障";
            case "REPAIRING" -> "维修中";
            case "MAINTAINING" -> "保养中";
            case "STOPPED" -> "停机";
            case "SCRAPPED" -> "报废";
            default -> code;
        };
    }

    /**
     * 检验类型中文名称，覆盖来料、过程、终检和出货检验。
     */
    private String inspectionTypeName(String code) {
        if (!StringUtils.hasText(code)) {
            return "";
        }
        return switch (code) {
            case "INCOMING" -> "来料检验";
            case "PROCESS" -> "过程检验";
            case "FINAL" -> "终检";
            case "OUTGOING" -> "出货检验";
            default -> code;
        };
    }

    /**
     * 单据状态中文名称。
     */
    private String documentStatusName(String code) {
        if (!StringUtils.hasText(code)) {
            return "";
        }
        return switch (code) {
            case "DRAFT" -> "草稿";
            case "SUBMITTED" -> "待审核";
            case "APPROVED" -> "已审核";
            case "RELEASED" -> "已发布";
            case "FINISHED" -> "已完成";
            case "CLOSED" -> "已关闭";
            case "WITHDRAWN" -> "已撤回";
            default -> code;
        };
    }

    private List<AiColumn> inventoryFlowColumns() {
        return List.of(
                new AiColumn("materialCode", "物料编码"),
                new AiColumn("materialName", "物料名称"),
                new AiColumn("unitName", "单位"),
                new AiColumn("openingQuantity", "期初"),
                new AiColumn("inboundQuantity", "本期入库"),
                new AiColumn("outboundQuantity", "本期出库"),
                new AiColumn("closingQuantity", "期末"));
    }

    private List<AiColumn> inventoryFlowDetailColumns() {
        return List.of(
                new AiColumn("materialCode", "物料编码"),
                new AiColumn("materialName", "物料名称"),
                new AiColumn("warehouseName", "仓库"),
                new AiColumn("warehouseTypeName", "仓库类型"),
                new AiColumn("openingQuantity", "期初"),
                new AiColumn("inboundQuantity", "本期入库"),
                new AiColumn("outboundQuantity", "本期出库"),
                new AiColumn("closingQuantity", "期末"),
                new AiColumn("unitName", "单位"));
    }

    private List<AiColumn> inventoryDetailColumns() {
        return List.of(
                new AiColumn("materialCode", "物料编码"),
                new AiColumn("materialName", "物料名称"),
                new AiColumn("warehouseName", "仓库"),
                new AiColumn("locationCode", "库位"),
                new AiColumn("batchNo", "批次"),
                new AiColumn("qualityStatus", "质量状态"),
                new AiColumn("availableQuantity", "可用数量"),
                new AiColumn("lockedQuantity", "锁定数量"),
                new AiColumn("inspectionQuantity", "待检数量"),
                new AiColumn("totalQuantity", "合计数量"),
                new AiColumn("workOrderNo", "工单占用"),
                new AiColumn("unitName", "单位"));
    }

    private List<AiColumn> workOrderHeaderColumns() {
        return List.of(
                new AiColumn("workOrderNo", "工单号"),
                new AiColumn("productCode", "产品编码"),
                new AiColumn("productName", "产品名称"),
                new AiColumn("workOrderType", "工单类型"),
                new AiColumn("plannedQuantity", "计划量"),
                new AiColumn("completedQuantity", "完成量"),
                new AiColumn("status", "状态"),
                new AiColumn("parentWorkOrderNo", "父工单"),
                new AiColumn("rootWorkOrderNo", "根工单"),
                new AiColumn("createdTime", "创建时间"));
    }

    private List<AiColumn> materialColumns() {
        return List.of(
                new AiColumn("materialCode", "物料编码"),
                new AiColumn("materialName", "物料名称"),
                new AiColumn("requiredQuantity", "需求数量"),
                new AiColumn("issuedQuantity", "已发数量"),
                new AiColumn("shortageQuantity", "缺料数量"),
                new AiColumn("status", "齐套状态"),
                new AiColumn("unitName", "单位"));
    }

    private List<AiColumn> reportColumns() {
        return List.of(
                new AiColumn("reportNo", "报工单号"),
                new AiColumn("operationName", "工序"),
                new AiColumn("stationName", "工位"),
                new AiColumn("qualifiedQuantity", "合格数"),
                new AiColumn("defectQuantity", "不良数"),
                new AiColumn("actualWorkHours", "实际工时"),
                new AiColumn("reportTime", "报工时间"),
                new AiColumn("auditStatus", "审核状态"));
    }

    private List<AiColumn> inspectionColumns() {
        return List.of(
                new AiColumn("inspectionNo", "检验单号"),
                new AiColumn("inspectionType", "检验类型"),
                new AiColumn("sampleQuantity", "抽样数"),
                new AiColumn("qualifiedQuantity", "合格数"),
                new AiColumn("unqualifiedQuantity", "不合格数"),
                new AiColumn("inspectionResult", "检验结论"),
                new AiColumn("status", "状态"),
                new AiColumn("inspectionTime", "检验时间"));
    }

    private List<AiColumn> productLotColumns() {
        return List.of(
                new AiColumn("lotNo", "批次号"),
                new AiColumn("productCode", "产品编码"),
                new AiColumn("productName", "产品名称"),
                new AiColumn("batchQuantity", "批次数量"),
                new AiColumn("qualifiedQuantity", "合格数"),
                new AiColumn("defectQuantity", "不良数"),
                new AiColumn("status", "状态"),
                new AiColumn("createdTime", "创建时间"));
    }

    private List<AiColumn> productionColumns() {
        return List.of(
                new AiColumn("reportDate", "日期"),
                new AiColumn("workOrderCount", "工单数"),
                new AiColumn("reportCount", "报工数"),
                new AiColumn("qualifiedQuantity", "合格数"),
                new AiColumn("defectQuantity", "不良数"),
                new AiColumn("defectRate", "不良率"),
                new AiColumn("actualWorkHours", "实际工时"));
    }

    private List<AiColumn> defectColumns() {
        return List.of(
                new AiColumn("productCode", "物料/产品编码"),
                new AiColumn("productName", "物料/产品名称"),
                new AiColumn("inspectionDefect", "检验判定不良"),
                new AiColumn("reportDefect", "报工登记不良"),
                new AiColumn("defectQuantity", "不良合计"),
                new AiColumn("docCount", "涉及单据数"));
    }

    private List<AiColumn> operationColumns() {
        return List.of(
                new AiColumn("operationCode", "工序编码"),
                new AiColumn("operationName", "工序名称"),
                new AiColumn("inspectionDefect", "检验判定不良"),
                new AiColumn("reportDefect", "报工登记不良"),
                new AiColumn("defectQuantity", "不良合计"),
                new AiColumn("docCount", "涉及单据数"));
    }

    private List<AiColumn> traceBatchColumns() {
        return List.of(
                new AiColumn("batchNo", "批次号"),
                new AiColumn("materialCode", "物料编码"),
                new AiColumn("materialName", "物料名称"),
                new AiColumn("qualityStatus", "质量状态"),
                new AiColumn("productionDate", "生产日期"),
                new AiColumn("receivedDate", "入库日期"));
    }

    private List<AiColumn> traceInboundColumns() {
        return List.of(
                new AiColumn("inboundNo", "入库单号"),
                new AiColumn("inboundType", "入库类型"),
                new AiColumn("warehouseName", "仓库"),
                new AiColumn("inboundQuantity", "入库数量"),
                new AiColumn("inboundTime", "入库时间"),
                new AiColumn("auditStatus", "审核状态"));
    }

    private List<AiColumn> traceIssueColumns() {
        return List.of(
                new AiColumn("workOrderNo", "工单号"),
                new AiColumn("productName", "产品名称"),
                new AiColumn("plannedQuantity", "计划量"),
                new AiColumn("completedQuantity", "完成量"),
                new AiColumn("issuedQuantity", "领用数量"),
                new AiColumn("status", "工单状态"),
                new AiColumn("firstIssueTime", "首次领用时间"));
    }

    private List<AiColumn> traceReportColumns() {
        return List.of(
                new AiColumn("reportNo", "报工单号"),
                new AiColumn("workOrderNo", "工单号"),
                new AiColumn("operationName", "工序"),
                new AiColumn("usedQuantity", "消耗数量"),
                new AiColumn("scrapQuantity", "损耗数量"),
                new AiColumn("reportTime", "报工时间"));
    }

    private List<AiColumn> traceFinishedBatchColumns() {
        return List.of(
                new AiColumn("batchNo", "成品批次号"),
                new AiColumn("materialName", "成品名称"),
                new AiColumn("qualityStatus", "质量状态"),
                new AiColumn("status", "状态"),
                new AiColumn("receivedDate", "入库日期"));
    }

    private List<AiColumn> scheduleLineColumns() {
        return List.of(
                new AiColumn("lineCode", "产线编码"),
                new AiColumn("lineName", "产线名称"),
                new AiColumn("lineStatus", "产线状态"),
                new AiColumn("itemCount", "任务数"),
                new AiColumn("plannedQuantity", "排程数量"),
                new AiColumn("loadMinutes", "占用工时(分钟)"),
                new AiColumn("availableMinutes", "可用工时(分钟)"),
                new AiColumn("remainingMinutes", "剩余工时(分钟)"),
                new AiColumn("utilizationRate", "负荷率"),
                new AiColumn("lateCount", "延期任务"),
                new AiColumn("conflictCount", "排程记录标记冲突"));
    }

    private List<AiColumn> scheduleItemColumns() {
        return List.of(
                new AiColumn("lineName", "产线"),
                new AiColumn("productionOrderNo", "生产订单"),
                new AiColumn("productName", "产品"),
                new AiColumn("plannedQuantity", "排程数量"),
                new AiColumn("plannedStartTime", "计划开始"),
                new AiColumn("plannedEndTime", "计划结束"),
                new AiColumn("estimatedMinutes", "预计工时(分钟)"),
                new AiColumn("tardinessMinutes", "延期(分钟)"),
                new AiColumn("status", "状态"),
                new AiColumn("conflictReason", "冲突原因"));
    }

    private List<AiColumn> orderProgressColumns() {
        return List.of(
                new AiColumn("productionOrderNo", "订单号"),
                new AiColumn("productCode", "产品编码"),
                new AiColumn("productName", "产品名称"),
                new AiColumn("customerName", "客户"),
                new AiColumn("orderQuantity", "订单量"),
                new AiColumn("plannedQuantity", "已排产"),
                new AiColumn("completedQuantity", "已完工"),
                new AiColumn("inboundQuantity", "已入库"),
                new AiColumn("shippedQuantity", "已发货"),
                new AiColumn("progressRate", "完工进度"),
                new AiColumn("deliveryDate", "交期"),
                new AiColumn("status", "状态"));
    }

    private List<AiColumn> orderPlanColumns() {
        return List.of(
                new AiColumn("planNo", "计划号"),
                new AiColumn("lineName", "产线"),
                new AiColumn("plannedQuantity", "计划数量"),
                new AiColumn("plannedStartTime", "计划开始"),
                new AiColumn("plannedEndTime", "计划结束"),
                new AiColumn("planStatus", "计划状态"),
                new AiColumn("workOrderNo", "工单号"),
                new AiColumn("workOrderPlannedQuantity", "工单计划量"),
                new AiColumn("workOrderCompletedQuantity", "工单完成量"),
                new AiColumn("workOrderStatus", "工单状态"));
    }

    private List<AiColumn> orderInboundColumns() {
        return List.of(
                new AiColumn("inboundNo", "入库单号"),
                new AiColumn("warehouseName", "仓库"),
                new AiColumn("materialName", "物料"),
                new AiColumn("inboundQuantity", "入库数量"),
                new AiColumn("inboundTime", "入库时间"),
                new AiColumn("auditStatus", "审核状态"));
    }

    private List<AiColumn> orderShipmentColumns() {
        return List.of(
                new AiColumn("outboundNo", "出库单号"),
                new AiColumn("customerName", "客户"),
                new AiColumn("materialName", "物料"),
                new AiColumn("outboundQuantity", "发货数量"),
                new AiColumn("outboundTime", "发货时间"),
                new AiColumn("auditStatus", "审核状态"));
    }

    private List<AiColumn> equipmentColumns() {
        return List.of(
                new AiColumn("equipmentCode", "设备编码"),
                new AiColumn("equipmentName", "设备名称"),
                new AiColumn("equipmentStatus", "当前设备状态"),
                new AiColumn("stationName", "当前工位"),
                new AiColumn("repairNo", "报修单号"),
                new AiColumn("repairStatus", "报修状态"),
                new AiColumn("faultDescription", "故障描述"),
                new AiColumn("reportedTime", "报修时间"));
    }

    private List<AiColumn> equipmentTaskColumns() {
        return List.of(
                new AiColumn("workOrderNo", "工单号"),
                new AiColumn("productionOrderNo", "生产订单"),
                new AiColumn("productName", "产品"),
                new AiColumn("taskNo", "任务号"),
                new AiColumn("operationName", "工序"),
                new AiColumn("stationName", "工位"),
                new AiColumn("plannedQuantity", "计划量"),
                new AiColumn("completedQuantity", "完成量"),
                new AiColumn("taskStatus", "任务状态"),
                new AiColumn("deliveryDate", "交期"));
    }

    private List<AiColumn> capacityColumns() {
        return List.of(
                new AiColumn("productCode", "产品编码"),
                new AiColumn("productName", "产品名称"),
                new AiColumn("extraQuantity", "追加数量"),
                new AiColumn("bomItemCount", "BOM 项数"),
                new AiColumn("materialLimitQuantity", "物料可支持台数"),
                new AiColumn("lineUnitMinutes", "单台工时(分钟)"),
                new AiColumn("requiredMinutes", "需求工时(分钟)"),
                new AiColumn("availableMinutes", "可用工时(分钟)"),
                new AiColumn("capacityLimitQuantity", "产能可支持台数"),
                new AiColumn("conclusion", "结论"));
    }

    private List<AiColumn> capacityBomColumns() {
        return List.of(
                new AiColumn("materialCode", "物料编码"),
                new AiColumn("materialName", "物料名称"),
                new AiColumn("unitQuantity", "单台用量"),
                new AiColumn("lossRate", "损耗率"),
                new AiColumn("requiredQuantity", "净需求"),
                new AiColumn("availableQuantity", "可用库存"),
                new AiColumn("gapQuantity", "缺口"),
                new AiColumn("unitName", "单位"),
                new AiColumn("stockStatus", "齐套判定"));
    }

    private List<AiColumn> capacityLineColumns() {
        return List.of(
                new AiColumn("lineCode", "产线编码"),
                new AiColumn("lineName", "产线名称"),
                new AiColumn("unitMinutes", "单台标准工时(分钟)"));
    }

    private List<AiColumn> workOrderListColumns() {
        return List.of(
                new AiColumn("workOrderNo", "工单号"),
                new AiColumn("workOrderType", "工单类型"),
                new AiColumn("productCode", "产品编码"),
                new AiColumn("productName", "产品名称"),
                new AiColumn("plannedQuantity", "计划量"),
                new AiColumn("completedQuantity", "完成量"),
                new AiColumn("status", "状态"),
                new AiColumn("productionOrderNo", "生产订单"),
                new AiColumn("lineName", "产线"),
                new AiColumn("createdTime", "创建时间"));
    }

    private List<AiColumn> customerOrderColumns() {
        return List.of(
                new AiColumn("productionOrderNo", "订单号"),
                new AiColumn("customerName", "客户"),
                new AiColumn("productCode", "产品编码"),
                new AiColumn("productName", "产品名称"),
                new AiColumn("orderQuantity", "订单量"),
                new AiColumn("completedQuantity", "已完工"),
                new AiColumn("progressRate", "完工进度"),
                new AiColumn("deliveryDate", "交期"),
                new AiColumn("status", "状态"));
    }

    private List<AiColumn> pendingInspectionColumns() {
        return List.of(
                new AiColumn("inspectionNo", "检验单号"),
                new AiColumn("inspectionType", "检验类型"),
                new AiColumn("workOrderNo", "工单号"),
                new AiColumn("productName", "产品"),
                new AiColumn("materialName", "物料"),
                new AiColumn("batchNo", "批次"),
                new AiColumn("sampleQuantity", "抽样数"),
                new AiColumn("status", "状态"),
                new AiColumn("createdTime", "创建时间"));
    }
}
