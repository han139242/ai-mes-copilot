package com.neuedu.ems.service.ai;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.neuedu.ems.entity.ai.AiAutomationRule;
import com.neuedu.ems.entity.ai.AiAutomationRun;
import com.neuedu.ems.entity.ai.AiInventoryRisk;

import java.util.List;
import java.util.Map;

/**
 * AI 自动化规则 Service 接口。
 * 规则描述"监控什么、阈值多少、命中后做什么"，执行时按确定性领域服务落地，不依赖大模型。
 */
public interface AiAutomationService {

    /** 概览指标。 */
    Map<String, Object> overview();

    /** 规则分页。 */
    Page<AiAutomationRule> pageRules(Integer current, Integer size, String status, String keyword);

    /** 规则详情。 */
    AiAutomationRule getRule(Long ruleId);

    /** 新建规则，返回规则ID。 */
    Long saveRule(AiAutomationRule rule);

    /** 修改规则。 */
    boolean updateRule(AiAutomationRule rule);

    /** 软删除规则。 */
    boolean removeRule(Long ruleId);

    /** 变更规则状态。 */
    boolean changeStatus(Long ruleId, String status);

    /** 预览：只读试算，不写任何业务数据。 */
    Map<String, Object> preview(Long ruleId);

    /** 手动执行一次规则。 */
    Map<String, Object> run(Long ruleId);

    /** 定时执行入口，返回本轮执行了多少条规则。 */
    int runScheduled();

    /**
     * 某个物料的可用库存刚变化。只跑覆盖该物料、且已启用的规则，不等下一次定时窗口。
     * @param materialId 物料ID
     * @return 实际执行的规则数
     */
    int runOnInventoryChange(Long materialId);

    /** 执行记录分页。 */
    Page<AiAutomationRun> pageRuns(Integer current, Integer size, Long ruleId, String status);

    /** 执行记录详情，含步骤流水。 */
    Map<String, Object> getRunDetail(Long runId);

    /** 风险清单分页。 */
    Page<AiInventoryRisk> pageRisks(Integer current, Integer size, String status);

    /** 规则的物料范围预览，供前端选择物料时使用。 */
    List<Map<String, Object>> ruleMaterials(Long ruleId);

    /**
     * 只读列出当前低于安全库存的物料，口径与规则执行时的缺口计算完全同源：
     * 可用量取同一仓库类型下的可用库存合计，阈值取规则默认阈值或物料上单独配置的安全库存。
     *
     * <p>不写风险台账、不生成补料建议、不推进任何单据状态。启用中的规则优先；
     * 某个仓库类型下没有启用规则时退回该类型的未停用规则，并在结果里标明规则状态，
     * 避免「规则被暂停」被误解成「没有缺料」。</p>
     *
     * @param warehouseType   仓库类型编码，可空表示覆盖全部已配置规则的类型
     * @param materialKeyword 物料名称或编码关键词，可空表示不限物料
     * @return 缺口明细，按仓库类型与物料编码排序
     */
    List<Map<String, Object>> lowStockMaterials(String warehouseType, String materialKeyword);
}
