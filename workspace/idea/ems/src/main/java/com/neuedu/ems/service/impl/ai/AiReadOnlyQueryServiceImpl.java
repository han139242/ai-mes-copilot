package com.neuedu.ems.service.impl.ai;

import com.neuedu.ems.common.AuthorizationContext;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.entity.ai.AiBlock;
import com.neuedu.ems.entity.ai.AiColumn;
import com.neuedu.ems.entity.ai.AiQueryResult;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.exception.DataScopeAccessDeniedException;
import com.neuedu.ems.service.ai.AiDataResourceCatalog;
import com.neuedu.ems.service.ai.AiReadOnlyQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AI 只读数据资源查询的实现。
 *
 * <p>SQL 完全由 {@link AiDataResourceCatalog} 里登记的列与条件拼装，模型只能选资源名
 * 和填筛选值，碰不到表名、列名或语句本身，所以不存在注入面。</p>
 *
 * <p>三道闸门依次过：资源在白名单里 → 账号有该资源的查看权限且数据范围是全局 →
 * 筛选键在该资源声明过的范围内。任一不过直接拒绝，不做"尽力而为"的降级。</p>
 */
@Service
public class AiReadOnlyQueryServiceImpl implements AiReadOnlyQueryService {

    private static final Logger log = LoggerFactory.getLogger(AiReadOnlyQueryServiceImpl.class);

    /** 关键词模糊匹配允许的最大长度，避免有人拿超长串去打全表。 */
    private static final int MAX_KEYWORD_LENGTH = 60;

    /** 兜底行数上限，资源自己声明的 maxRows 不能超过它。 */
    private static final int HARD_ROW_LIMIT = 500;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public AiReadOnlyQueryServiceImpl(DataSource dataSource) {
        this.jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
    }

    @Override
    public List<Map<String, Object>> describeResources() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (AiDataResourceCatalog.Resource resource : AiDataResourceCatalog.resources()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("resource", resource.code());
            item.put("label", resource.label());
            item.put("description", resource.description());
            List<Map<String, Object>> filters = new ArrayList<>();
            for (AiDataResourceCatalog.Filter filter : resource.filters()) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("name", filter.name());
                one.put("label", filter.label());
                one.put("type", filter.type());
                filters.add(one);
            }
            Map<String, Object> keyword = new LinkedHashMap<>();
            keyword.put("name", "keyword");
            keyword.put("label", "关键词（名称/编码/单号模糊匹配）");
            keyword.put("type", "like");
            if (resource.keywordExpr() != null) {
                filters.add(keyword);
            }
            item.put("filters", filters);
            item.put("maxRows", Math.min(resource.maxRows(), HARD_ROW_LIMIT));
            items.add(item);
        }
        return items;
    }

    @Override
    public AiQueryResult query(String resource, Map<String, Object> filters, AuthorizationContext authorization) {
        AiDataResourceCatalog.Resource definition = AiDataResourceCatalog.find(resource);
        if (definition == null) {
            // 静默返回 null 会让上层说成「没查到数据」，其实是用了一个不存在的资源名。
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "不支持的数据资源：" + resource);
        }
        assertReadable(definition, authorization);

        MapSqlParameterSource parameters = new MapSqlParameterSource();
        List<String> conditions = new ArrayList<>();
        List<String> applied = new ArrayList<>();
        Map<String, Object> safeFilters = filters == null ? Map.of() : filters;
        applyFilterConditions(definition, safeFilters, parameters, conditions, applied);
        applyKeywordCondition(definition, safeFilters, parameters, conditions, applied);

        int limit = Math.min(Math.max(definition.maxRows(), 1), HARD_ROW_LIMIT);
        List<AiColumn> columns = new ArrayList<>();
        String sql = buildSelectSql(definition, conditions, columns, limit);

        List<Map<String, Object>> rawRows = queryRows(definition, sql, parameters);

        boolean truncated = rawRows.size() > limit;
        List<Map<String, Object>> rows = buildRows(rawRows, columns, limit);

        AiQueryResult result = new AiQueryResult();
        result.setTitle(definition.label());
        // 结果被截断时状态分布会失真，宁可不给，也不要给出一个对不上的数字。
        Map<String, Integer> statusCounts = truncated ? Map.of() : statusBreakdown(rows);
        result.setSummary(summarize(definition, rows.size(), truncated, applied, statusCounts));
        result.setColumns(columns);
        result.setRows(rows);
        result.setBlocks(List.of());
        return result;
    }

    /** 按资源定义逐个把非空的筛选条件转成 SQL 条件与占位参数。 */
    private void applyFilterConditions(AiDataResourceCatalog.Resource definition,
                                       Map<String, Object> safeFilters,
                                       MapSqlParameterSource parameters,
                                       List<String> conditions,
                                       List<String> applied) {
        for (AiDataResourceCatalog.Filter filter : definition.filters()) {
            Object raw = safeFilters.get(filter.name());
            if (raw == null || (raw instanceof String text && text.isBlank())) {
                continue;
            }
            addFilterCondition(filter, raw, parameters, conditions, applied);
        }
    }

    /** 单个筛选条件：日期起止按当天零点/末点换算，其余原样（字符串去首尾空白）。 */
    private void addFilterCondition(AiDataResourceCatalog.Filter filter,
                                    Object raw,
                                    MapSqlParameterSource parameters,
                                    List<String> conditions,
                                    List<String> applied) {
        String placeholder = filter.name();
        switch (filter.type()) {
            case "dateFrom" -> parameters.addValue(placeholder, toDate(raw, false));
            case "dateTo" -> parameters.addValue(placeholder, toDate(raw, true));
            default -> parameters.addValue(placeholder, raw instanceof String text ? text.trim() : raw);
        }
        conditions.add(filter.expr());
        applied.add(filter.label());
    }

    /** 关键词条件：超长截断后再拼进 LIKE 表达式。 */
    private void applyKeywordCondition(AiDataResourceCatalog.Resource definition,
                                       Map<String, Object> safeFilters,
                                       MapSqlParameterSource parameters,
                                       List<String> conditions,
                                       List<String> applied) {
        Object keyword = safeFilters.get("keyword");
        if (!(keyword instanceof String text) || text.isBlank() || definition.keywordExpr() == null) {
            return;
        }
        String trimmed = text.trim();
        if (trimmed.length() > MAX_KEYWORD_LENGTH) {
            trimmed = trimmed.substring(0, MAX_KEYWORD_LENGTH);
        }
        parameters.addValue("keyword", trimmed);
        conditions.add("(" + definition.keywordExpr() + ")");
        applied.add("关键词「" + trimmed + "」");
    }

    /**
     * 软删除基线条件。
     *
     * <p>资源登记表里的 FROM 子句只管连表，不带软删除过滤；这里统一补上，
     * 保证 AI 查询和业务页面的口径一致——已删除的单据不能再出现在对话答案里。</p>
     */
    static List<String> baseConditions() {
        return List.of("t.deleted_flag = 0");
    }

    /** 组装 WHERE 子句：先放软删除基线，再放调用方筛选，统一用 AND 连接。 */
    static String whereClause(List<String> conditions) {
        List<String> all = new ArrayList<>(baseConditions());
        if (conditions != null) {
            all.addAll(conditions);
        }
        return all.isEmpty() ? "" : " WHERE " + String.join(" AND ", all);
    }

    /** 拼出带列别名 c0..cN 的查询语句，同时收集结果列元数据。 */
    private String buildSelectSql(AiDataResourceCatalog.Resource definition,
                                  List<String> conditions,
                                  List<AiColumn> columns,
                                  int limit) {
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int index = 0; index < definition.columns().size(); index++) {
            AiDataResourceCatalog.Column column = definition.columns().get(index);
            if (index > 0) {
                sql.append(", ");
            }
            sql.append(column.expr()).append(" AS c").append(index);
            columns.add(new AiColumn(column.prop(), column.label()));
        }
        sql.append(" FROM ").append(definition.from());
        sql.append(whereClause(conditions));
        String orderBy = definition.orderBy();
        if (orderBy != null && !orderBy.isBlank()) {
            sql.append(" ORDER BY ").append(orderBy);
        }
        sql.append(" LIMIT ").append(limit + 1);
        return sql.toString();
    }

    /** 执行查询。失败必须说清是「数据库连不上」还是「这条查询本身出错」，不能一句笼统报错。 */
    private List<Map<String, Object>> queryRows(AiDataResourceCatalog.Resource definition,
                                                String sql,
                                                MapSqlParameterSource parameters) {
        try {
            return jdbcTemplate.queryForList(sql, parameters);
        } catch (DataAccessResourceFailureException e) {
            // CannotGetJdbcConnectionException 是它的子类，这里一并覆盖。
            log.warn("AI 只读查询因数据库连接不可用而失败 resource={} message={}",
                    definition.code(), e.getMessage());
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(),
                    "业务数据暂时读不到（数据库连接不可用），" + definition.label() + "这一项没有查到");
        } catch (RuntimeException e) {
            log.warn("AI 只读查询失败 resource={} message={}", definition.code(), e.getMessage());
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(),
                    "业务数据读取失败：" + definition.label() + "（" + e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : "：" + e.getMessage()) + "）");
        }
    }

    /** 把原始行按列别名转换成对外字段，最多 limit 行。 */
    private List<Map<String, Object>> buildRows(List<Map<String, Object>> rawRows,
                                                List<AiColumn> columns,
                                                int limit) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int index = 0; index < rawRows.size() && index < limit; index++) {
            Map<String, Object> source = rawRows.get(index);
            Map<String, Object> row = new LinkedHashMap<>();
            for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
                row.put(columns.get(columnIndex).getProp(), normalize(source.get("c" + columnIndex)));
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * 权限与数据范围校验。拒绝时抛 {@link DataScopeAccessDeniedException}，
     * 由全局异常处理转成 403，和其余接口口径一致。
     */
    private void assertReadable(AiDataResourceCatalog.Resource definition, AuthorizationContext authorization) {
        if (authorization == null) {
            throw new DataScopeAccessDeniedException("缺少登录信息，不能查询业务数据");
        }
        if (!authorization.hasPermission(definition.permission())) {
            throw new DataScopeAccessDeniedException("当前账号没有查看" + definition.label() + "的权限");
        }
        if (!authorization.isSuperAdmin() && !"ALL".equals(authorization.dataScope())) {
            throw new DataScopeAccessDeniedException(
                    "当前账号只能看到本人或本部门范围内的数据，" + definition.label() + "需要全局数据范围才能查询");
        }
    }

    /**
     * 日期取值：dateFrom 用当天 00:00:00，dateTo 用当天 23:59:59，避免"截止今天"漏掉当天记录。
     */
    private Object toDate(Object raw, boolean endOfDay) {
        String text = String.valueOf(raw).trim();
        try {
            LocalDate date = LocalDate.parse(text);
            LocalDateTime moment = endOfDay ? date.atTime(23, 59, 59) : date.atStartOfDay();
            return Timestamp.valueOf(moment);
        } catch (RuntimeException e) {
            // 日期写错是调用方的参数问题，报成 403「无权限」会让人往权限方向排查。
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),
                    "日期格式应为 yyyy-MM-dd，收到的是「" + text + "」");
        }
    }

    /**
     * 把 JDBC 返回的类型归一成能直接序列化的形态：时间转字符串，其余原样。
     */
    private Object normalize(Object value) {
        return switch (value) {
            case Timestamp timestamp -> timestamp.toLocalDateTime().format(TIME_FORMAT);
            case LocalDateTime time -> time.format(TIME_FORMAT);
            case java.sql.Date date -> date.toLocalDate().toString();
            case null, default -> value;
        };
    }

    /**
     * 一句话结论。说清查的是哪张表、命中多少条、用了哪些条件、有没有被截断，
     * 让人一眼能判断"这个数字是不是我要的那个口径"。
     *
     * <p>结果带状态列时再补一句状态分布。这一句是为了让「70 条提醒」这种数字一眼看出
     * 里面有 33 条是已取消的——口径错了要能被当场发现，而不是等人工去翻明细。</p>
     */
    private String summarize(AiDataResourceCatalog.Resource definition, int rowCount,
                             boolean truncated, List<String> applied, Map<String, Integer> statusCounts) {
        StringBuilder summary = new StringBuilder();
        summary.append("查询").append(definition.label()).append("，");
        if (rowCount == 0) {
            summary.append(applied.isEmpty() ? "当前没有任何记录。" : "按" + String.join("、", applied) + "没有匹配到记录。");
            return summary.toString();
        }
        summary.append("命中 ").append(rowCount).append(" 条");
        if (!applied.isEmpty()) {
            summary.append("（条件：").append(String.join("、", applied)).append("）");
        }
        summary.append("。");
        String breakdown = describeStatusCounts(statusCounts);
        if (!breakdown.isEmpty()) {
            summary.append("其中").append(breakdown).append("。");
        }
        if (truncated) {
            summary.append("结果已按上限截断，需要更精确的请补充筛选条件。");
        }
        return summary.toString();
    }

    /**
     * 统计结果集里的状态分布。状态列数超过 4 种就直接放弃，避免摘要被一堆枚举值撑满。
     */
    private Map<String, Integer> statusBreakdown(List<Map<String, Object>> rows) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object status = row.get("status");
            if (status == null || String.valueOf(status).isBlank()) {
                return Map.of();
            }
            String key = String.valueOf(status).trim();
            counts.merge(key, 1, Integer::sum);
        }
        if (counts.size() > 4) {
            return Map.of();
        }
        return counts;
    }

    /**
     * 状态分布的中文描述，按条数从多到少排。
     */
    private String describeStatusCounts(Map<String, Integer> statusCounts) {
        if (statusCounts == null || statusCounts.isEmpty()) {
            return "";
        }
        return statusCounts.entrySet().stream()
                .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                .map(entry -> "状态 " + entry.getKey() + " 的 " + entry.getValue() + " 条")
                .collect(java.util.stream.Collectors.joining("、"));
    }

    /**
     * 资源名归一，供调用方复用（模型输出可能带空格或大小写不一致）。
     */
    static String normalizeResource(String resource) {
        return resource == null ? null : resource.trim().toLowerCase(Locale.ROOT);
    }
}
