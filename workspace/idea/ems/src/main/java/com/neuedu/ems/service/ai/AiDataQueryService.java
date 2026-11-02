package com.neuedu.ems.service.ai;

import com.neuedu.ems.entity.ai.AiQueryResult;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * AI 结构化数据查询服务。
 * 所有方法均直接查询数据库真实数据，不使用大模型计算，保证与人工查询逐字段一致。
 */
public interface AiDataQueryService {

    /**
     * 按物料品类或名称模糊查询收发存。
     * 期初与期末按 wms_stock_movement 的实际日期边界累加计算，不使用当前库存代替历史余额。
     *
     * @param keyword       物料名称、编码或品类关键词，可空表示全部
     * @param warehouseType 仓库类型编码，可空表示全部仓库
     * @param dateFrom      统计开始日期
     * @param dateTo        统计结束日期
     * @return 收发存结果，主表按物料汇总，附按仓库明细区块
     */
    AiQueryResult queryInventoryByMaterial(String keyword, String warehouseType, LocalDate dateFrom, LocalDate dateTo);

    /**
     * 按仓库和物料查询当前库存明细。
     *
     * @param warehouseCode 仓库编码，可空表示全部仓库
     * @param warehouseType 仓库类型编码（RAW/LINE_SIDE/FINISHED…），可空表示不限类型。
     *                      用户说「线边仓还有多少」时只有类型可抓，不传就把全部仓库的库存都算进去。
     * @param materialKeyword 物料名称或编码，可空表示全部物料
     * @return 库存明细结果
     */
    AiQueryResult queryInventoryByWarehouse(String warehouseCode, String warehouseType, String materialKeyword);

    /**
     * 查当前低于安全库存的物料清单。
     *
     * <p>阈值口径与自动化规则执行缺口计算完全同源：可用量取该仓库类型下全部仓库的可用库存合计，
     * 阈值取规则默认阈值或物料上单独配置的安全库存。只读，不写风险台账、不生成补料建议。</p>
     *
     * @param warehouseType   仓库类型编码，可空表示覆盖全部已配置规则的类型
     * @param materialKeyword 物料名称或编码关键词，可空表示不限物料
     * @return 低于阈值的物料与缺口数量
     */
    AiQueryResult queryInventoryBelowSafety(String warehouseType, String materialKeyword);

    /**
     * 按工单号查询工单全息，包含工单信息、领料明细、报工明细、质检单和产品批次多个区块。
     *
     * @param workOrderNo 工单编号
     * @return 工单全息结果
     */
    AiQueryResult queryWorkOrderDetail(String workOrderNo);

    /**
     * 按工单号核查「现在能不能开工」，逐条给出通过/未通过与原因。
     *
     * <p>判定口径与 {@code MfgWorkOrderServiceImpl.startWorkOrder} 完全同源（制造准备闸门、
     * 单据状态、用料需求是否齐套、工序任务是否就绪），区别是只读：任何一条不满足都只记录原因，
     * 不抛异常、不改状态。这样 AI 才能回答「为什么现在开不了工、应该先做什么」，
     * 而不是丢一句失败提示让用户自己去查。</p>
     *
     * @param workOrderNo 工单编号
     * @return 逐条核查结论，附阻塞物料与组件子工单明细
     */
    AiQueryResult queryWorkOrderReadiness(String workOrderNo);

    /**
     * 按工单号推演「怎么才能开工」：把核查出来的阻塞项翻成有先后顺序的处置方案，
     * 并对「直接开工」和「先补齐前置条件」两条路径做只读比较。
     *
     * <p>只读推演，不写任何单据。工时只汇总工艺路线的标准工时，不含排队、换型与等待，
     * 因此结论里明说这是「可比较的量级」而不是承诺的交期。</p>
     *
     * @param workOrderNo 工单编号
     * @return 方案步骤与两条路径的比较结论
     */
    AiQueryResult queryWorkOrderStartPlan(String workOrderNo);

    /**
     * 按时间范围查询每日产量、报工、不良数量与不良率。
     *
     * @param dateFrom 开始日期
     * @param dateTo   结束日期
     * @return 产量趋势结果
     */
    AiQueryResult queryProductionByDate(LocalDate dateFrom, LocalDate dateTo);

    /**
     * 按时间范围查询不良数量排名靠前的物料与工序。
     *
     * @param dateFrom       开始日期
     * @param dateTo         结束日期
     * @param productKeyword 产品编码或名称筛选，null 表示不筛选
     * @return 不良分布结果
     */
    AiQueryResult queryDefectByPeriod(LocalDate dateFrom, LocalDate dateTo, String productKeyword);

    /**
     * 按批次号追溯来源入库单、领用工单、报工、检验记录和成品批次。
     *
     * @param batchNo 物料批次号或成品批次号
     * @return 批次追溯结果
     */
    AiQueryResult queryTraceByBatch(String batchNo);

    /**
     * 按时间范围查询产线排程与产能占用。
     *
     * @param dateFrom 开始日期
     * @param dateTo   结束日期
     * @return 排程产能结果
     */
    AiQueryResult queryScheduleCapacity(LocalDate dateFrom, LocalDate dateTo);

    /**
     * 按时间范围查询产线排程，并可按产线名称或编码收窄。
     *
     * @param dateFrom    开始日期
     * @param dateTo      结束日期
     * @param lineKeyword 产线名称或编码，空表示全部产线
     * @return 排程产能结果
     */
    AiQueryResult queryScheduleCapacity(LocalDate dateFrom, LocalDate dateTo, String lineKeyword);

    /**
     * 排程与产能。lateOnly 为真时只返回延期任务；没有延期时返回空结果，不退回全部产线。
     */
    AiQueryResult queryScheduleCapacity(LocalDate dateFrom, LocalDate dateTo, String lineKeyword, boolean lateOnly);

    /**
     * 按生产订单号查询订单进度，包含订单量、已排产、已完工、入库和发货。
     *
     * @param orderNo 生产订单编号
     * @return 订单进度结果
     */
    AiQueryResult queryOrderProgress(String orderNo);

    /**
     * 按生产订单号做执行前排程推演：在冻结的订单、路线、周历与产能口径下，
     * 只读比较「现状口径」「调整资源」「组件优先」三种排法的最早开工与完工时间。
     *
     * <p>与手工排程草案用的是同一套班次窗口与产能分摊算法，产出只用于比较，
     * 不写排程明细、不生成生产计划。推算口径是最早能开工、按产能做完，
     * 不含排队、换型与待料等待，因此不能当作交期承诺。</p>
     *
     * @param orderNo 生产订单编号
     * @return 三方案对比、假设条件与缺口说明
     */
    AiQueryResult queryOrderScheduleSimulation(String orderNo);

    /**
     * 查询设备故障影响的工单、任务与订单。
     *
     * @param equipmentKeyword 设备名称或编码关键词，可空表示全部故障设备
     * @param dateFrom         开始日期
     * @param dateTo           结束日期
     * @return 设备影响结果
     */
    AiQueryResult queryEquipmentImpact(String equipmentKeyword, LocalDate dateFrom, LocalDate dateTo);

    /**
     * 按产品追加需求测算产能与交期可行性。
     * 依据 BOM 净需求、现有库存和产线剩余产能给出确定性结论。
     *
     * @param productKeyword 产品名称或编码关键词
     * @param extraQuantity  追加数量
     * @param dateFrom       测算开始日期
     * @param dateTo         测算结束日期
     * @return 产能测算结果
     */
    AiQueryResult queryCapacityFeasibility(String productKeyword, int extraQuantity, LocalDate dateFrom, LocalDate dateTo);

    /**
     * 按产品型号或名称查询工单清单，回答“L200 有哪些工单”这类列表型提问。
     *
     * @param productKeyword 产品型号或名称关键词，可空表示全部产品
     * @param status         工单状态编码，可空表示全部状态
     * @return 工单清单结果
     */
    AiQueryResult queryWorkOrdersByProduct(String productKeyword, String status);

    /**
     * 按客户名称查询该客户下的生产订单与交付进度。
     *
     * @param customerKeyword 客户名称关键词，可空表示全部客户
     * @return 客户订单结果
     */
    AiQueryResult queryOrdersByCustomer(String customerKeyword);

    /**
     * 查询尚未完成的检验单，覆盖来料、过程、终检与出货检验。
     *
     * @param inspectionType 检验类型编码，可空表示全部类型
     * @return 待检检验单结果
     */
    AiQueryResult queryPendingInspections(String inspectionType);

    /**
     * 按产品与生产台数逐层展开 BOM，回答「这个产品一台要用多少」这类用量提问。
     *
     * <p>用的是主数据 BOM 需求分析同一套递归展开：子 BOM 逐层拆到叶子物料，按发料粒度取整，
     * 与工单领料、采购需求口径一致。只读，不预留库存、不生成单据。</p>
     *
     * @param productKeyword 产品名称或编码关键词
     * @param materialKeyword 关心的物料名称或编码关键词，可空表示看全部物料
     * @param quantity       生产台数，为空或非正数时按 1 台
     * @param asOf           BOM 生效日期，为空时按今天
     * @return 逐层用量明细与合计
     */
    AiQueryResult queryBomRequirement(String productKeyword, String materialKeyword,
                                      BigDecimal quantity, LocalDate asOf);

    /**
     * 检验口径的良率：本期已判定检验单的抽检、合格、不合格数量与合格率。
     *
     * @param dateFrom       开始日期，可空
     * @param dateTo         结束日期，可空
     * @param productKeyword 产品/物料关键词，可空
     */
    AiQueryResult queryQualityYield(LocalDate dateFrom, LocalDate dateTo, String productKeyword);
}
