package com.neuedu.ems.service.mfg;

import com.neuedu.ems.PhoneCryptoTestProperties;
import com.neuedu.ems.entity.mfg.MfgDispatchOrder;
import com.neuedu.ems.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"mes.notification.scheduling-enabled=false", "mes.ai.enabled=false",
        PhoneCryptoTestProperties.ACTIVE_KEY, PhoneCryptoTestProperties.KEY_IDS,
        PhoneCryptoTestProperties.ENCRYPTION_KEY, PhoneCryptoTestProperties.LOOKUP_HMAC_KEY})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:(3307|3310)/mes_lamp_lineside_.*")
@Transactional
@AutoConfigureMockMvc
class DispatchPreparationIntegrationTest extends ProductionOrderProgressTestFixture {
    @Autowired MfgDispatchOrderService dispatch;
    Long workOrder, team;
    LocalDateTime from = java.time.LocalDate.now().plusWeeks(1)
            .with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.MONDAY)).atTime(8, 0);

    @BeforeEach void executionFixture() {
        lineId = jdbc.queryForObject("SELECT line_id FROM factory_line WHERE line_code IN ('LAMP-LINE-L200','LAMP-LINE-FINAL') AND deleted_flag=0 ORDER BY CASE line_code WHEN 'LAMP-LINE-L200' THEN 0 ELSE 1 END LIMIT 1", Long.class);
        routeId = jdbc.queryForObject("SELECT route_id FROM mfg_line_product_capability WHERE line_id=? AND product_id=? AND status='ENABLE' AND deleted_flag=0 ORDER BY priority_level LIMIT 1", Long.class, lineId, product);
        jdbc.update("UPDATE process_route SET status='RELEASED' WHERE route_id=?", routeId);
        bomId = jdbc.queryForObject("SELECT bom_id FROM mdm_bom WHERE product_id=? AND status='RELEASED' AND deleted_flag=0 ORDER BY effective_date DESC LIMIT 1", Long.class, product);
        team = jdbc.queryForObject("SELECT team_id FROM org_team WHERE deleted_flag=0 AND leader_id IS NOT NULL ORDER BY team_id DESC LIMIT 1", Long.class);
        Long order = insertOrder("RELEASED", BigDecimal.TEN);
        Long plan = insertPlan(order, BigDecimal.TEN, "RELEASED", "ROOT", null);
        jdbc.update("UPDATE mfg_production_plan SET planned_start_time=?,planned_end_time=? WHERE plan_id=?",
                Timestamp.valueOf(from), Timestamp.valueOf(from.plusDays(6)), plan);
        workOrder = insertWorkOrder("DISPATCH", plan, product, BigDecimal.TEN, "RELEASED", null);
        insertRequirement(workOrder, inputMaterial, BigDecimal.TEN, BigDecimal.ZERO,
                BigDecimal.TEN, "WAIT_READY", "STOCK", null, BigDecimal.ZERO);
        jdbc.update("DELETE FROM org_team_schedule WHERE line_id=? AND work_date BETWEEN ? AND ?",
                lineId, from.toLocalDate(), from.plusDays(7).toLocalDate());
    }

    private void scheduleTeam() {
        for (int i = 0; i < 7; i++) {
            var day = from.toLocalDate().plusDays(i);
            jdbc.update("INSERT INTO org_team_schedule(schedule_no,team_id,line_id,shift_id,work_date,planned_headcount,status,version,deleted_flag) "
                            + "SELECT CONCAT(?, '-',shift_id),?,?,shift_id,?,1,'RELEASED',0,0 FROM org_shift WHERE shift_code IN ('LAMP-SHIFT-AM','LAMP-SHIFT-PM') AND deleted_flag=0",
                    tag(), team, lineId, day);
        }
    }

    @Test void missingScheduleBlocksGenerationWithActionableMessage() {
        var preview = dispatch.previewDispatch(workOrder);
        assertTrue(preview.teams().isEmpty());
        assertTrue(preview.blockers().stream().anyMatch(b -> b.contains("日期排班")));
        var request = new MfgDispatchOrder(); request.setTeamId(team);
        assertThrows(BusinessException.class, () -> dispatch.generateDispatchOrder(workOrder, request, 1L));
    }

    @Test void generatedTimeIsComputedAndWrongTeamOrMissingMaterialCannotBypassChecks() {
        scheduleTeam();
        var preview = dispatch.previewDispatch(workOrder);
        assertTrue(preview.blockers().isEmpty(), preview.blockers().toString());
        assertEquals(team, preview.teams().get(0).teamId());
        var request = new MfgDispatchOrder(); request.setTeamId(-1L);
        assertThrows(BusinessException.class, () -> dispatch.generateDispatchOrder(workOrder, request, 1L));
        request.setTeamId(team); request.setDueTime(from.plusYears(50));
        var notReady = assertThrows(BusinessException.class, () -> dispatch.generateDispatchOrder(workOrder, request, 1L));
        assertTrue(notReady.getMessage().contains("尚未实际到位"), notReady.getMessage());
        jdbc.update("UPDATE mfg_work_order_material SET issued_quantity=required_quantity,shortage_quantity=0,status='READY' WHERE work_order_id=?", workOrder);
        session.clearCache();
        var created = dispatch.generateDispatchOrder(workOrder, request, 1L);
        assertEquals(preview.dueTime(), created.getDueTime());
        assertEquals(created.getDueTime(), orders.selectById(workOrder).getPlannedEndTime());
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM mfg_task", Long.class);
        // 生成后到位数量若发生变化，正式派发仍必须重新校验，不能只认旧的齐套状态。
        jdbc.update("UPDATE mfg_work_order_material SET issued_quantity=0,shortage_quantity=required_quantity,status='WAIT_READY' WHERE work_order_id=?", workOrder);
        session.clearCache();
        BusinessException failure = assertThrows(BusinessException.class, () -> dispatch.releaseDispatchOrder(created.getDispatchId(), 1L, null));
        assertTrue(failure.getMessage().contains("物料未齐套"), failure.getMessage());
        assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM mfg_task", Long.class));
        jdbc.update("UPDATE mfg_work_order_material SET issued_quantity=required_quantity,shortage_quantity=0,status='READY' WHERE work_order_id=?", workOrder);
        session.clearCache();
        var tasks = dispatch.releaseDispatchOrder(created.getDispatchId(), 1L, "验证齐套后正式派发");
        assertFalse(tasks.isEmpty());
        assertTrue(tasks.stream().allMatch(t -> workOrder.equals(t.getWorkOrderId()) && t.getStationId() != null));
        assertEquals(before + tasks.size(), jdbc.queryForObject("SELECT COUNT(*) FROM mfg_task", Long.class));
    }
}
