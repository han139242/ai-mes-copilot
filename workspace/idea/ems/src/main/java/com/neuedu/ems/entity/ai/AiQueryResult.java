package com.neuedu.ems.entity.ai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * AI 确定性查询的统一返回结构。
 * 查询结果全部来自数据库真实数据，不由大模型计算。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class AiQueryResult {

    /**
     * 查询标题。
     */
    private String title;

    /**
     * 一句话业务结论。
     */
    private String summary;

    /**
     * 主表列定义。
     */
    private List<AiColumn> columns;

    /**
     * 主表数据。
     */
    private List<Map<String, Object>> rows;

    /**
     * 附加区块，例如明细表、分布表或图表。
     */
    private List<AiBlock> blocks;
}
