package com.neuedu.ems.service.ai;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.neuedu.ems.common.AuthorizationContext;
import com.neuedu.ems.entity.ai.AiCommandExecution;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * AI 写命令服务：把自然语言命令变成"先预览、再确认、后执行"的可审计操作。
 *
 * <p>设计要点：
 * 1. 预览阶段不改任何业务数据，只做真实的前置条件校验并落一条预览凭据；
 * 2. 执行阶段凭 confirmationId 回放，先校验凭据有效性与参数摘要，再调用真实领域服务；
 * 3. 命令记录的状态更新走独立事务，保证业务执行失败时失败原因仍然落库。</p>
 */
public interface AiCommandService {

    /**
     * 预览请求。
     *
     * @param text           用户自然语言命令
     * @param conversationId 会话ID，可空
     * @param source         来源：WORKBENCH/DOCK/API，可空
     * @param context        前端上下文，可空
     */
    record AiCommandPreviewRequest(String text, String conversationId, String source,
                                   Map<String, Object> context) {
    }

    /**
     * 执行请求。
     *
     * @param confirmationId 预览返回的确认凭据
     * @param idempotencyKey 幂等键，可空
     * @param action         动作编码，传了必须与预览一致
     * @param remark         执行备注
     */
    record AiCommandExecuteRequest(String confirmationId, String idempotencyKey, String action,
                                   String remark) {
    }

    /**
     * 批量办理请求：可以给一批确认凭据，也可以给一批「动作 + 单据号」。
     *
     * @param items           逐项办理清单
     * @param confirmationIds 只有确认凭据时的简写形式
     * @param idempotencyKey  整批的幂等键前缀，每项会派生独立的键
     * @param remark          执行备注
     */
    record AiCommandBatchRequest(List<BatchItem> items, List<String> confirmationIds,
                                 String idempotencyKey, String remark) {
    }

    /**
     * 批量办理里的一项：要么给确认凭据，要么给动作 + 单据号。
     *
     * @param confirmationId 预览返回的确认凭据，可空
     * @param action         动作编码，可空
     * @param targetNo       目标单据号，可空
     * @param params         附加参数，可空
     */
    record BatchItem(String confirmationId, String action, String targetNo, Map<String, Object> params) {
    }

    /**
     * 从自然语言里识别写动作编码。
     *
     * @param text 用户输入
     * @return 动作编码；识别不到返回 null
     */
    String parseIntent(String text);

    /**
     * 列出某个动作当前可办理的候选单据。
     *
     * <p>用于「帮我处理待入库单」这类命中多张单据的场景：先把候选全列出来，
     * 并给出每张单据能不能办、卡在哪个前置条件上，用户再决定办哪几张。</p>
     *
     * @param capability    动作编码
     * @param params        附加参数，可空
     * @param authorization 当前登录上下文
     * @return {candidates:[{bizNo,bizType,bizId,label,detail,status,canExecute,blockingReason}], total}
     */
    Map<String, Object> candidates(String capability, Map<String, Object> params,
                                   AuthorizationContext authorization);

    /**
     * 批量执行：逐项独立办理，一项失败不影响其它项。
     *
     * @param request       批量请求
     * @param authorization 当前登录上下文
     * @return {total,succeeded,failed,items:[{bizNo,ok,message,commandId,bizRef}]}
     */
    Map<String, Object> executeBatch(AiCommandBatchRequest request, AuthorizationContext authorization);

    /**
     * 预览：解析命令、校验前置条件、生成确认凭据。
     */
    Map<String, Object> preview(AiCommandPreviewRequest request, AuthorizationContext authorization);

    /**
     * 显式预览：动作与目标单据由结构化计划给出，不再依赖关键词正则解析。
     *
     * <p>供大模型计划器使用：模型只给出「动作 + 单据号 + 参数」，
     * 前置条件、影响范围、权限与落库仍然全部在这里完成。</p>
     *
     * @param action         动作编码，必须属于已注册动作
     * @param targetNo       目标单据号，可空；为空时按文本兜底解析
     * @param params         计划给出的参数，会覆盖默认推导值
     * @param request        原始预览请求，用于记录原文、会话与来源
     * @param authorization  当前登录上下文
     */
    Map<String, Object> previewExplicit(String action, String targetNo, Map<String, Object> params,
                                        AiCommandPreviewRequest request, AuthorizationContext authorization);

    /**
     * 结构化预览：办理参数由前端表单选择后直接给出，不再要求用户把这些值再写进一句话。
     *
     * <p>取值来源变了，校验链路没变：服务端把表单值还原成一句完整的业务描述，
     * 再走与 {@link #previewExplicit} 完全相同的解析、前置校验、权限校验与落库流程，
     * 因此模型虚构参数的口子没有被放开。</p>
     *
     * @param action        动作编码，必须属于已注册动作
     * @param formParams    表单原值；创建生产订单用 productCode/customerCode/orderQuantity/deliveryDate
     * @param request       原始预览请求，用于记录会话与来源
     * @param authorization 当前登录上下文
     */
    Map<String, Object> previewStructured(String action, Map<String, Object> formParams,
                                          AiCommandPreviewRequest request, AuthorizationContext authorization);

    /**
     * 创建生产订单表单的可选值：启用状态的产品与客户各取一页。
     *
     * @param authorization 当前登录上下文
     * @return {products:[{productId,productCode,productName,productModel}],
     *         customers:[{customerId,customerCode,customerName}]}
     */
    Map<String, Object> createOrderOptions(AuthorizationContext authorization);

    /**
     * 排产表单的可选值：不带单号时返回待排产的生产订单清单，带单号时返回该订单可用的产线与时段。
     *
     * @param orderNo       生产订单号，可为空
     * @param authorization 当前登录上下文
     * @return {orders:[...], order:{...}, lines:[{lineId,lineCode,lineName,earliestStartTime,earliestEndTime}]}
     */
    Map<String, Object> scheduleOptions(String orderNo, AuthorizationContext authorization);

    /**
     * 执行：按确认凭据调用真实领域服务。
     */
    Map<String, Object> execute(AiCommandExecuteRequest request, AuthorizationContext authorization);

    /**
     * 命令执行记录分页。
     */
    Page<AiCommandExecution> pageCommands(Integer current, Integer size, String status, String action,
                                          Long userId, String keyword, LocalDateTime beginTime,
                                          LocalDateTime endTime);

    /**
     * 命令详情：命令记录 + 步骤流水。
     */
    Map<String, Object> getCommandDetail(Long commandId);

    /**
     * 监控统计。
     */
    Map<String, Object> stats();

    /**
     * 支持的动作目录。
     */
    List<Map<String, Object>> capabilities();

    /**
     * 多步预览：把一句话解析成有序的多个办理步骤，逐步骤校验并给出整体能否执行。
     *
     * <p>步骤之间允许真实依赖传递：上一步产出的业务单号会成为下一步的目标。
     * 解析阶段还拿不到产出时，该步骤标「等待上一步产出」，不直接判失败。</p>
     *
     * @param text           用户原话
     * @param conversationId 会话ID，可空
     * @param source         来源 WORKBENCH/DOCK/API，可空
     * @param context        附加上下文，可空
     * @return {planId, steps:[{stepNo,action,actionLabel,targetNo,params,impacts,preconditions,
     *         canExecute,blockingReason,status}], overallCanExecute, expiresAt}
     */
    Map<String, Object> previewSequence(String text, String conversationId, String source,
                                        Map<String, Object> context, AuthorizationContext authorization);

    /**
     * 只有一句话确实包含两步以上业务时才返回多步预览，否则返回 null 交给单步链路。
     *
     * <p>这样「下达工单 WO-xxx」这类单步命令不会被误包装成序列，既有单步体验完全不变。</p>
     */
    Map<String, Object> previewSequenceIfMulti(String text, String conversationId, String source,
                                               Map<String, Object> context,
                                               AuthorizationContext authorization);

    /**
     * 按序列预览结果显式指定步骤，供大模型计划器直接下发多步计划时使用。
     *
     * @param steps          每步的 {action,targetNo,params}
     * @param text           用户原话
     * @param conversationId 会话ID，可空
     * @param source         来源，可空
     * @param authorization  当前登录上下文
     * @return 与 previewSequence 相同的预览体
     */
    Map<String, Object> previewSequenceExplicit(List<SequenceStepInput> steps, String text,
                                                String conversationId, String source,
                                                AuthorizationContext authorization);

    /**
     * 分步执行：按步骤顺序执行，某步失败整体置 PARTIAL_FAILED，已提交业务不回滚。
     *
     * @param request       执行入参
     * @param authorization 当前登录上下文
     * @return {planId, status, totalSteps, executedSteps, steps:[{stepNo,action,targetNo,status,bizNo,
     *         bizId,commandId,message,bizRef}], elapsedMs, executedAt}
     */
    Map<String, Object> executeSequence(AiCommandSequenceExecuteRequest request,
                                        AuthorizationContext authorization);

    /**
     * 查序列各步状态，供前端刷新后恢复卡片。
     *
     * @param planId 序列ID（预览返回的 planId）
     * @return {planId, status, totalSteps, executedSteps, overallCanExecute, steps:[...], expiresAt}
     */
    Map<String, Object> getSequence(String planId);

    /**
     * 多步预览里的一个步骤。
     *
     * @param action   动作编码
     * @param targetNo 目标单据号，可空（由上一步产出时为空）
     * @param params   附加参数，可空
     */
    record SequenceStepInput(String action, String targetNo, Map<String, Object> params) {
    }

    /**
     * 多步执行入参。
     *
     * @param planId         预览返回的序列ID
     * @param idempotencyKey 整序列幂等键
     * @param startStepNo    从第几步开始执行，空或 1 表示从头
     * @param remark         执行备注
     */
    record AiCommandSequenceExecuteRequest(String planId, String idempotencyKey, Integer startStepNo,
                                           String remark) {
    }
}
