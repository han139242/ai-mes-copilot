package com.neuedu.ems.service.impl.ai;
import com.neuedu.ems.PhoneCryptoTestProperties;
import com.neuedu.ems.common.AuthorizationContext;
import com.neuedu.ems.entity.ai.AiChatRequest;
import com.neuedu.ems.service.ai.AiDataQueryService;
import com.neuedu.ems.service.ai.AiIntentRouter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
/** 在显式指定的本机台灯库验证真实SQL；模型关闭，不调用外部API。 */
@SpringBootTest(properties={"mes.notification.scheduling-enabled=false","mes.ai.enabled=false",
        PhoneCryptoTestProperties.ACTIVE_KEY,PhoneCryptoTestProperties.KEY_IDS,
        PhoneCryptoTestProperties.ENCRYPTION_KEY,PhoneCryptoTestProperties.LOOKUP_HMAC_KEY})
@EnabledIfEnvironmentVariable(named="DB_URL",matches="jdbc:mysql://127[.]0[.]0[.]1:(?:3306|3307|3310)/mes_lamp_.*")
class AiQuerySmokeTest {
    @Autowired AiIntentRouter router;
    @Autowired AiDataQueryService queries;
    @Autowired AiServiceImpl service;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired com.neuedu.ems.mapper.ai.AiDataQueryMapper mapper;
    private final AuthorizationContext admin=new AuthorizationContext(1L,null,null,"ALL",Set.of("SUPER_ADMIN"),Set.of(),Set.of());
    @Test void weeklyScheduleReturnsStructuredDataWithoutCallingModel() {
        var request=new AiChatRequest();request.setQuestion("本周排程和产能怎么样");
        var answer=service.agentChat(request,admin);
        assertEquals("schedule_capacity",answer.get("intent"));assertEquals("DATABASE",answer.get("source"));
        assertEquals(0,answer.get("tokenCost"));assertFalse(((List<?>)answer.get("blocks")).isEmpty());
        assertEquals(0L,service.getUsage().get("totalCalls"));
    }
    @Test void followupKeepsMaterialAndMonth() {
        var first=router.route("这个月控制板收发存",List.of(),null);
        var next=router.route("那出库呢",List.of("这个月控制板收发存"),null);
        assertEquals("inventory_flow",next.intent());assertEquals("控制板",next.params().get("keyword"));
        assertEquals(first.date("dateFrom"),next.date("dateFrom"));assertNotNull(router.execute(next));
    }
    @Test void businessSqlExecutesAgainstLampSchema() {
        var from=LocalDate.of(2026,10,19);var to=LocalDate.of(2026,10,25);
        var results=List.of(queries.queryInventoryByMaterial(null,null,from,to),
                queries.queryInventoryByWarehouse(null,null,null),
                queries.queryProductionByDate(from,to),queries.queryDefectByPeriod(from,to,null),
                queries.queryScheduleCapacity(from,to),
                queries.queryCapacityFeasibility("LAMP-L200",100,from,to),queries.queryEquipmentImpact(null,from,to),
                queries.queryTraceByBatch("NOT-A-REAL-BATCH"));
        for(var result:results){assertNotNull(result.getTitle());assertNotNull(result.getColumns());assertNotNull(result.getRows());}
    }
    @Test void missingProductAndPermissionsCannotReadAnotherDataset() {
        var request=new AiChatRequest();request.setQuestion("库存还有多少");
        assertThrows(com.neuedu.ems.exception.DataScopeAccessDeniedException.class,()->service.agentChat(request,null));
        assertThrows(com.neuedu.ems.exception.AiServiceException.class,()->queries.queryCapacityFeasibility(null,100,LocalDate.now(),LocalDate.now()));
    }
    @Test @org.springframework.transaction.annotation.Transactional
    void capacityQueryExcludesStockAssignedToAnotherWorkOrder() {
        Long materialId=jdbc.queryForObject("SELECT material_id FROM mdm_material WHERE material_code LIKE 'LAMP-%' AND deleted_flag=0 LIMIT 1",Long.class);
        Long warehouseId=jdbc.queryForObject("SELECT warehouse_id FROM wms_warehouse WHERE status='ENABLE' AND deleted_flag=0 LIMIT 1",Long.class);
        java.math.BigDecimal before=stock(materialId);
        String batchNo="TEST-AI-SCOPED-"+System.nanoTime();
        jdbc.update("INSERT INTO wms_material_batch(batch_no,material_id,quality_status,status,deleted_flag) VALUES (?,?,'PASS','AVAILABLE',0)",batchNo,materialId);
        Long batchId=jdbc.queryForObject("SELECT batch_id FROM wms_material_batch WHERE batch_no=?",Long.class,batchNo);
        jdbc.update("INSERT INTO wms_inventory(warehouse_id,material_id,batch_id,available_quantity,work_order_id,deleted_flag) VALUES (?,?,?,12345,987654321,0)",warehouseId,materialId,batchId);
        assertEquals(0,before.compareTo(stock(materialId)));
    }
    private java.math.BigDecimal stock(Long materialId) {
        return mapper.selectAvailableStock(List.of(materialId)).stream()
                .map(row->new java.math.BigDecimal(row.get("availableQuantity").toString()))
                .reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add);
    }
    @Test @org.springframework.transaction.annotation.Transactional
    void capacityQueryRoundsDiscreteDemandInsteadOfShowingFractionalPieces() {
        var item=jdbc.queryForMap("SELECT bi.bom_item_id AS id,m.material_code AS code FROM mdm_bom_item bi JOIN mdm_bom b ON b.bom_id=bi.bom_id JOIN mdm_product p ON p.product_id=b.product_id JOIN mdm_material m ON m.material_id=bi.material_id WHERE p.product_code='LAMP-L200' AND b.status='RELEASED' AND bi.deleted_flag=0 AND m.quantity_mode='DISCRETE' AND m.issue_step=1 LIMIT 1");
        jdbc.update("UPDATE mdm_bom_item SET quantity=0.04,loss_rate=0 WHERE bom_item_id=?",item.get("id"));
        var result=queries.queryCapacityFeasibility("LAMP-L200",1,LocalDate.of(2026,10,19),LocalDate.of(2026,10,25));
        var row=result.getBlocks().getFirst().getRows().stream().filter(value->item.get("code").equals(value.get("materialCode"))).findFirst().orElseThrow();
        assertEquals(0,java.math.BigDecimal.ONE.compareTo(new java.math.BigDecimal(row.get("requiredQuantity").toString())));
        assertFalse(result.getSummary().contains("可按期完成"));
    }
}
