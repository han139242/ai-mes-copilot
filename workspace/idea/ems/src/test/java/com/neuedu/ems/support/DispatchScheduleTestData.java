package com.neuedu.ems.support;

import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 集成夹具显式提供真实产线日期排班，不绕过派工服务的排班校验。 */
public final class DispatchScheduleTestData {
    private DispatchScheduleTestData() {}

    public static void coverWorkOrder(JdbcTemplate jdbc, Long workOrderId, Long teamId) {
        Long line = jdbc.queryForObject("SELECT p.line_id FROM mfg_work_order w JOIN mfg_production_plan p ON p.plan_id=w.plan_id WHERE w.work_order_id=?", Long.class, workOrderId);
        LocalDateTime finish = jdbc.queryForObject("SELECT p.planned_end_time FROM mfg_work_order w JOIN mfg_production_plan p ON p.plan_id=w.plan_id WHERE w.work_order_id=?", LocalDateTime.class, workOrderId);
        LocalDate from = LocalDate.now().minusDays(1);
        LocalDate to = finish == null ? from.plusDays(14) : finish.toLocalDate().plusDays(1);
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            for (Long shift : jdbc.queryForList("SELECT DISTINCT shift_id FROM mfg_line_calendar_rule WHERE line_id=? AND deleted_flag=0 AND status='ENABLE'", Long.class, line)) {
                jdbc.update("INSERT INTO org_team_schedule(schedule_no,team_id,line_id,shift_id,work_date,planned_headcount,status,version,deleted_flag) "
                        + "SELECT ?,?,?,?,?,1,'RELEASED',0,0 WHERE NOT EXISTS (SELECT 1 FROM org_team_schedule WHERE team_id=? AND line_id=? AND shift_id=? AND work_date=?)",
                        "TEST-TS-" + System.nanoTime(), teamId, line, shift, day, teamId, line, shift, day);
            }
        }
    }
}
