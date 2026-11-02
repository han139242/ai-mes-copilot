package com.neuedu.ems.service.mfg;

import com.neuedu.ems.PhoneCryptoTestProperties;
import com.neuedu.ems.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"mes.notification.scheduling-enabled=false", "mes.ai.enabled=false",
        PhoneCryptoTestProperties.ACTIVE_KEY, PhoneCryptoTestProperties.KEY_IDS,
        PhoneCryptoTestProperties.ENCRYPTION_KEY, PhoneCryptoTestProperties.LOOKUP_HMAC_KEY})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:(3307|3310)/mes_lamp_lineside_.*")
@Transactional
@AutoConfigureMockMvc
class WorkOrderBatchIntegrationTest extends ProductionOrderProgressTestFixture {
    @Autowired MfgWorkOrderService workOrders;

    @Test void batchDeleteDeduplicatesAndSoftDeletesAllDrafts() throws Exception {
        Long order = insertOrder("RELEASED", BigDecimal.TEN);
        Long plan = insertPlan(order, BigDecimal.TEN, "RELEASED", "ROOT", null);
        Long first = insertWorkOrder("FIRST", plan, product, BigDecimal.ONE, "DRAFT", null);
        Long second = insertWorkOrder("SECOND", plan, product, BigDecimal.ONE, "DRAFT", null);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/mfg/work-order/batch")
                        .header("Authorization", "Bearer " + jwt.generateToken(adminId(), "admin"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(List.of(first, second, first))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(200))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data").value(2));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM mfg_work_order WHERE work_order_id IN (?,?) AND deleted_flag=1", Integer.class, first, second));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE operation_type='BATCH_DELETE_WORK_ORDER' AND biz_id IN (?,?)", Integer.class, first, second));
    }

    @Test void oneNonDraftPreventsTheWholeBatchFromBeingDeleted() {
        Long order = insertOrder("RELEASED", BigDecimal.TEN);
        Long plan = insertPlan(order, BigDecimal.TEN, "RELEASED", "ROOT", null);
        Long first = insertWorkOrder("DRAFT", plan, product, BigDecimal.ONE, "DRAFT", null);
        Long second = insertWorkOrder("RUNNING", plan, product, BigDecimal.ONE, "RUNNING", null);
        assertThrows(BusinessException.class, () -> workOrders.removeWorkOrders(List.of(first, second), adminId()));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM mfg_work_order WHERE work_order_id IN (?,?) AND deleted_flag=0", Integer.class, first, second));
    }

    @Test void staleIdsAndEmptyBatchesAreRejected() {
        assertThrows(BusinessException.class, () -> workOrders.removeWorkOrders(List.of(), adminId()));
        assertThrows(BusinessException.class, () -> workOrders.removeWorkOrders(List.of(Long.MAX_VALUE), adminId()));
    }

    private Long adminId() {
        return jdbc.queryForObject("SELECT user_id FROM sys_user WHERE username='admin' AND deleted_flag=0", Long.class);
    }
}
