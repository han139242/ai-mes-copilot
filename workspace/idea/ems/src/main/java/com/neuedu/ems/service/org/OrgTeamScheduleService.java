package com.neuedu.ems.service.org;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.neuedu.ems.entity.org.OrgTeamSchedule;

import java.time.LocalDate;
import java.util.List;

/**
 * 班组日期排班 Service 接口。
 */
public interface OrgTeamScheduleService extends IService<OrgTeamSchedule> {

    /**
     * 分页查询班组排班，按排班日期倒序。
     * @param current 当前页码
     * @param size 每页记录数
     * @param workDate 排班日期，可选
     * @param lineId 产线ID，可选
     * @param teamId 班组ID，可选
     * @return 排班分页结果
     */
    Page<OrgTeamSchedule> pageSchedules(Integer current, Integer size, LocalDate workDate, Long lineId, Long teamId);

    /**
     * 根据ID查询未删除的排班。
     * @param scheduleId 排班ID
     * @return 排班实体
     */
    OrgTeamSchedule getScheduleById(Long scheduleId);

    /**
     * 新增排班，编号由后端生成，初始状态为 DRAFT。
     * @param schedule 排班实体
     * @return 是否成功
     */
    boolean saveSchedule(OrgTeamSchedule schedule);

    /**
     * 修改排班，仅 DRAFT 状态允许修改。
     * @param schedule 排班实体
     * @return 是否成功
     */
    boolean updateSchedule(OrgTeamSchedule schedule);

    /**
     * 生效排班，DRAFT 变更为 RELEASED。
     * @param scheduleId 排班ID
     * @return 是否成功
     */
    boolean releaseSchedule(Long scheduleId);

    /**
     * 根据ID软删除排班，仅 DRAFT 状态允许删除。
     * @param scheduleId 排班ID
     * @return 是否成功
     */
    boolean removeScheduleById(Long scheduleId);

    /**
     * 查询指定日期所在周（7天）的排班列表，供前端日历视图使用。
     * @param startDate 周起始日期
     * @return 该周排班列表
     */
    List<OrgTeamSchedule> listWeekSchedules(LocalDate startDate);
}
