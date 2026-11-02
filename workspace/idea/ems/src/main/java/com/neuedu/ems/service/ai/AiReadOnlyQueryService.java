package com.neuedu.ems.service.ai;

import com.neuedu.ems.common.AuthorizationContext;
import com.neuedu.ems.entity.ai.AiQueryResult;

import java.util.List;
import java.util.Map;

/**
 * AI 只读数据资源查询。
 *
 * <p>大模型能问到的不应该只有十几个固定报表。这里把系统里的主数据与业务单据
 * 按「资源」登记成白名单：模型只能选资源名 + 填几个筛选条件，SQL 由后端按
 * 白名单列拼装，模型接触不到任何表名、列名与 SQL 文本。</p>
 *
 * <p>三条硬约束：
 * 1. 只读。这里只发 SELECT，不接受任何写语句；
 * 2. 只暴露白名单列。密码摘要、手机号明文、加密检索哈希、密钥类字段永不出现；
 * 3. 一律带 LIMIT 与数据范围校验，避免一次拖空整库。</p>
 */
public interface AiReadOnlyQueryService {

    /**
     * 资源清单，用于拼进模型提示词与前端能力说明。
     *
     * @return 每个资源的编码、名称、说明与可筛选字段
     */
    List<Map<String, Object>> describeResources();

    /**
     * 按资源名与筛选条件查询。
     *
     * <p>不返回 null：资源名不在白名单、日期写错属于参数问题（{@code BusinessException} +
     * PARAM_ERROR），数据范围不够抛 {@code DataScopeAccessDeniedException}，
     * 数据库连不上或查询本身出错抛 {@code BusinessException} + BUSINESS_ERROR 并带上具体是哪一项。
     * 调用方据此能告诉用户「哪一项没读到、为什么」，而不是笼统说一句查询失败。</p>
     *
     * @param resource      资源编码，必须在白名单内
     * @param filters       筛选条件，键必须是该资源声明的筛选字段
     * @param authorization 当前账号，用于数据范围校验
     * @return 查询结果
     */
    AiQueryResult query(String resource, Map<String, Object> filters, AuthorizationContext authorization);
}
