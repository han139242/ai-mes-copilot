package com.neuedu.ems.service.ai;

import java.util.List;
import java.util.Map;

/**
 * 知识助手：在需求规格说明书与系统设计说明书里检索章节，返回带出处的依据。
 *
 * <p>检索只做中文关键词匹配与排序，不让模型凭空生成出处；
 * 模型可以基于命中的章节片段做解释，但「哪份文档、第几章、第几页」由这里给出。</p>
 */
public interface AiKnowledgeService {

    /** 命中一章的最低分，低于它视为没找到依据。 */
    int MIN_SCORE = 3;

    /**
     * 按问题检索章节。
     *
     * @param question 用户问题
     * @param limit    最多返回几章
     * @return 每项含 docCode/docName/title/pageNo/snippet/score
     */
    List<Map<String, Object>> search(String question, int limit);

    /**
     * 把命中章节组织成带出处的回答文本。
     *
     * @param question 用户问题
     * @param hits     {@link #search} 的结果
     * @return 中文回答，每段末尾标注（文档名 · 章节 · 第 N 页）
     */
    String composeAnswer(String question, List<Map<String, Object>> hits);

    /**
     * 命中章节转成前端可渲染的表格区块。
     */
    Map<String, Object> buildBlock(List<Map<String, Object>> hits);

    /**
     * 索引里有多少章，用于状态展示与自检。
     */
    long sectionCount();
}
