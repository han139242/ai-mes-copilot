package com.neuedu.ems.service.mfg;

import com.neuedu.ems.PhoneCryptoTestProperties;
import com.neuedu.ems.entity.mfg.MfgWorkOrder;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.mfg.MfgWorkOrderMapper;
import com.neuedu.ems.service.mfg.MfgWorkOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

/** 只在本机验证库构造主数据夹具，所有修改回滚，不作为实际生产证据。 */
@SpringBootTest(properties={"mes.notification.scheduling-enabled=false","mes.ai.enabled=false",
        PhoneCryptoTestProperties.ACTIVE_KEY,PhoneCryptoTestProperties.KEY_IDS,
        PhoneCryptoTestProperties.ENCRYPTION_KEY,PhoneCryptoTestProperties.LOOKUP_HMAC_KEY})
@EnabledIfEnvironmentVariable(named="DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:(3307|3310)/mes_lamp_lineside_.*")
@Transactional
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class WorkOrderSnapshotIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired WorkOrderSnapshotService snapshots;
    @Autowired ExecutionSnapshotPolicy policy;
    @Autowired MfgWorkOrderMapper orders;
    @Autowired MfgWorkOrderService service;
    @Autowired com.neuedu.ems.service.mdm.MdmBomService bomService;
    @Autowired com.neuedu.ems.service.impl.mfg.MfgWorkReportServiceImpl reports;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired com.neuedu.ems.util.JwtUtil jwt;
    @Autowired org.mybatis.spring.SqlSessionTemplate session;
    MfgWorkOrder order;
    Long product,bom,route;
    @BeforeEach void fixture() {
        product=jdbc.queryForObject("SELECT product_id FROM mdm_product WHERE product_code='LAMP-HEAD'",Long.class);
        route=jdbc.queryForObject("SELECT route_id FROM process_route WHERE route_code='LAMP-ROUTE-HEAD-V01'",Long.class);
        String tag="SNAP"+System.nanoTime();
        jdbc.update("INSERT INTO mdm_bom(bom_code,product_id,bom_version,effective_date,status,version,deleted_flag) VALUES (?,?,'TEST-V2','2026-10-24','RELEASED',0,0)",tag,product);
        bom=jdbc.queryForObject("SELECT bom_id FROM mdm_bom WHERE bom_code=?",Long.class,tag);
        Long first=jdbc.queryForObject("SELECT operation_id FROM process_route_item WHERE route_id=? AND deleted_flag=0 ORDER BY sequence_no LIMIT 1",Long.class,route);
        jdbc.update("INSERT INTO mdm_bom_item(bom_id,material_id,quantity,loss_rate,sequence_no,consumption_operation_id,version,deleted_flag) SELECT ?,i.material_id,i.quantity,i.loss_rate,i.sequence_no,?,0,0 FROM mdm_bom_item i JOIN mdm_bom b ON b.bom_id=i.bom_id WHERE b.product_id=? AND b.bom_id<>? AND b.deleted_flag=0 AND i.deleted_flag=0",bom,first,product,bom);
        jdbc.update("UPDATE process_route SET status='RELEASED' WHERE route_id=?",route);
        jdbc.update("UPDATE process_operation SET status='ENABLE' WHERE operation_id IN (SELECT operation_id FROM process_route_item WHERE route_id=?)",route);
        jdbc.update("UPDATE mdm_product_parameter SET status='CONFIRMED' WHERE product_id=? AND deleted_flag=0",product);
        jdbc.update("UPDATE qms_inspection_scheme SET status='RELEASED' WHERE product_id=? AND remark='LAMP_REPORT_V18' AND deleted_flag=0",product);
        Long terminal=jdbc.queryForObject("SELECT operation_id FROM process_route_item WHERE route_id=? AND deleted_flag=0 ORDER BY sequence_no DESC LIMIT 1",Long.class,route);
        jdbc.update("UPDATE qms_inspection_scheme SET operation_id=? WHERE product_id=? AND remark='LAMP_REPORT_V18' AND inspection_type='FINAL' AND deleted_flag=0",terminal,product);
        jdbc.update("UPDATE qms_inspection_standard SET confirmation_status='CONFIRMED' WHERE product_id=? AND remark='LAMP_REPORT_V18' AND deleted_flag=0",product);
        order=new MfgWorkOrder();order.setWorkOrderNo(tag);order.setProductId(product);order.setBomId(bom);order.setRouteId(route);
        order.setPlannedQuantity(BigDecimal.TEN);order.setCompletedQuantity(BigDecimal.ZERO);order.setWorkOrderType("COMPONENT");
        order.setStatus("DRAFT");order.setDeletedFlag(0);orders.insert(order);
    }
    @Test void frozenValuesAndQuantityRulesSurviveSourceChanges() {
        String content=snapshots.freeze(order,LocalDate.of(2026,10,24),LocalDateTime.of(2026,10,24,10,0),1L);
        order.setSnapshotJson(content);order.setSnapshotHash(ExecutionSnapshotPolicy.hash(content));orders.updateById(order);
        var before=policy.read(order);
        var demand=policy.directDemands(before,BigDecimal.TEN);
        jdbc.update("UPDATE mdm_bom_item SET quantity=quantity+100 WHERE bom_id=?",bom);
        jdbc.update("UPDATE mdm_material SET issue_step=2 WHERE material_id IN (SELECT material_id FROM mdm_bom_item WHERE bom_id=?)",bom);
        jdbc.update("UPDATE qms_inspection_standard SET upper_limit='999' WHERE product_id=?",product);
        jdbc.update("UPDATE mdm_product_parameter SET parameter_value='CHANGED' WHERE product_id=?",product);
        jdbc.update("UPDATE process_route_item SET sequence_no=sequence_no+100,operation_id=999999 WHERE route_id=?",route);
        jdbc.update("UPDATE qms_inspection_scheme SET status='DISABLE' WHERE product_id=?",product);
        var saved=orders.selectById(order.getWorkOrderId());
        assertEquals(before,policy.read(saved));
        assertEquals(demand,policy.directDemands(policy.read(saved),BigDecimal.TEN));
        long expectedLast=before.path("routeItems").get(before.path("routeItems").size()-1).path("operationId").asLong();
        assertEquals(expectedLast,policy.routeItems(saved).getLast().getOperationId());
        assertEquals(expectedLast,org.springframework.test.util.ReflectionTestUtils.<Long>invokeMethod(reports,"finalOperationId",saved));
        assertThrows(BusinessException.class,()->snapshots.freeze(saved,LocalDate.of(2026,10,24),LocalDateTime.now(),1L));
    }
    @Test void missingConsumptionBindingBlocksFreezeWithoutChangingOrder() {
        jdbc.update("UPDATE mdm_bom_item SET consumption_operation_id=NULL WHERE bom_id=?",bom);
        assertTrue(assertThrows(BusinessException.class,()->snapshots.freeze(order,LocalDate.of(2026,10,24),LocalDateTime.now(),1L)).getMessage().contains("投料工序"));
        assertNull(orders.selectById(order.getWorkOrderId()).getSnapshotJson());
    }
    @Test void unconfirmedParameterBlocksFreezeAndExecutionGateStaysClosed() {
        jdbc.update("UPDATE mdm_product_parameter SET status='DRAFT',required_flag=1 WHERE product_id=?",product);
        assertThrows(BusinessException.class,()->snapshots.freeze(order,LocalDate.of(2026,10,24),LocalDateTime.now(),1L));
        assertThrows(BusinessException.class,()->service.releaseWorkOrder(order.getWorkOrderId(),1L,null));
        assertNull(orders.selectById(order.getWorkOrderId()).getSnapshotJson());
    }
    @Test void bomHttpExposesBindingAndRejectsPublishedVersionEdits() throws Exception {
        Long row=jdbc.queryForObject("SELECT bom_item_id FROM mdm_bom_item WHERE bom_id=? LIMIT 1",Long.class,bom);
        Long operation=jdbc.queryForObject("SELECT consumption_operation_id FROM mdm_bom_item WHERE bom_item_id=?",Long.class,row);
        Long actor=jdbc.queryForObject("SELECT user_id FROM sys_user WHERE username='admin' AND deleted_flag=0",Long.class);
        String token=jwt.generateToken(actor,"admin");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/mdm/bom-item/"+row)
                .header("Authorization","Bearer "+token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.consumptionOperationId").value(operation));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/mdm/bom-item")
                .header("Authorization","Bearer "+token).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"bomItemId\":"+row+",\"consumptionOperationId\":999999}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(org.hamcrest.Matchers.not(200)));
        assertEquals(operation,jdbc.queryForObject("SELECT consumption_operation_id FROM mdm_bom_item WHERE bom_item_id=?",Long.class,row));
        jdbc.update("UPDATE mdm_bom SET status='DRAFT' WHERE bom_id=?",bom);
        session.clearCache(); // JDBC夹具绕过了MyBatis的缓存失效，真实服务更新会自动清理。
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/mdm/bom-item")
                .header("Authorization","Bearer "+token).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"bomItemId\":"+row+",\"consumptionOperationId\":0,\"version\":0}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(200));
        assertNull(jdbc.queryForObject("SELECT consumption_operation_id FROM mdm_bom_item WHERE bom_item_id=?",Long.class,row));
    }
    @Test void newControlledBomCannotBePublishedWithoutConsumptionBinding() {
        jdbc.update("UPDATE mdm_bom SET status='DRAFT' WHERE bom_id=?",bom);
        jdbc.update("UPDATE mdm_bom_item SET consumption_operation_id=NULL WHERE bom_id=?",bom);
        var command=new com.neuedu.ems.entity.mdm.MdmBom();command.setBomId(bom);command.setStatus("RELEASED");command.setVersion(0);
        // 使用真正BOM服务事务验证失败不发布，不绕过其发布门槛。
        assertThrows(BusinessException.class,()->bomService.updateBom(command));
        assertEquals("DRAFT",jdbc.queryForObject("SELECT status FROM mdm_bom WHERE bom_id=?",String.class,bom));
    }
    @Test void finalInspectionWithoutExplicitOperationCannotBeFrozen() {
        jdbc.update("UPDATE qms_inspection_scheme SET operation_id=NULL WHERE product_id=? AND remark='LAMP_REPORT_V18' AND inspection_type='FINAL'",product);
        assertThrows(BusinessException.class,()->snapshots.freeze(order,LocalDate.of(2026,10,24),LocalDateTime.now(),1L));
        assertNull(orders.selectById(order.getWorkOrderId()).getSnapshotJson());
    }
}
