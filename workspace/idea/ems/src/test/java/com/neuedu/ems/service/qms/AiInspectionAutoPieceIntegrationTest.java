package com.neuedu.ems.service.qms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.neuedu.ems.PhoneCryptoTestProperties;
import com.neuedu.ems.entity.qms.QmsInspectionOrder;
import com.neuedu.ems.entity.qms.QmsInspectionResult;
import com.neuedu.ems.entity.wms.WmsMaterialBatch;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.service.wms.WmsMaterialBatchService;

/**
 * AI 提交检验的边界：方案要求逐件全检时，只接受质检员真实填报的逐件结果。
 *
 * <p>早期版本为了「让 AI 办得成」，会按抽样数量自己给每件每个必检项补一条同结论的台账，
 * 实测值写「AI 自动登记，未做人工实测」，空结论还默认判合格。这等于凭空造出一批
 * 「已检合格」的记录，批次会因此被判合格流入下游，所以现在改成缺逐件结果直接拒绝。</p>
 */
@SpringBootTest(properties = {"mes.notification.scheduling-enabled=false", "mes.ai.enabled=false",
        PhoneCryptoTestProperties.ACTIVE_KEY, PhoneCryptoTestProperties.KEY_IDS,
        PhoneCryptoTestProperties.ENCRYPTION_KEY, PhoneCryptoTestProperties.LOOKUP_HMAC_KEY})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:(3307|3310|3311)/mes_lamp_[A-Za-z0-9_]+.*")
@Transactional
class AiInspectionAutoPieceIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired WmsMaterialBatchService batches;
    @Autowired QmsInspectionOrderService inspections;

    @Test
    void rejectsWhenPieceResultsMissingInsteadOfFabricating() {
        Ctx ctx = prepare();
        BusinessException blocked = assertThrows(BusinessException.class,
                () -> inspections.submitInspectionAutoPieces(ctx.orderId(), "PASS", "AI 代提交", ctx.actor(),
                        new BigDecimal("3"), null, null, null));
        assertTrue(blocked.getMessage().contains("逐件全检"), "必须明确要求补逐件结果，实际：" + blocked.getMessage());
        assertNoPieceResults(ctx.orderId(), "被拒绝时不能留下任何台账");
        assertStillDraft(ctx.orderId());
        assertEquals("WAIT_INSPECTION", batchQuality(ctx.batchId()), "被拒绝后批次不能变成已检合格");
    }

    @Test
    void rejectsWhenPieceResultsEmpty() {
        Ctx ctx = prepare();
        BusinessException blocked = assertThrows(BusinessException.class,
                () -> inspections.submitInspectionAutoPieces(ctx.orderId(), "PASS", "AI 代提交", ctx.actor(),
                        new BigDecimal("3"), null, null, List.of()));
        assertTrue(blocked.getMessage().contains("逐件全检"));
        assertNoPieceResults(ctx.orderId(), "空列表不能留下台账");
        assertStillDraft(ctx.orderId());
    }

    @Test
    void rejectsWhenAnyRequiredItemMissing() {
        Ctx ctx = prepare();
        List<QmsInspectionResult> partial = rows(ctx.itemIds().subList(0, ctx.itemIds().size() - 1), 1, "实测 1.0");
        BusinessException blocked = assertThrows(BusinessException.class,
                () -> inspections.submitInspectionAutoPieces(ctx.orderId(), null, "缺项提交", ctx.actor(),
                        null, null, null, partial));
        assertTrue(blocked.getMessage().contains("还缺"), "必须指出缺项，实际：" + blocked.getMessage());
        assertNoPieceResults(ctx.orderId(), "缺项被拒后不能写入半截台账");
        assertStillDraft(ctx.orderId());
    }

    @Test
    void acceptsOptionalMeasurementsAndAggregatesExplicitVerdicts() {
        Ctx ctx = prepare();
        List<QmsInspectionResult> rows = new ArrayList<>();
        rows.addAll(rows(ctx.itemIds(), 1, null, "PASS"));
        rows.addAll(rows(ctx.itemIds(), 2, "", "PASS"));
        rows.addAll(rows(ctx.itemIds(), 3, "", "FAIL"));
        assertTrue(inspections.submitInspectionAutoPieces(ctx.orderId(), null, "人工批量判定后修改例外", ctx.actor(),
                null, null, null, rows));
        QmsInspectionOrder submitted = inspections.getInspectionOrderById(ctx.orderId());
        assertEquals("FINISHED", submitted.getStatus());
        assertEquals("FAIL", submitted.getInspectionResult());
        assertEquals(0, new BigDecimal("2").compareTo(submitted.getQualifiedQuantity()));
        assertEquals(0, BigDecimal.ONE.compareTo(submitted.getUnqualifiedQuantity()));
        var saved = inspections.listPieceResults(ctx.orderId());
        assertEquals(3 * ctx.itemIds().size(), saved.size());
        assertTrue(saved.stream().allMatch(row -> row.getMeasuredValue() == null || row.getMeasuredValue().isEmpty()),
                "选填记录留空，不能自动生成实测值");
    }

    @Test
    void submitsRealMixedResultsAndAggregatesVerdict() {
        Ctx ctx = prepare();
        List<QmsInspectionResult> rows = new ArrayList<>();
        // 前两件每项都合格，第三件每项都不合格，检验单应汇总成 FAIL 且不合格件数为 1。
        rows.addAll(rows(ctx.itemIds(), 1, "实测 1.0", "PASS"));
        rows.addAll(rows(ctx.itemIds(), 2, "实测 1.0", "PASS"));
        rows.addAll(rows(ctx.itemIds(), 3, "实测超差", "FAIL"));

        assertTrue(inspections.submitInspectionAutoPieces(ctx.orderId(), null, "真实逐件提交", ctx.actor(),
                null, null, null, rows));

        QmsInspectionOrder submitted = inspections.getInspectionOrderById(ctx.orderId());
        assertEquals("FINISHED", submitted.getStatus());
        assertEquals("FAIL", submitted.getInspectionResult(), "有一件不合格就应判整单不合格");
        assertEquals(0, new BigDecimal("3").compareTo(submitted.getSampleQuantity()));
        assertEquals(0, BigDecimal.ONE.compareTo(submitted.getUnqualifiedQuantity()), "不合格件数应为 1");
        Integer resultRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM qms_inspection_result WHERE inspection_id=? AND deleted_flag=0",
                Integer.class, ctx.orderId());
        assertEquals(3 * ctx.itemIds().size(), resultRows, "三件每件每个必检项都要有一条真实结果");
        Integer distinctPieces = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT piece_no) FROM qms_inspection_result WHERE inspection_id=? AND deleted_flag=0",
                Integer.class, ctx.orderId());
        assertEquals(3, distinctPieces);
        Integer fabricated = jdbc.queryForObject(
                "SELECT COUNT(*) FROM qms_inspection_result WHERE inspection_id=? AND deleted_flag=0 "
                        + "AND (measured_value LIKE '%未做人工实测%' OR measured_value LIKE '%AI 自动登记%')",
                Integer.class, ctx.orderId());
        assertEquals(0, fabricated, "不允许出现任何自动登记的伪实测值");
        assertEquals("FAIL", batchQuality(ctx.batchId()));
    }

    @Test
    void manualAggregateStillBlockedWhenSchemeRequiresPieces() {
        Ctx ctx = prepare();
        BusinessException blocked = assertThrows(BusinessException.class,
                () -> inspections.submitInspectionOrder(ctx.orderId(), "PASS", "汇总提交", ctx.actor()));
        assertTrue(blocked.getMessage().contains("逐件全检"), "人工汇总提交的后门必须继续关着");
        assertStillDraft(ctx.orderId());
    }

    @Test
    void fallbackToAggregateWhenSchemeHasNoAllMode() {
        String tag = "AIA" + System.nanoTime();
        Long actor = actor();
        WmsMaterialBatch batch = newBatch(tag);
        QmsInspectionOrder order = new QmsInspectionOrder();
        order.setInspectionNo(tag + "-QI");
        order.setInspectionType("INCOMING");
        order.setBatchId(batch.getBatchId());
        order.setSampleQuantity(new BigDecimal("4"));
        order.setQualifiedQuantity(new BigDecimal("4"));
        order.setUnqualifiedQuantity(BigDecimal.ZERO);
        order.setCreatedBy(actor);
        assertTrue(inspections.saveInspectionOrder(order));

        // 没有绑定方案时没有逐件约束，应退回到原来的汇总提交路径。
        assertTrue(inspections.submitInspectionAutoPieces(order.getInspectionId(), "PASS", "AI 汇总", actor,
                new BigDecimal("4"), new BigDecimal("4"), BigDecimal.ZERO, null));
        QmsInspectionOrder submitted = inspections.getInspectionOrderById(order.getInspectionId());
        assertEquals("FINISHED", submitted.getStatus());
        assertEquals("PASS", submitted.getInspectionResult());
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT piece_no FROM qms_inspection_result WHERE inspection_id=? AND deleted_flag=0",
                order.getInspectionId());
        assertTrue(rows.isEmpty(), "汇总提交不应产生逐件台账");
    }

    /* ==================== 造数据与断言辅助 ==================== */

    private record Ctx(Long actor, Long batchId, Long orderId, List<Long> itemIds) {
    }

    /** 造一张绑定「逐件全检」方案的来料检验单，抽样数量 3。 */
    private Ctx prepare() {
        String tag = "AIP" + System.nanoTime();
        Long actor = actor();
        Map<String, Object> scheme = jdbc.queryForMap(
                "SELECT s.scheme_id FROM qms_inspection_scheme s "
                        + "WHERE s.status='RELEASED' AND s.deleted_flag=0 AND s.inspection_type='INCOMING' "
                        + "AND EXISTS (SELECT 1 FROM qms_inspection_scheme_item i WHERE i.scheme_id=s.scheme_id "
                        + "AND i.deleted_flag=0 AND i.sampling_mode='ALL') ORDER BY s.scheme_id LIMIT 1");
        Long schemeId = ((Number) scheme.get("scheme_id")).longValue();
        List<Long> itemIds = jdbc.queryForList(
                "SELECT item_id FROM qms_inspection_scheme_item WHERE scheme_id=? AND deleted_flag=0 "
                        + "AND sampling_mode='ALL' ORDER BY sequence_no, item_id",
                Long.class, schemeId);
        assertTrue(!itemIds.isEmpty(), "选到的方案必须至少有 1 个全检项");

        WmsMaterialBatch batch = newBatch(tag);
        QmsInspectionOrder order = new QmsInspectionOrder();
        order.setInspectionNo(tag + "-QI");
        order.setInspectionType("INCOMING");
        order.setSchemeId(schemeId);
        order.setBatchId(batch.getBatchId());
        order.setSampleQuantity(new BigDecimal("3"));
        order.setCreatedBy(actor);
        assertTrue(inspections.saveInspectionOrder(order));
        return new Ctx(actor, batch.getBatchId(), order.getInspectionId(), itemIds);
    }

    @Test
    void savedPieceDraftCanBeEditedAndSubmittedWithoutDuplicatingItsUniqueKeys() {
        Ctx ctx=prepare();
        List<QmsInspectionResult> draft=new ArrayList<>();
        draft.addAll(rows(ctx.itemIds(),1,"草稿值","PASS"));
        draft.addAll(rows(ctx.itemIds(),2,"草稿值","PASS"));
        draft.addAll(rows(ctx.itemIds(),3,"草稿值","FAIL"));
        var order=inspections.getInspectionOrderById(ctx.orderId());order.setUpdatedBy(ctx.actor());
        assertTrue(inspections.updateInspectionOrderFull(order,draft));
        assertStillDraft(ctx.orderId());
        var saved=inspections.listPieceResults(ctx.orderId());
        var ids=saved.stream().map(QmsInspectionResult::getResultId).sorted().toList();
        assertEquals(3*ctx.itemIds().size(),saved.size());
        saved.forEach(row->row.setMeasuredValue(null));
        var next=inspections.getInspectionOrderById(ctx.orderId());next.setUpdatedBy(ctx.actor());
        assertTrue(inspections.updateInspectionOrderFull(next,saved));
        assertTrue(inspections.submitPieceInspection(ctx.orderId(),saved,"核对草稿后正式提交",ctx.actor()));
        var submitted=inspections.getInspectionOrderById(ctx.orderId());
        assertEquals("FINISHED",submitted.getStatus());assertEquals("FAIL",submitted.getInspectionResult());
        assertEquals(0,BigDecimal.ONE.compareTo(submitted.getUnqualifiedQuantity()));
        var finalRows=inspections.listPieceResults(ctx.orderId());
        assertEquals(ids,finalRows.stream().map(QmsInspectionResult::getResultId).sorted().toList());
        assertTrue(finalRows.stream().allMatch(row->row.getMeasuredValue()==null));
        assertEquals(3*ctx.itemIds().size(),jdbc.queryForObject("SELECT COUNT(*) FROM qms_inspection_result WHERE inspection_id=?",Integer.class,ctx.orderId()));
    }

    private WmsMaterialBatch newBatch(String tag) {
        WmsMaterialBatch batch = new WmsMaterialBatch();
        batch.setBatchNo(tag + "-B");
        batch.setMaterialId(materialId());
        assertTrue(batches.saveMaterialBatch(batch));
        return batch;
    }

    /** 给指定件号按每个必检项造一条结果。 */
    private List<QmsInspectionResult> rows(List<Long> itemIds, int pieceNo, String measuredValue) {
        return rows(itemIds, pieceNo, measuredValue, "PASS");
    }

    private List<QmsInspectionResult> rows(List<Long> itemIds, int pieceNo, String measuredValue, String result) {
        List<QmsInspectionResult> list = new ArrayList<>(itemIds.size());
        for (Long itemId : itemIds) {
            QmsInspectionResult row = new QmsInspectionResult();
            row.setPieceNo("P" + pieceNo);
            row.setItemId(itemId);
            row.setMeasuredValue(measuredValue);
            row.setResult(result);
            list.add(row);
        }
        return list;
    }

    private void assertNoPieceResults(Long inspectionId, String message) {
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM qms_inspection_result WHERE inspection_id=? AND deleted_flag=0",
                Integer.class, inspectionId);
        assertEquals(0, rows, message);
    }

    private void assertStillDraft(Long inspectionId) {
        assertEquals("DRAFT", inspections.getInspectionOrderById(inspectionId).getStatus(), "被拒绝后状态不能变");
    }

    private String batchQuality(Long batchId) {
        return jdbc.queryForObject(
                "SELECT quality_status FROM wms_material_batch WHERE batch_id=?", String.class, batchId);
    }

    private Long actor() {
        return jdbc.queryForObject(
                "SELECT user_id FROM sys_user WHERE status='ENABLE' AND deleted_flag=0 ORDER BY user_id LIMIT 1",
                Long.class);
    }

    private Long materialId() {
        return jdbc.queryForObject(
                "SELECT material_id FROM mdm_material WHERE status='ENABLE' AND deleted_flag=0 ORDER BY material_id LIMIT 1",
                Long.class);
    }
}
