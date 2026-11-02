package com.neuedu.ems.service.ai;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.neuedu.ems.entity.ai.AiAnomalyScan;
import com.neuedu.ems.entity.mfg.MfgException;

import java.util.List;
import java.util.Map;

/**
 * 主动异常闭环服务：定时扫描业务数据里的异常信号，自动登记异常台账、通知责任人，
 * 条件恢复后自动验证并关闭，全过程留扫描记录。
 */
public interface AiAnomalyService {

    /** 异常信号类型：检验单超期未提交。 */
    String TYPE_INSPECTION_OVERDUE = "INSPECTION_OVERDUE";

    /** 异常信号类型：工单下达后长时间未开工。 */
    String TYPE_WORK_ORDER_IDLE = "WORK_ORDER_IDLE";

    /** 异常信号类型：设备故障或维修中未处理。 */
    String TYPE_EQUIPMENT_FAULT = "EQUIPMENT_FAULT";

    /** 自动登记异常的编号前缀，用于区分人工上报与系统识别。 */
    String AUTO_EXCEPTION_PREFIX = "EXC-AUTO-";

    /** 异常台账状态：待处理。 */
    String STATUS_SUBMITTED = "SUBMITTED";

    /** 异常台账状态：已关闭。 */
    String STATUS_CLOSED = "CLOSED";

    /**
     * 监控概览：本类异常各状态条数、最近一次扫描结论。
     */
    Map<String, Object> overview();

    /**
     * 手工触发一轮扫描。
     * @return 本轮扫描结果
     */
    Map<String, Object> scan(Long operatorId);

    /**
     * 定时扫描：按时间窗幂等，同一窗口重复触发不会重复登记。
     * @return 本轮新登记的异常条数
     */
    int scanScheduled();

    /**
     * 扫描记录分页。
     */
    Page<AiAnomalyScan> pageScans(Integer current, Integer size, String status);

    /**
     * 自动识别的异常台账分页。
     * @param status 异常状态，空表示全部
     * @param exceptionType 异常类型，空表示全部自动类型
     */
    Page<MfgException> pageAnomalies(Integer current, Integer size, String status, String exceptionType);

    /**
     * 人工确认关闭一条自动异常（业务已线下处理完毕时使用）。
     */
    boolean closeAnomaly(Long exceptionId, String handleResult, Long operatorId);

    /**
     * 当前各类信号的阈值配置，供界面展示。
     */
    List<Map<String, Object>> thresholds();
}
