package com.neuedu.ems.mapper.org;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.neuedu.ems.entity.org.OrgTeamSchedule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 班组日期排班 Mapper 接口。
 */
@Mapper
public interface OrgTeamScheduleMapper extends BaseMapper<OrgTeamSchedule> {

    /**
     * 统计班组下仍在职的成员数量。
     * 在职口径：班组成员未离职且有效，关联员工状态为 ENABLE。
     * @param teamId 班组ID
     * @return 在职成员数量
     */
    @Select("""
            SELECT COUNT(1)
            FROM org_team_member member
            JOIN org_employee employee
              ON employee.employee_id = member.employee_id AND employee.deleted_flag = 0
            WHERE member.team_id = #{teamId}
              AND member.deleted_flag = 0
              AND member.leave_date IS NULL
              AND employee.status = 'ENABLE'
            """)
    long countActiveMembers(@Param("teamId") Long teamId);

    /**
     * 检测 org_shift 是否已具备 status 列。
     * 历史库没有该列，补列后自动按 ENABLE 校验，避免排班生效接口在旧库上直接报 SQL 错误。
     * @return 命中列数量
     */
    @Select("""
            SELECT COUNT(1)
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = 'org_shift'
              AND column_name = 'status'
            """)
    int countShiftStatusColumn();

    /**
     * 查询班次启用状态，仅在 org_shift 已具备 status 列时调用。
     * @param shiftId 班次ID
     * @return 班次状态编码
     */
    @Select("SELECT status FROM org_shift WHERE shift_id = #{shiftId} AND deleted_flag = 0")
    String selectShiftStatus(@Param("shiftId") Long shiftId);
}
