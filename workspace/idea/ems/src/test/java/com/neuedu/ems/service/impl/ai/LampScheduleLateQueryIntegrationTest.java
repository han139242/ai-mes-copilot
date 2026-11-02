package com.neuedu.ems.service.impl.ai;

import com.neuedu.ems.PhoneCryptoTestProperties;
import com.neuedu.ems.entity.ai.AiBlock;
import com.neuedu.ems.service.ai.AiIntentRouter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「哪些订单延期」要查到本周已确认、并且写了延期分钟的排程，不能把准时的订单也列出来。
 */
@SpringBootTest(properties = {"mes.notification.scheduling-enabled=false", "mes.ai.enabled=false",
        PhoneCryptoTestProperties.ACTIVE_KEY, PhoneCryptoTestProperties.KEY_IDS,
        PhoneCryptoTestProperties.ENCRYPTION_KEY, PhoneCryptoTestProperties.LOOKUP_HMAC_KEY})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:(3307|3310)/mes_lamp_lineside_[A-Za-z0-9_]+.*")
@Transactional
class LampScheduleLateQueryIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired AiIntentRouter router;

    @Test
    void delayedFollowUpListsOnlyTheLateConfirmedOrder() {
        String tag = "LATE" + System.nanoTime();
        LocalDate monday = LocalDate.now().with(DayOfWeek.MONDAY);
        LocalDateTime start = monday.atTime(9, 0);
        LocalDateTime end = monday.atTime(11, 0);
        Long productId = jdbc.queryForObject(
                "SELECT product_id FROM mdm_product WHERE product_code='LAMP-L200' AND deleted_flag=0", Long.class);
        Long lateLineId = jdbc.queryForObject(
                "SELECT line_id FROM factory_line WHERE line_code='LAMP-LINE-FINAL' AND deleted_flag=0", Long.class);
        Long onTimeLineId = jdbc.queryForObject(
                "SELECT line_id FROM factory_line WHERE line_code='LAMP-LINE-HEAD' AND deleted_flag=0", Long.class);
        Long scenarioId = insertScenario(tag, start, end);
        Long lateOrderId = insertOrder(tag + "-L", productId);
        Long onTimeOrderId = insertOrder(tag + "-O", productId);
        Long latePlanId = insertPlan(tag + "-L", lateOrderId, lateLineId, start, end);
        Long onTimePlanId = insertPlan(tag + "-O", onTimeOrderId, onTimeLineId, start, end);
        insertItem(scenarioId, lateOrderId, productId, lateLineId, latePlanId, 1, start, end, 30);
        insertItem(scenarioId, onTimeOrderId, productId, onTimeLineId, onTimePlanId, 2, start, end, 0);

        var route = router.route("哪些订单延期", List.of("本周排程和产能怎么样"), null);
        assertTrue(route.matched());
        assertEquals(AiIntentRouterImpl.INTENT_SCHEDULE, route.intent());
        assertEquals(Boolean.TRUE, route.params().get("lateOnly"));

        var result = router.execute(route);
        assertTrue(result.getSummary().contains("当前只列出延期任务。"));
        assertTrue(orderNos(result.getBlocks()).contains(tag + "-L"));
        assertFalse(orderNos(result.getBlocks()).contains(tag + "-O"));
        assertTrue(result.getRows().stream().anyMatch(row -> "LAMP-LINE-FINAL".equals(row.get("lineCode"))));
        assertTrue(result.getRows().stream().noneMatch(row ->
                "LAMP-LINE-HEAD".equals(row.get("lineCode")) && onTimeOnly(row)));
    }

    private Long insertScenario(String tag, LocalDateTime start, LocalDateTime end) {
        jdbc.update("""
                INSERT INTO mfg_schedule_scenario(scenario_no, scenario_name, horizon_start, horizon_end,
                    algorithm_code, status, deleted_flag)
                VALUES (?,?,?,?, 'RULE', 'CONFIRMED', 0)
                """, tag, tag, start, end);
        return jdbc.queryForObject(
                "SELECT scenario_id FROM mfg_schedule_scenario WHERE scenario_no=?", Long.class, tag);
    }

    private Long insertOrder(String orderNo, Long productId) {
        jdbc.update("""
                INSERT INTO mfg_production_order(production_order_no, product_id, order_quantity, status, deleted_flag)
                VALUES (?,?,1,'RELEASED',0)
                """, orderNo, productId);
        return jdbc.queryForObject(
                "SELECT production_order_id FROM mfg_production_order WHERE production_order_no=?", Long.class, orderNo);
    }

    private Long insertPlan(String planNo, Long orderId, Long lineId, LocalDateTime start, LocalDateTime end) {
        jdbc.update("""
                INSERT INTO mfg_production_plan(plan_no, production_order_id, line_id, planned_start_time,
                    planned_end_time, planned_quantity, status, deleted_flag)
                VALUES (?,?,?,?,?,1,'RELEASED',0)
                """, planNo, orderId, lineId, start, end);
        return jdbc.queryForObject(
                "SELECT plan_id FROM mfg_production_plan WHERE plan_no=?", Long.class, planNo);
    }

    private void insertItem(Long scenarioId, Long orderId, Long productId, Long lineId, Long planId, int sequence,
                            LocalDateTime start, LocalDateTime end, long tardiness) {
        jdbc.update("""
                INSERT INTO mfg_schedule_item(scenario_id, production_order_id, product_id, line_id, plan_id,
                    sequence_no, planned_quantity, planned_start_time, planned_end_time, estimated_minutes,
                    priority_score, tardiness_minutes, decision_source, status, deleted_flag)
                VALUES (?,?,?,?,?,?,1,?,?,30,0,?,'RULE_ENGINE','CONFIRMED',0)
                """, scenarioId, orderId, productId, lineId, planId, sequence, start, end, tardiness);
    }

    private List<String> orderNos(List<AiBlock> blocks) {
        return blocks.stream()
                .flatMap(block -> block.getRows().stream())
                .map(row -> String.valueOf(row.get("productionOrderNo")))
                .toList();
    }

    private boolean onTimeOnly(Map<String, Object> row) {
        Object lateCount = row.get("lateCount");
        return lateCount instanceof Number number && number.longValue() == 0;
    }
}
