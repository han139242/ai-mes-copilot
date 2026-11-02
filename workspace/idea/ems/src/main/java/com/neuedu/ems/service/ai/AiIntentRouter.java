package com.neuedu.ems.service.ai;

import com.neuedu.ems.entity.ai.AiQueryResult;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * AI 查询意图路由。
 * 先用中文关键词与正则表达式抽取参数，命中已知意图时直接调用确定性查询服务；
 * 匹配不到才交给大模型自由问答，从而保证常见业务问句快速、准确、零 Token。
 */
public interface AiIntentRouter {

    /**
     * 未命中确定性意图时的通用问答编码。
     */
    String INTENT_GENERAL = "general";

    /**
     * 识别意图并抽取参数。
     *
     * @param question      本轮问题
     * @param history       最近几轮用户消息，用于继承省略的物料、日期或单据号
     * @param defaultRange  前端上下文给出的日期范围，可空
     * @return 路由结果；未命中时 intent 为 general
     */
    RouteResult route(String question, List<String> history, DateRange defaultRange);

    /**
     * 命中确定性意图后执行查询。
     *
     * @param route 路由结果
     * @return 查询结果；未命中返回 null
     */
    AiQueryResult execute(RouteResult route);

    /**
     * 按意图生成 3 条追问建议。
     *
     * @param intent 意图编码
     * @return 建议列表
     */
    List<String> suggestions(String intent);

    /**
     * 路由结果。
     *
     * @param intent  意图编码，未命中为 general
     * @param params  抽取到的参数
     * @param matched 是否命中确定性查询
     */
    record RouteResult(String intent, Map<String, Object> params, boolean matched) {

        /**
         * 读取字符串参数。
         */
        public String string(String key) {
            Object value = params == null ? null : params.get(key);
            return value == null ? null : value.toString();
        }

        /**
         * 读取日期参数。
         */
        public LocalDate date(String key) {
            Object value = params == null ? null : params.get(key);
            return value instanceof LocalDate localDate ? localDate : null;
        }

        /**
         * 读取整数参数。
         */
        public int integer(String key, int defaultValue) {
            Object value = params == null ? null : params.get(key);
            if (value instanceof Number number) {
                return number.intValue();
            }
            Integer parsed = parseInteger(value);
            return parsed == null ? defaultValue : parsed;
        }

        /** 文本参数转整数，空值或格式非法返回 null。 */
        private Integer parseInteger(Object value) {
            if (value == null) {
                return null;
            }
            try {
                return Integer.parseInt(value.toString().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        /**
         * 读取数量参数，支持 BigDecimal、整数与数字文本；空值或格式非法返回 null。
         */
        public java.math.BigDecimal decimal(String key) {
            Object value = params == null ? null : params.get(key);
            java.math.BigDecimal result = null;
            if (value instanceof java.math.BigDecimal bigDecimal) {
                result = bigDecimal;
            } else if (value instanceof Number number) {
                result = java.math.BigDecimal.valueOf(number.doubleValue());
            } else if (value != null) {
                try {
                    result = new java.math.BigDecimal(value.toString().trim());
                } catch (NumberFormatException ignored) {
                    result = null;
                }
            }
            return result;
        }
    }

    /**
     * 日期范围。
     *
     * @param begin 开始日期
     * @param end   结束日期
     */
    record DateRange(LocalDate begin, LocalDate end) {

        /**
         * 转换为查询服务使用的数组形式。
         */
        public LocalDate[] toArray() {
            return new LocalDate[]{begin, end};
        }
    }
}
