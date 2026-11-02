package com.neuedu.ems.controller.ai;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.common.ResultData;
import com.neuedu.ems.entity.ai.AiAnomalyScan;
import com.neuedu.ems.entity.mfg.MfgException;
import com.neuedu.ems.service.ai.AiAnomalyService;
import com.neuedu.ems.service.ai.AiQueryAccessPolicy;
import com.neuedu.ems.common.AuthorizationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 主动异常闭环控制器：概览、手工扫描、扫描记录、异常台账、人工关闭。
 *
 * <p>路径统一在 /api/ai/** 之下，权限按 ai:assistant:use 解析。</p>
 */
@RestController
@RequestMapping("/api/ai/anomaly")
public class AiAnomalyController {

    private final AiAnomalyService anomalyService;

    public AiAnomalyController(AiAnomalyService anomalyService) {
        this.anomalyService = anomalyService;
    }

    /** 监控概览：未关闭条数、分类条数、最近一次扫描、阈值配置。 */
    @GetMapping("/overview")
    public ResultData overview(@RequestAttribute("authorizationContext") AuthorizationContext authorization) {
        AiQueryAccessPolicy.requireIntent(authorization, "general");
        return ResultData.success(ResultCode.SUCCESS, anomalyService.overview());
    }

    /** 手工触发一轮扫描。 */
    @PostMapping("/scan")
    public ResultData scan(@RequestAttribute("userId") Long userId,
                           @RequestAttribute("authorizationContext") AuthorizationContext authorization) {
        AiQueryAccessPolicy.requireIntent(authorization, "command_preview");
        return ResultData.success(ResultCode.SUCCESS, anomalyService.scan(userId));
    }

    /** 扫描记录分页。 */
    @GetMapping("/scans")
    public ResultData scans(@RequestParam(defaultValue = "1") Integer current,
                            @RequestParam(defaultValue = "10") Integer size,
                            @RequestParam(required = false) String status,
                            @RequestAttribute("authorizationContext") AuthorizationContext authorization) {
        AiQueryAccessPolicy.requireIntent(authorization, "general");
        Page<AiAnomalyScan> page = anomalyService.pageScans(current, size, status);
        return ResultData.success(ResultCode.SUCCESS, page);
    }

    /** 自动识别异常台账分页。 */
    @GetMapping("/anomalies")
    public ResultData anomalies(@RequestParam(defaultValue = "1") Integer current,
                                @RequestParam(defaultValue = "10") Integer size,
                                @RequestParam(required = false) String status,
                                @RequestParam(required = false) String exceptionType,
                                @RequestAttribute("authorizationContext") AuthorizationContext authorization) {
        AiQueryAccessPolicy.requireIntent(authorization, "general");
        Page<MfgException> page = anomalyService.pageAnomalies(current, size, status, exceptionType);
        return ResultData.success(ResultCode.SUCCESS, page);
    }

    /** 人工确认关闭一条自动异常。 */
    @PutMapping("/anomalies/{exceptionId}/close")
    public ResultData close(@PathVariable Long exceptionId,
                            @RequestBody(required = false) CloseRequest request,
                            @RequestAttribute("userId") Long userId,
                            @RequestAttribute("authorizationContext") AuthorizationContext authorization) {
        AiQueryAccessPolicy.requireIntent(authorization, "command_preview");
        String handleResult = request == null ? null : request.getHandleResult();
        return ResultData.success(ResultCode.SUCCESS,
                Map.of("closed", anomalyService.closeAnomaly(exceptionId, handleResult, userId)));
    }

    /** 当前各类信号阈值。 */
    @GetMapping("/thresholds")
    public ResultData thresholds(@RequestAttribute("authorizationContext") AuthorizationContext authorization) {
        AiQueryAccessPolicy.requireIntent(authorization, "general");
        return ResultData.success(ResultCode.SUCCESS, anomalyService.thresholds());
    }

    /** 人工关闭请求体。 */
    public static class CloseRequest {
        private String handleResult;

        public String getHandleResult() {
            return handleResult;
        }

        public void setHandleResult(String handleResult) {
            this.handleResult = handleResult;
        }
    }
}
