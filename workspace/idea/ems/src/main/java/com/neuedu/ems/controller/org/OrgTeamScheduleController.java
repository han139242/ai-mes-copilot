package com.neuedu.ems.controller.org;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.common.ResultData;
import com.neuedu.ems.entity.org.OrgTeamSchedule;
import com.neuedu.ems.service.org.OrgTeamScheduleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 班组日期排班控制器。
 */
@RestController
@RequestMapping("/api/org/team-schedule")
public class OrgTeamScheduleController {

    @Autowired
    private OrgTeamScheduleService orgTeamScheduleService;

    /**
     * 分页查询班组排班，按排班日期倒序。
     * @param current 当前页码，默认1
     * @param size 每页记录数，默认10
     * @param workDate 排班日期，可选，格式 yyyy-MM-dd
     * @param lineId 产线ID，可选
     * @param teamId 班组ID，可选
     * @return 排班分页结果
     */
    @GetMapping("/page")
    public ResultData page(@RequestParam(defaultValue = "1") Integer current,
                           @RequestParam(defaultValue = "10") Integer size,
                           @RequestParam(required = false)
                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate workDate,
                           @RequestParam(required = false) Long lineId,
                           @RequestParam(required = false) Long teamId) {
        Page<OrgTeamSchedule> result = orgTeamScheduleService.pageSchedules(current, size, workDate, lineId, teamId);
        return ResultData.success(ResultCode.SUCCESS, result);
    }

    /**
     * 查询指定日期所在周（7天）的排班列表，供前端日历视图使用。
     * @param startDate 周起始日期，格式 yyyy-MM-dd；不传时默认本周一
     * @return 该周排班列表
     */
    @GetMapping("/week")
    public ResultData week(@RequestParam(value = "startDate", required = false)
                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate) {
        LocalDate resolved = startDate == null ? LocalDate.now().with(java.time.DayOfWeek.MONDAY) : startDate;
        List<OrgTeamSchedule> schedules = orgTeamScheduleService.listWeekSchedules(resolved);
        return ResultData.success(ResultCode.SUCCESS, schedules);
    }

    /**
     * 根据排班ID查询详情。
     * @param scheduleId 排班ID
     * @return 排班实体
     */
    @GetMapping("/{scheduleId}")
    public ResultData detail(@PathVariable("scheduleId") Long scheduleId) {
        OrgTeamSchedule schedule = orgTeamScheduleService.getScheduleById(scheduleId);
        return ResultData.success(ResultCode.SUCCESS, schedule);
    }

    /**
     * 新增排班，编号由后端生成，初始状态为 DRAFT。
     * @param schedule 排班实体，JSON格式
     * @return 操作结果
     */
    @PostMapping
    public ResultData create(@RequestBody OrgTeamSchedule schedule) {
        return orgTeamScheduleService.saveSchedule(schedule)
                ? ResultData.success(ResultCode.SUCCESS)
                : ResultData.fail(ResultCode.FAILED);
    }

    /**
     * 修改排班，仅 DRAFT 状态允许修改。
     * @param schedule 排班实体，JSON格式，必须包含scheduleId
     * @return 操作结果
     */
    @PutMapping
    public ResultData update(@RequestBody OrgTeamSchedule schedule) {
        return orgTeamScheduleService.updateSchedule(schedule)
                ? ResultData.success(ResultCode.SUCCESS)
                : ResultData.fail(ResultCode.FAILED);
    }

    /**
     * 生效排班，DRAFT 变更为 RELEASED。
     * @param scheduleId 排班ID
     * @return 操作结果
     */
    @PutMapping("/{scheduleId}/release")
    public ResultData release(@PathVariable("scheduleId") Long scheduleId) {
        return orgTeamScheduleService.releaseSchedule(scheduleId)
                ? ResultData.success(ResultCode.SUCCESS)
                : ResultData.fail(ResultCode.FAILED);
    }

    /**
     * 删除排班，统一做软删除，仅 DRAFT 状态允许删除。
     * @param scheduleId 排班ID
     * @return 操作结果
     */
    @DeleteMapping("/{scheduleId}")
    public ResultData delete(@PathVariable("scheduleId") Long scheduleId) {
        return orgTeamScheduleService.removeScheduleById(scheduleId)
                ? ResultData.success(ResultCode.SUCCESS)
                : ResultData.fail(ResultCode.FAILED);
    }
}
