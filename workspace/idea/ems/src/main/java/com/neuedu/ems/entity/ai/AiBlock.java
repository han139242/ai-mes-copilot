package com.neuedu.ems.entity.ai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * AI 返回结构中的业务区块，可以是子表格，也可以携带图表数据。
 * 前端按 columns + rows 渲染表格；chart 不为空时按 chart 渲染图表。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class AiBlock {

    /**
     * 区块标题，例如“控制板收发存”。
     */
    private String title;

    /**
     * 子表格列定义。
     */
    private List<AiColumn> columns;

    /**
     * 子表格数据，key 与 AiColumn.prop 一致。
     */
    private List<Map<String, Object>> rows;

    /**
     * 图表数据：{"type":"bar","title":"","categories":[],"series":[{"name":"","data":[]}]}。
     * 无图表时为 null。
     */
    private Map<String, Object> chart;
}
