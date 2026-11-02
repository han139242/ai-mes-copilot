package com.neuedu.ems.service.mdm;

import com.neuedu.ems.PhoneCryptoTestProperties;
import com.neuedu.ems.entity.factory.FactoryStation;
import com.neuedu.ems.entity.mdm.*;
import com.neuedu.ems.entity.org.OrgTeamSchedule;
import com.neuedu.ems.entity.process.ProcessRoute;
import com.neuedu.ems.entity.qms.*;
import com.neuedu.ems.exception.*;
import com.neuedu.ems.mapper.mdm.MdmProductParameterMapper;
import com.neuedu.ems.service.factory.FactoryStationService;
import com.neuedu.ems.service.mdm.MdmBomItemService;
import com.neuedu.ems.service.mdm.MdmBomService;
import com.neuedu.ems.service.mdm.MdmProductService;
import com.neuedu.ems.service.org.OrgTeamScheduleService;
import com.neuedu.ems.service.process.ProcessRouteService;
import com.neuedu.ems.service.qms.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.*;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"mes.notification.scheduling-enabled=false",PhoneCryptoTestProperties.ACTIVE_KEY,
        PhoneCryptoTestProperties.KEY_IDS,PhoneCryptoTestProperties.ENCRYPTION_KEY,PhoneCryptoTestProperties.LOOKUP_HMAC_KEY})
@Transactional
@EnabledIfEnvironmentVariable(named="DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:(?:3306|3307|3310)/mes_lamp_.*")
class ManufacturingPreparationIntegrationTest {
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired ManufacturingPreparationService preparation;
    @Autowired ManufacturingPreparationGate gate;
    @Autowired ProductParameterService parameters;
    @Autowired MdmProductParameterMapper parameterMapper;
    @Autowired MdmProductService products;
    @Autowired QmsInspectionSchemeService schemes;
    @Autowired QmsInspectionStandardService standards;
    @Autowired InspectionStandardReviewService review;
    @Autowired QmsInspectionSchemeItemService schemeItems;
    @Autowired QmsInspectionOrderService inspections;
    @Autowired OrgTeamScheduleService teamSchedules;
    @Autowired ProcessRouteService routes;
    @Autowired FactoryStationService stations;
    @Autowired MdmBomService boms;
    @Autowired MdmBomItemService bomItems;
    private Long product(String code){return jdbc.queryForObject("SELECT product_id FROM mdm_product WHERE product_code=?",Long.class,code);}
    private MdmProductParameter parameter(){return parameterMapper.selectById(jdbc.queryForObject("SELECT parameter_id FROM mdm_product_parameter WHERE product_id=? AND parameter_code='MAX_POWER'",Long.class,product("LAMP-L200")));}

    @Test
    void invalidBomReportsBlockedWithoutPoisoningReadOnlyTransaction() {
        Long productId = product("LAMP-L200");
        Long bomId = jdbc.queryForObject("SELECT MIN(bom_id) FROM mdm_bom WHERE product_id=? AND deleted_flag=0", Long.class, productId);
        jdbc.update("UPDATE mdm_bom SET status='DISABLE' WHERE product_id=? AND deleted_flag=0", productId);
        jdbc.update("UPDATE mdm_bom SET status='RELEASED',effective_date=?,effective_to=NULL WHERE bom_id=?", LocalDate.now().minusDays(1), bomId);
        jdbc.update("UPDATE mdm_bom_item SET quantity=0 WHERE bom_id=? AND deleted_flag=0", bomId);
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status -> {
            var report = preparation.inspect(productId, LocalDate.now());
            assertTrue(report.checks().stream().anyMatch(check -> "BOM".equals(check.code())
                    && "BLOCKED".equals(check.status()) && check.message().contains("用量必须大于")));
            assertFalse(status.isRollbackOnly(), "只读诊断的预期配置错误不能让整个请求回滚并返回 500");
            return null;
        });
    }

    @Test
    void reportUsesRealDataAndSeparatesBatchTimeFromLabor(){
        // 在事务夹具中构造未准备状态，不依赖库里已有演示排班或已经人工确认的生产条件。
        Long productId=product("LAMP-L200");
        jdbc.update("UPDATE mdm_product_parameter SET status='DRAFT' WHERE product_id=? AND required_flag=1",productId);
        jdbc.update("UPDATE mdm_bom_item i JOIN mdm_bom b ON b.bom_id=i.bom_id SET i.consumption_operation_id=NULL WHERE b.product_id=?",productId);
        LocalDate asOf=LocalDate.now();
        var result=preparation.inspect(product("LAMP-L200"),asOf);
        assertFalse(result.configurationReady());assertFalse(result.executionReady());assertTrue(result.parameters().stream().anyMatch(p->p.getParameterCode().equals("ADAPTER_CURRENT") && p.getParameterValue().equals("1") && p.getRequiredFlag()==1));
        assertEquals(2,result.routes().getFirst().steps().size());
        var aging=result.routes().getFirst().steps().stream().filter(s->"BATCH_EQUIPMENT".equals(s.item().getResourceType())).findFirst().orElseThrow().item();
        assertEquals(0,new java.math.BigDecimal("60").compareTo(aging.getBatchDurationMinutes()));
        assertEquals(0,java.math.BigDecimal.ZERO.compareTo(aging.getStandardTime()));
        assertTrue(result.qualitySchemes().stream().flatMap(s->s.standards().stream()).allMatch(s->"ALL".equals(s.samplingMode()) && s.sampleQuantity().signum()==0));
        var bomCheck=result.checks().stream().filter(c->c.code().equals("BOM")).findFirst().orElseThrow();
        assertEquals("BLOCKED",bomCheck.status());
        assertTrue(bomCheck.message().contains("投料工序"));
    }

    @Test
    void preparedReportUsesRealEngineeringBaseline(){
        // 演示基线已把 L200 的投料工序、检验标准与方案补齐，这里校验报表反映真实数据且工时口径正确。
        restoreL200QualityBaseline();
        // 台灯种子不代审参数、路线和投料工序。缺什么就走对应服务补齐，再核对报表。
        prepareL200EngineeringBaseline();
        LocalDate asOf=releasedScheduleDate();
        assertNotNull(asOf);
        var result=preparation.inspect(product("LAMP-L200"),asOf);
        assertTrue(result.configurationReady(),result.checks().toString());
        assertTrue(result.executionReady(),result.executionBlockers().toString());
        assertTrue(result.parameters().stream().anyMatch(p->p.getParameterCode().equals("ADAPTER_CURRENT") && p.getParameterValue().equals("1") && p.getRequiredFlag()==1));
        assertEquals(2,result.routes().getFirst().steps().size());
        var aging=result.routes().getFirst().steps().stream().filter(s->"BATCH_EQUIPMENT".equals(s.item().getResourceType())).findFirst().orElseThrow().item();
        assertEquals(0,new java.math.BigDecimal("60").compareTo(aging.getBatchDurationMinutes()));
        assertEquals(0,java.math.BigDecimal.ZERO.compareTo(aging.getStandardTime()));
        assertTrue(result.qualitySchemes().stream().flatMap(s->s.standards().stream()).allMatch(s->"ALL".equals(s.samplingMode()) && s.sampleQuantity().signum()==0));
        var bomCheck=result.checks().stream().filter(c->c.code().equals("BOM")).findFirst().orElseThrow();
        assertEquals("PASS",bomCheck.status());
        assertTrue(bomCheck.message().contains("BOM"));
    }
    @Test void parameterLifecycleAndOptimisticVersionWorkAgainstMysql(){
        jdbc.update("UPDATE mdm_product_parameter SET status='DRAFT' WHERE parameter_id=?", parameter().getParameterId());
        var row=parameter();
        var confirmed=parameters.review(row.getParameterId(),new ProductParameterService.Review(row.getVersion()),1L,true);
        assertEquals("CONFIRMED",confirmed.getStatus());assertEquals(row.getVersion()+1,confirmed.getVersion());
        assertThrows(OptimisticLockConflictException.class,()->parameters.review(row.getParameterId(),new ProductParameterService.Review(row.getVersion()),1L,false));
        assertThrows(BusinessException.class,()->parameters.edit(row.getParameterId(),new ProductParameterService.Edit(confirmed.getVersion(),"6","R1设计"),1L));
        var reopened=parameters.review(row.getParameterId(),new ProductParameterService.Review(confirmed.getVersion()),1L,false);
        var edited=parameters.edit(row.getParameterId(),new ProductParameterService.Edit(reopened.getVersion(),"6.5","Q/LAMP-001-V0.1 测试草稿"),1L);
        assertEquals("6.5",edited.getParameterValue());
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void missingActorRollsBackTheActualParameterUpdate(){
        var before=parameter();
        assertThrows(BusinessException.class,()->parameters.edit(before.getParameterId(),new ProductParameterService.Edit(before.getVersion(),"6.25","R1回滚用例"),null));
        var after=parameter();assertEquals(before.getParameterValue(),after.getParameterValue());assertEquals(before.getVersion(),after.getVersion());
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void incompleteSchemePublicationRollsBackStatus(){
        Long id=jdbc.queryForObject("SELECT scheme_id FROM qms_inspection_scheme WHERE scheme_code='LAMP-V18-QS-L200-FINAL'",Long.class);
        jdbc.update("UPDATE qms_inspection_scheme SET status='DRAFT' WHERE scheme_id=?", id);
        jdbc.update("UPDATE qms_inspection_standard SET confirmation_status='DRAFT' WHERE product_id=(SELECT product_id FROM qms_inspection_scheme WHERE scheme_id=?)", id);
        try{
            var request=new QmsInspectionScheme();request.setSchemeId(id);request.setStatus("RELEASED");
            assertThrows(BusinessException.class,()->schemes.updateScheme(request));
            assertEquals("DRAFT",schemes.getSchemeById(id).getStatus());
        }finally{
            // 本用例不参与事务回滚，结束前把 L200 质量基线改回来，避免影响同库的其它用例与演示。
            restoreL200QualityBaseline();
        }
    }

    /**
     * 台灯种子把参数、路线、工位和投料工序留在待审状态。这里按正式服务确认，不直接改状态。
     * 已发布且未绑工序的 BOM 另建新版本，发布时由服务收口旧版本失效日。
     */
    private void prepareL200EngineeringBaseline(){
        Long productId=product("LAMP-L200");
        for(Long id:jdbc.queryForList("SELECT parameter_id FROM mdm_product_parameter WHERE product_id=? AND deleted_flag=0 AND required_flag=1 AND status='DRAFT'",Long.class,productId)){
            Integer version=jdbc.queryForObject("SELECT version FROM mdm_product_parameter WHERE parameter_id=?",Integer.class,id);
            parameters.review(id,new ProductParameterService.Review(version),1L,true);
        }
        Long routeId=jdbc.queryForObject("SELECT route_id FROM process_route WHERE route_code='LAMP-ROUTE-L200-V01' AND deleted_flag=0",Long.class);
        if("DRAFT".equals(jdbc.queryForObject("SELECT status FROM process_route WHERE route_id=?",String.class,routeId))){
            var route=new ProcessRoute();route.setRouteId(routeId);route.setStatus("RELEASED");
            assertTrue(routes.updateRoute(route));
        }
        for(String code:java.util.List.of("WS-FG-01","WS-AGING-01")){
            Long stationId=jdbc.queryForObject("SELECT station_id FROM factory_station WHERE station_code=? AND deleted_flag=0",Long.class,code);
            if(!"RUNNING".equals(jdbc.queryForObject("SELECT status FROM factory_station WHERE station_id=?",String.class,stationId))){
                var station=new FactoryStation();station.setStationId(stationId);station.setStatus("RUNNING");
                assertTrue(stations.updateStation(station));
            }
        }
        Long oldBom=jdbc.queryForObject("SELECT bom_id FROM mdm_bom WHERE product_id=? AND status='RELEASED' AND deleted_flag=0 ORDER BY bom_id LIMIT 1",Long.class,productId);
        Integer unbound=jdbc.queryForObject("SELECT COUNT(*) FROM mdm_bom_item WHERE bom_id=? AND deleted_flag=0 AND consumption_operation_id IS NULL",Integer.class,oldBom);
        if(unbound==null || unbound==0)return;
        Long operationId=jdbc.queryForObject("SELECT operation_id FROM process_operation WHERE operation_code='LAMP-OP-F10' AND deleted_flag=0",Long.class);
        var created=new MdmBom();created.setBomCode("LAMP-BOM-L200-V2");created.setBomVersion("V2");
        created.setProductId(productId);created.setEffectiveDate(LocalDate.of(2026,10,19));created.setRemark("补齐投料工序");
        assertTrue(boms.saveBom(created));
        for(var row:jdbc.queryForList("SELECT material_id,child_bom_id,quantity,loss_rate,sequence_no FROM mdm_bom_item WHERE bom_id=? AND deleted_flag=0 ORDER BY sequence_no",oldBom)){
            var item=new MdmBomItem();item.setBomId(created.getBomId());
            item.setMaterialId(((Number)row.get("material_id")).longValue());
            Object child=row.get("child_bom_id");if(child instanceof Number number)item.setChildBomId(number.longValue());
            item.setQuantity(new java.math.BigDecimal(row.get("quantity").toString()));
            if(row.get("loss_rate")!=null)item.setLossRate(new java.math.BigDecimal(row.get("loss_rate").toString()));
            item.setSequenceNo(((Number)row.get("sequence_no")).intValue());
            item.setConsumptionOperationId(operationId);
            assertTrue(bomItems.saveBomItem(item));
        }
        var publish=new MdmBom();publish.setBomId(created.getBomId());publish.setStatus("RELEASED");
        assertTrue(boms.updateBom(publish));
    }

    /**
     * 取已生效排班的最早日期。台灯种子故意不写排班，缺失时按已启用产线走排班服务：先草稿，再生效。
     * 组件线用组件班组，总装线用总装班组，日期取周历生效后的第一个周一。
     */
    private LocalDate releasedScheduleDate(){
        LocalDate existing=jdbc.queryForObject(
                "SELECT MIN(work_date) FROM org_team_schedule WHERE deleted_flag=0 AND status='RELEASED'",LocalDate.class);
        if(existing!=null)return existing;
        LocalDate asOf=LocalDate.of(2026,10,19);
        Long shiftId=jdbc.queryForObject(
                "SELECT shift_id FROM org_shift WHERE shift_code='LAMP-SHIFT-AM' AND deleted_flag=0",Long.class);
        Long componentTeam=jdbc.queryForObject(
                "SELECT team_id FROM org_team WHERE team_code='LAMP-TEAM-COMPONENT' AND deleted_flag=0",Long.class);
        Long finalTeam=jdbc.queryForObject(
                "SELECT team_id FROM org_team WHERE team_code='LAMP-TEAM-FINAL' AND deleted_flag=0",Long.class);
        Long finalLine=jdbc.queryForObject(
                "SELECT line_id FROM factory_line WHERE line_code='LAMP-LINE-FINAL' AND deleted_flag=0",Long.class);
        for(Long lineId:jdbc.queryForList(
                "SELECT DISTINCT line_id FROM mfg_line_product_capability WHERE deleted_flag=0 AND status='ENABLE'",Long.class)){
            var row=new OrgTeamSchedule();
            row.setTeamId(lineId.equals(finalLine)?finalTeam:componentTeam);
            row.setLineId(lineId);
            row.setShiftId(shiftId);
            row.setWorkDate(asOf);
            row.setPlannedHeadcount(2);
            assertTrue(teamSchedules.saveSchedule(row));
            assertTrue(teamSchedules.releaseSchedule(row.getScheduleId()));
        }
        return asOf;
    }

    /**
     * 把 L200 的检验标准与检验方案恢复成演示基线（标准已确认、方案已发布）。
     * 供不参与事务回滚的用例做清理，保证同一库上的用例顺序无关。
     */
    private void restoreL200QualityBaseline(){
        Long productId=product("LAMP-L200");
        jdbc.update("UPDATE qms_inspection_standard SET confirmation_status='CONFIRMED' WHERE product_id=?",productId);
        jdbc.update("UPDATE qms_inspection_scheme SET status='RELEASED' WHERE product_id=? AND scheme_code LIKE 'LAMP-V18-QS-L200-%'",productId);
    }
    @Test void directCrudCannotConfirmStandardOrRemoveProductControl(){
        Long id=jdbc.queryForObject("SELECT standard_id FROM qms_inspection_standard WHERE product_id=? LIMIT 1",Long.class,product("LAMP-L200"));
        jdbc.update("UPDATE qms_inspection_standard SET confirmation_status='DRAFT' WHERE standard_id=?", id);
        var existing=standards.getStandardById(id);
        var update=new QmsInspectionStandard();update.setStandardId(id);update.setConfirmationStatus("CONFIRMED");update.setVersion(existing.getVersion());
        assertTrue(standards.updateStandard(update));assertEquals("DRAFT",standards.getStandardById(id).getConfirmationStatus());
        var productUpdate=new MdmProduct();productUpdate.setProductId(product("LAMP-L200"));productUpdate.setPreparationControlled(0);
        productUpdate.setRemark("回滚用例，不保留");assertTrue(products.updateProduct(productUpdate));
        assertEquals(1,products.getProductById(productUpdate.getProductId()).getPreparationControlled());
    }
    @Test void standardConfirmationLocksItsContent(){
        Long id=jdbc.queryForObject("SELECT standard_id FROM qms_inspection_standard WHERE product_id=? LIMIT 1",Long.class,product("LAMP-HEAD"));
        jdbc.update("UPDATE qms_inspection_standard SET confirmation_status='DRAFT' WHERE standard_id=?", id);
        var existing=standards.getStandardById(id);
        var confirmed=review.review(id,existing.getVersion(),1L,true);
        assertEquals("CONFIRMED",confirmed.getConfirmationStatus());
        var edit=new QmsInspectionStandard();edit.setStandardId(id);edit.setStandardValue("改变已确认内容");
        assertThrows(BusinessException.class,()->standards.updateStandard(edit));
    }
    @Test void executionGateAllowsControlledProductsAndRejectsInvalidOnes(){
        // 执行链贯通后受控产品可以进入执行环节，组件与整机适用同一规则
        assertDoesNotThrow(()->gate.assertExecutionSupported(product("LAMP-HEAD")));
        assertDoesNotThrow(()->gate.assertExecutionSupported(product("LAMP-L200")));
        assertDoesNotThrow(()->gate.assertExecutionSupported(product("LAMP-L100")));
        assertDoesNotThrow(()->gate.assertExecutionSupported(product("LAMP-ARM")));
        assertDoesNotThrow(()->gate.assertExecutionSupported(product("LAMP-BASE-L200")));
        // 产品本身不合法时仍然拦截
        assertThrows(BusinessException.class,()->gate.assertExecutionSupported(null));
        assertThrows(BusinessException.class,()->gate.assertExecutionSupported(-1L));
    }
    @Test void draftControlledSchemeCannotCreateInspectionResults(){
        Long schemeId=jdbc.queryForObject("SELECT scheme_id FROM qms_inspection_scheme WHERE scheme_code='LAMP-V18-QS-L200-IQC'",Long.class);
        jdbc.update("UPDATE qms_inspection_scheme SET status='DRAFT' WHERE scheme_id=?", schemeId);
        var request=new QmsInspectionOrder();request.setInspectionType("INCOMING");
        request.setSchemeId(schemeId);
        assertThrows(BusinessException.class,()->inspections.saveInspectionOrder(request));
    }
    @Test void removedMeasurementUnitPreventsStandardConfirmation(){
        Long id=jdbc.queryForObject("SELECT s.standard_id FROM qms_inspection_standard s JOIN qms_inspection_item i ON i.item_id=s.item_id WHERE s.product_id=? AND i.item_code='LAMP-QI-LED-I'",Long.class,product("LAMP-HEAD"));
        var existing=standards.getStandardById(id);
        jdbc.update("UPDATE mdm_unit SET deleted_flag=1 WHERE unit_code='LAMP-MEASURE-A'");
        assertThrows(BusinessException.class,()->review.review(id,existing.getVersion(),1L,true));
    }
    @Test void controlledStandardEditCannotOmitItsVersion(){
        Long id=jdbc.queryForObject("SELECT standard_id FROM qms_inspection_standard WHERE product_id=? LIMIT 1",Long.class,product("LAMP-L200"));
        jdbc.update("UPDATE qms_inspection_standard SET confirmation_status='DRAFT' WHERE standard_id=?", id);
        var edit=new QmsInspectionStandard();edit.setStandardId(id);edit.setSourceReference("未提供版本的编辑");
        assertThrows(OptimisticLockConflictException.class,()->standards.updateStandard(edit));
    }
    @Test void batchEquipmentRequiresIntegerCapacityAndAnEvidenceReference(){
        var result=preparation.inspect(product("LAMP-L200"),LocalDate.of(2026,10,19));
        var aging=result.routes().getFirst().steps().stream().filter(s->"BATCH_EQUIPMENT".equals(s.item().getResourceType())).findFirst().orElseThrow().item();
        assertTrue(preparation.validTiming(aging));
        aging.setBatchCapacity(new java.math.BigDecimal("1.5"));assertFalse(preparation.validTiming(aging));
        aging.setBatchCapacity(java.math.BigDecimal.TEN);aging.setTimeSourceReference(null);assertFalse(preparation.validTiming(aging));
    }
    @Test void publishedSchemeBlocksItemChangesAndStandardReopen(){
        Long id=jdbc.queryForObject("SELECT scheme_id FROM qms_inspection_scheme WHERE scheme_code='LAMP-V18-QS-L200-FINAL'",Long.class);
        jdbc.update("UPDATE qms_inspection_scheme SET status='DRAFT' WHERE scheme_id=?", id);
        jdbc.update("UPDATE qms_inspection_standard SET confirmation_status='DRAFT' WHERE product_id=(SELECT product_id FROM qms_inspection_scheme WHERE scheme_id=?)", id);
        var links=schemes.listSchemeItems(id);
        for(var link:links){var standard=standards.getStandardById(link.getStandardId());review.review(standard.getStandardId(),standard.getVersion(),1L,true);}
        var publish=new QmsInspectionScheme();publish.setSchemeId(id);publish.setStatus("RELEASED");
        assertTrue(schemes.updateScheme(publish));assertEquals("RELEASED",schemes.getSchemeById(id).getStatus());
        assertThrows(BusinessException.class,()->schemeItems.removeSchemeItemById(links.getFirst().getId()));
        var standard=standards.getStandardById(links.getFirst().getStandardId());
        assertThrows(BusinessException.class,()->review.review(standard.getStandardId(),standard.getVersion(),1L,false));
        assertDoesNotThrow(()->gate.assertExecutionSupported(product("LAMP-L200")));
    }
    @Test void creatingAnotherProductCannotBypassPreparationControl(){
        var original=products.getProductById(product("LAMP-L100"));
        var created=new MdmProduct();created.setProductCode("R1-ROLLBACK-"+System.nanoTime());
        created.setProductName("工程准备回滚用例");created.setMaterialId(original.getMaterialId());created.setStatus("ENABLE");created.setPreparationControlled(0);
        assertTrue(products.saveProduct(created));assertEquals(1,products.getProductById(created.getProductId()).getPreparationControlled());
        // 复用物料另建产品也不能绕过受控标记，但仍可进入执行链
        assertDoesNotThrow(()->gate.assertExecutionSupported(created.getProductId()));
        assertFalse(preparation.inspect(created.getProductId(),LocalDate.of(2026,10,19)).configurationReady());
    }
}
