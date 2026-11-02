package com.neuedu.ems.entity.ai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI 结构化查询结果的列定义。
 * prop 与 rows 中 Map 的 key 一致，label 为前端表头显示的中文名称。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class AiColumn {

    /**
     * 列属性名，对应 rows 中每行的 key。
     */
    private String prop;

    /**
     * 列显示名称。
     */
    private String label;
}
