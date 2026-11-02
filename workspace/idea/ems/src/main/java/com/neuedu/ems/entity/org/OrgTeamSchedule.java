package com.neuedu.ems.entity.org;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * org_team_schedule 班组日期排班表。
 * 记录某班组在某日某产线某班次的出勤安排，与产线周历规则相互独立。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("org_team_schedule")
public class OrgTeamSchedule {

    @TableId(value = "schedule_id", type = IdType.AUTO)
    private Long scheduleId;
    /** 排班编号，后端按 TS-<yyyyMMdd>-<teamId>-<shiftId> 生成。 */
    private String scheduleNo;
    private Long teamId;
    private Long lineId;
    private Long shiftId;
    private LocalDate workDate;
    private Integer plannedHeadcount;
    /** DRAFT:草稿，RELEASED:已生效，CLOSED:已关闭。 */
    private String status;
    private String remark;
    @Version
    private Integer version;
    private Long createdBy;
    private LocalDateTime createdTime;
    private Long updatedBy;
    private LocalDateTime updatedTime;
    private Integer deletedFlag;
}
