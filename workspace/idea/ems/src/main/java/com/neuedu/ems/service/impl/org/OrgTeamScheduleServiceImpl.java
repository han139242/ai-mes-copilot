package com.neuedu.ems.service.impl.org;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.neuedu.ems.common.BusinessStatus;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.entity.factory.FactoryLine;
import com.neuedu.ems.entity.org.OrgShift;
import com.neuedu.ems.entity.org.OrgTeam;
import com.neuedu.ems.entity.org.OrgTeamSchedule;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.factory.FactoryLineMapper;
import com.neuedu.ems.mapper.org.OrgShiftMapper;
import com.neuedu.ems.mapper.org.OrgTeamMapper;
import com.neuedu.ems.mapper.org.OrgTeamScheduleMapper;
import com.neuedu.ems.service.org.OrgTeamScheduleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * 班组日期排班 Service 实现类。
 */
@Service
public class OrgTeamScheduleServiceImpl extends ServiceImpl<OrgTeamScheduleMapper, OrgTeamSchedule>
        implements OrgTeamScheduleService {

    private static final String DRAFT_STATUS = BusinessStatus.Document.DRAFT;
    private static final String RELEASED_STATUS = BusinessStatus.Document.RELEASED;
    private static final DateTimeFormatter SCHEDULE_NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Autowired
    private OrgTeamMapper orgTeamMapper;

    @Autowired
    private OrgShiftMapper orgShiftMapper;

    @Autowired
    private FactoryLineMapper factoryLineMapper;

    /**
     * 分页查询班组排班，按排班日期倒序。
     * @param current 当前页码
     * @param size 每页记录数
     * @param workDate 排班日期，可选
     * @param lineId 产线ID，可选
     * @param teamId 班组ID，可选
     * @return 排班分页结果
     */
    @Override
    public Page<OrgTeamSchedule> pageSchedules(Integer current, Integer size, LocalDate workDate,
                                               Long lineId, Long teamId) {
        Page<OrgTeamSchedule> page = new Page<>(current == null ? 1 : current, size == null ? 10 : size);
        return baseMapper.selectPage(page, buildScheduleQuery(workDate, lineId, teamId));
    }

    /**
     * 根据ID查询未删除的排班。
     * @param scheduleId 排班ID
     * @return 排班实体
     */
    @Override
    public OrgTeamSchedule getScheduleById(Long scheduleId) {
        if (scheduleId == null) {
            return null;
        }
        LambdaQueryWrapper<OrgTeamSchedule> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(OrgTeamSchedule::getScheduleId, scheduleId)
                .eq(OrgTeamSchedule::getDeletedFlag, 0);
        return baseMapper.selectOne(queryWrapper);
    }

    /**
     * 新增排班，编号由后端生成，初始状态为 DRAFT。
     * @param schedule 排班实体
     * @return 是否成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean saveSchedule(OrgTeamSchedule schedule) {
        if (schedule == null) {
            throw business(ResultCode.PARAM_ERROR, "排班信息不能为空");
        }
        requireScheduleFields(schedule);
        assertScheduleUnique(schedule.getTeamId(), schedule.getLineId(), schedule.getShiftId(),
                schedule.getWorkDate(), null);
        schedule.setScheduleId(null);
        schedule.setScheduleNo(buildScheduleNo(schedule));
        schedule.setStatus(DRAFT_STATUS);
        schedule.setVersion(0);
        schedule.setDeletedFlag(0);
        if (schedule.getPlannedHeadcount() == null) {
            schedule.setPlannedHeadcount(1);
        }
        return baseMapper.insert(schedule) > 0;
    }

    /**
     * 修改排班，仅 DRAFT 状态允许修改。
     * @param schedule 排班实体
     * @return 是否成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateSchedule(OrgTeamSchedule schedule) {
        if (schedule == null || schedule.getScheduleId() == null) {
            throw business(ResultCode.PARAM_ERROR, "排班ID不能为空");
        }
        OrgTeamSchedule existing = getScheduleById(schedule.getScheduleId());
        if (existing == null) {
            throw business(ResultCode.NOT_FOUND, "排班不存在或已删除");
        }
        if (!DRAFT_STATUS.equals(existing.getStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "只有草稿状态的排班可以修改，已生效排班请勿直接变更");
        }
        Long teamId = schedule.getTeamId() == null ? existing.getTeamId() : schedule.getTeamId();
        Long lineId = schedule.getLineId() == null ? existing.getLineId() : schedule.getLineId();
        Long shiftId = schedule.getShiftId() == null ? existing.getShiftId() : schedule.getShiftId();
        LocalDate workDate = schedule.getWorkDate() == null ? existing.getWorkDate() : schedule.getWorkDate();
        assertScheduleUnique(teamId, lineId, shiftId, workDate, existing.getScheduleId());
        // 状态、删除标记只能由专用接口变更；排班日期或班组班次变化时同步重算编号，保持编号与业务口径一致。
        boolean scheduleNoChanged = !Objects.equals(existing.getTeamId(), teamId)
                || !Objects.equals(existing.getShiftId(), shiftId)
                || !Objects.equals(existing.getWorkDate(), workDate);
        if (scheduleNoChanged) {
            schedule.setScheduleNo("TS-" + workDate.format(SCHEDULE_NO_DATE) + "-" + teamId + "-" + shiftId);
        } else {
            schedule.setScheduleNo(null);
        }
        schedule.setStatus(null);
        schedule.setDeletedFlag(null);
        if (schedule.getVersion() == null) {
            schedule.setVersion(existing.getVersion());
        }
        return baseMapper.updateById(schedule) > 0;
    }

    /**
     * 生效排班，DRAFT 变更为 RELEASED。
     * @param scheduleId 排班ID
     * @return 是否成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean releaseSchedule(Long scheduleId) {
        OrgTeamSchedule existing = getScheduleById(scheduleId);
        if (existing == null) {
            throw business(ResultCode.NOT_FOUND, "排班不存在或已删除");
        }
        if (!DRAFT_STATUS.equals(existing.getStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "只有草稿状态的排班可以生效");
        }
        assertTeamHasActiveMembers(existing.getTeamId());
        assertLineRunning(existing.getLineId());
        assertShiftEnabled(existing.getShiftId());
        OrgTeamSchedule update = new OrgTeamSchedule();
        update.setScheduleId(existing.getScheduleId());
        update.setVersion(existing.getVersion());
        update.setStatus(RELEASED_STATUS);
        if (baseMapper.updateById(update) == 0) {
            throw business(ResultCode.DATA_CONFLICT, "排班已被其他用户修改，请刷新后重试");
        }
        return true;
    }

    /**
     * 根据ID软删除排班，仅 DRAFT 状态允许删除。
     * @param scheduleId 排班ID
     * @return 是否成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeScheduleById(Long scheduleId) {
        OrgTeamSchedule existing = getScheduleById(scheduleId);
        if (existing == null) {
            throw business(ResultCode.NOT_FOUND, "排班不存在或已删除");
        }
        if (!DRAFT_STATUS.equals(existing.getStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "只有草稿状态的排班可以删除，已生效排班不能删除");
        }
        OrgTeamSchedule update = new OrgTeamSchedule();
        update.setScheduleId(existing.getScheduleId());
        update.setVersion(existing.getVersion());
        update.setDeletedFlag(1);
        if (baseMapper.updateById(update) == 0) {
            throw business(ResultCode.DATA_CONFLICT, "排班已被其他用户修改，请刷新后重试");
        }
        return true;
    }

    /**
     * 查询指定日期所在周（7天）的排班列表，供前端日历视图使用。
     * @param startDate 周起始日期
     * @return 该周排班列表
     */
    @Override
    public List<OrgTeamSchedule> listWeekSchedules(LocalDate startDate) {
        if (startDate == null) {
            throw business(ResultCode.PARAM_ERROR, "周起始日期不能为空");
        }
        LambdaQueryWrapper<OrgTeamSchedule> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(OrgTeamSchedule::getDeletedFlag, 0)
                .ge(OrgTeamSchedule::getWorkDate, startDate)
                .lt(OrgTeamSchedule::getWorkDate, startDate.plusDays(7))
                .orderByAsc(OrgTeamSchedule::getWorkDate)
                .orderByAsc(OrgTeamSchedule::getShiftId);
        return baseMapper.selectList(queryWrapper);
    }

    /**
     * 构建排班查询条件，默认过滤已删除记录并按排班日期倒序。
     * @param workDate 排班日期，可选
     * @param lineId 产线ID，可选
     * @param teamId 班组ID，可选
     * @return 查询条件包装器
     */
    private LambdaQueryWrapper<OrgTeamSchedule> buildScheduleQuery(LocalDate workDate, Long lineId, Long teamId) {
        LambdaQueryWrapper<OrgTeamSchedule> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(OrgTeamSchedule::getDeletedFlag, 0)
                .eq(workDate != null, OrgTeamSchedule::getWorkDate, workDate)
                .eq(lineId != null, OrgTeamSchedule::getLineId, lineId)
                .eq(teamId != null, OrgTeamSchedule::getTeamId, teamId)
                .orderByDesc(OrgTeamSchedule::getWorkDate)
                .orderByDesc(OrgTeamSchedule::getScheduleId);
        return queryWrapper;
    }

    /**
     * 校验排班必填字段。
     * @param schedule 排班实体
     */
    private void requireScheduleFields(OrgTeamSchedule schedule) {
        if (schedule.getTeamId() == null) {
            throw business(ResultCode.PARAM_ERROR, "班组不能为空");
        }
        if (schedule.getLineId() == null) {
            throw business(ResultCode.PARAM_ERROR, "产线不能为空");
        }
        if (schedule.getShiftId() == null) {
            throw business(ResultCode.PARAM_ERROR, "班次不能为空");
        }
        if (schedule.getWorkDate() == null) {
            throw business(ResultCode.PARAM_ERROR, "排班日期不能为空");
        }
        if (schedule.getPlannedHeadcount() != null && schedule.getPlannedHeadcount() <= 0) {
            throw business(ResultCode.PARAM_ERROR, "计划出勤人数必须大于 0");
        }
    }

    /**
     * 校验同一班组、产线、班次、日期是否重复排班。
     * @param teamId 班组ID
     * @param lineId 产线ID
     * @param shiftId 班次ID
     * @param workDate 排班日期
     * @param excludeScheduleId 需要排除的排班ID，修改时传入自身
     */
    private void assertScheduleUnique(Long teamId, Long lineId, Long shiftId, LocalDate workDate,
                                      Long excludeScheduleId) {
        LambdaQueryWrapper<OrgTeamSchedule> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(OrgTeamSchedule::getDeletedFlag, 0)
                .eq(OrgTeamSchedule::getTeamId, teamId)
                .eq(OrgTeamSchedule::getLineId, lineId)
                .eq(OrgTeamSchedule::getShiftId, shiftId)
                .eq(OrgTeamSchedule::getWorkDate, workDate)
                .ne(excludeScheduleId != null, OrgTeamSchedule::getScheduleId, excludeScheduleId);
        if (baseMapper.selectCount(queryWrapper) > 0) {
            throw business(ResultCode.BUSINESS_ERROR,
                    "该班组在 " + workDate + " 的同一产线同一班次已有排班，请勿重复创建");
        }
    }

    /**
     * 生成排班编号：TS-<yyyyMMdd>-<teamId>-<shiftId>。
     * @param schedule 排班实体
     * @return 排班编号
     */
    private String buildScheduleNo(OrgTeamSchedule schedule) {
        return "TS-" + schedule.getWorkDate().format(SCHEDULE_NO_DATE)
                + "-" + schedule.getTeamId() + "-" + schedule.getShiftId();
    }

    /**
     * 校验班组存在且仍有未离职成员。
     * @param teamId 班组ID
     */
    private void assertTeamHasActiveMembers(Long teamId) {
        OrgTeam team = orgTeamMapper.selectOne(new LambdaQueryWrapper<OrgTeam>()
                .eq(OrgTeam::getTeamId, teamId)
                .eq(OrgTeam::getDeletedFlag, 0));
        if (team == null) {
            throw business(ResultCode.BUSINESS_ERROR, "排班关联的班组不存在或已删除，不能生效");
        }
        if (baseMapper.countActiveMembers(teamId) <= 0) {
            throw business(ResultCode.BUSINESS_ERROR,
                    "班组 " + displayTeam(team) + " 没有在职成员，不能生效排班");
        }
    }

    /**
     * 校验产线存在且处于运行状态。
     * @param lineId 产线ID
     */
    private void assertLineRunning(Long lineId) {
        FactoryLine line = factoryLineMapper.selectOne(new LambdaQueryWrapper<FactoryLine>()
                .eq(FactoryLine::getLineId, lineId)
                .eq(FactoryLine::getDeletedFlag, 0));
        if (line == null) {
            throw business(ResultCode.BUSINESS_ERROR, "排班关联的产线不存在或已删除，不能生效");
        }
        if (!BusinessStatus.Factory.RUNNING.equals(line.getStatus())) {
            throw business(ResultCode.BUSINESS_ERROR,
                    "产线 " + displayLine(line) + " 当前不是运行状态，不能生效排班");
        }
    }

    /**
     * 校验班次存在且启用。
     * 说明：当前 org_shift 表未提供 status 列，检测不到该列时只校验班次存在；
     * 数据库补上 status 列后本方法会自动按 ENABLE 校验。
     * @param shiftId 班次ID
     */
    private void assertShiftEnabled(Long shiftId) {
        OrgShift shift = orgShiftMapper.selectOne(new LambdaQueryWrapper<OrgShift>()
                .eq(OrgShift::getShiftId, shiftId)
                .eq(OrgShift::getDeletedFlag, 0));
        if (shift == null) {
            throw business(ResultCode.BUSINESS_ERROR, "排班关联的班次不存在或已删除，不能生效");
        }
        if (baseMapper.countShiftStatusColumn() > 0) {
            String status = baseMapper.selectShiftStatus(shiftId);
            if (!BusinessStatus.Common.ENABLE.equals(status)) {
                throw business(ResultCode.BUSINESS_ERROR,
                        "班次 " + displayShift(shift) + " 未启用，不能生效排班");
            }
        }
    }

    private String displayTeam(OrgTeam team) {
        return StringUtils.hasText(team.getTeamName()) ? team.getTeamName() : String.valueOf(team.getTeamId());
    }

    private String displayLine(FactoryLine line) {
        return StringUtils.hasText(line.getLineName()) ? line.getLineName() : String.valueOf(line.getLineId());
    }

    private String displayShift(OrgShift shift) {
        return StringUtils.hasText(shift.getShiftName()) ? shift.getShiftName() : String.valueOf(shift.getShiftId());
    }

    private BusinessException business(ResultCode code, String message) {
        return new BusinessException(code.getCode(), message);
    }
}
