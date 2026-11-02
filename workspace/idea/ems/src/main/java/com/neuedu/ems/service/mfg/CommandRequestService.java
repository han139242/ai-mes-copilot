package com.neuedu.ems.service.mfg;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.entity.mfg.MfgCommandRequest;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.mfg.MfgCommandRequestMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 生产与仓储命令的幂等登记。
 *
 * <p>口径与生成工单一致：同一命令、同一请求键、同一请求摘要时重放原结果；
 * 同一请求键但摘要变化必须拒绝，不能静默复用。请求键为空表示调用方不要幂等，
 * 此时直接执行，不落记录。</p>
 */
@Service
public class CommandRequestService {

    @Autowired
    private MfgCommandRequestMapper requests;

    /**
     * 查询已登记的命令结果。
     *
     * @return 已登记的结果主键；未登记返回 {@code null}
     */
    public Long replayed(String command, String requestKey, String digest) {
        String key = trimToNull(requestKey);
        if (key == null) return null;
        MfgCommandRequest existing = requests.selectOne(new QueryWrapper<MfgCommandRequest>()
                .eq("command", command).eq("request_key", key).eq("deleted_flag", 0).last("FOR UPDATE"));
        if (existing == null) return null;
        if (!Objects.equals(digest, existing.getRequestDigest())) {
            throw business("同一命令请求键的请求参数已变化，不能复用原结果");
        }
        return existing.getResultId();
    }

    /**
     * 登记已完成的命令结果。调用方必须已在同一事务内完成全部业务写入。
     */
    public void record(String command, String requestKey, String aggregateType, Long aggregateId,
                       String digest, Long resultId, Long operatorId, String remark, LocalDateTime now) {
        String key = trimToNull(requestKey);
        if (key == null) return;
        MfgCommandRequest record = new MfgCommandRequest();
        record.setRequestKey(key);
        record.setCommand(command);
        record.setAggregateType(aggregateType);
        record.setAggregateId(aggregateId);
        record.setRequestDigest(digest);
        record.setResultId(resultId);
        record.setOperatorId(operatorId);
        record.setStatus(MfgCommandRequest.COMPLETED);
        record.setRemark(trimToNull(remark));
        record.setVersion(0);
        record.setCreatedBy(operatorId);
        record.setCreatedTime(now);
        record.setUpdatedBy(operatorId);
        record.setUpdatedTime(now);
        record.setDeletedFlag(0);
        if (requests.insert(record) != 1) {
            throw business("命令请求幂等记录保存失败");
        }
    }

    /** 计算请求摘要；调用方负责按固定顺序拼接参数，避免 Map 迭代顺序影响摘要。 */
    public String digest(String content) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(
                    Objects.toString(content, "").getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String trimToNull(String value) {
        if (!StringUtils.hasText(value)) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static BusinessException business(String message) {
        return new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), message);
    }
}
