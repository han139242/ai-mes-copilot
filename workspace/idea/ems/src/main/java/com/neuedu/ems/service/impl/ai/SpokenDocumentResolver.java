package com.neuedu.ems.service.impl.ai;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把口头的「005」补成对话或库里唯一的完整单号。
 * 生产订单和工单都可能以同一组数字结尾，说「订单」只认订单，说「工单」只认工单。
 */
public final class SpokenDocumentResolver {
    private static final Pattern FULL_ORDER = Pattern.compile(
            "(?<![A-Za-z0-9_-])((?:[A-Z][A-Z0-9]{0,7}-)?PO-\\d{8}-\\d{3,})(?![A-Za-z0-9_-])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FULL_WORK_ORDER = Pattern.compile(
            "(?<![A-Za-z0-9_-])((?:[A-Z][A-Z0-9]{0,7}-)?WO-\\d{8}-\\d{3,})(?![A-Za-z0-9_-])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SHORT_NUMBER = Pattern.compile("(?<![0-9])(\\d{3,})(?![0-9])");

    public record Expansion(String question, String clarify) {
        public boolean clarified() {
            return StringUtils.hasText(clarify);
        }
    }

    private SpokenDocumentResolver() {
    }

    public static Expansion expand(String question, List<String> history) {
        if (!StringUtils.hasText(question) || hasFullNumber(question)) {
            return new Expansion(question, null);
        }
        Matcher suffix = SHORT_NUMBER.matcher(question);
        if (!suffix.find() || !aboutDocument(question)) {
            return new Expansion(question, null);
        }
        String digits = suffix.group(1);
        String corpus = question + "\n" + String.join("\n", history == null ? List.of() : history);
        List<String> orders = endingWith(FULL_ORDER, corpus, digits);
        List<String> workOrders = endingWith(FULL_WORK_ORDER, corpus, digits);
        boolean orderWord = question.contains("订单");
        boolean workWord = question.contains("工单");
        if (orderWord && !workWord) {
            return choose(question, digits, orders, workOrders, true);
        }
        if (workWord && !orderWord) {
            return choose(question, digits, workOrders, orders, false);
        }
        if (orders.size() == 1 && workOrders.isEmpty()) {
            return new Expansion(replaceFirst(question, digits, orders.getFirst()), null);
        }
        if (workOrders.size() == 1 && orders.isEmpty()) {
            return new Expansion(replaceFirst(question, digits, workOrders.getFirst()), null);
        }
        if (orders.size() == 1 && workOrders.size() == 1) {
            return new Expansion(question, "「" + digits + "」同时对上生产订单 " + orders.getFirst()
                    + " 和工单 " + workOrders.getFirst() + "。请说明是订单还是工单，尚未执行任何操作。");
        }
        return new Expansion(question, null);
    }

    /** 多张订单同时出现时，取这句话里最后一次提到的那张，不退回更早的订单。 */
    public static String lastOrderNumber(String text) {
        String found = null;
        if (!StringUtils.hasText(text)) {
            return null;
        }
        Matcher matcher = FULL_ORDER.matcher(text);
        while (matcher.find()) {
            found = matcher.group(1).toUpperCase(Locale.ROOT);
        }
        return found;
    }

    private static Expansion choose(String question, String digits, List<String> wanted, List<String> other,
                                    boolean order) {
        if (wanted.size() == 1) {
            return new Expansion(replaceFirst(question, digits, wanted.getFirst()), null);
        }
        if (wanted.size() > 1) {
            return new Expansion(question, "「" + digits + "」对上多张" + (order ? "生产订单" : "工单")
                    + "：" + String.join("、", wanted) + "。请说完整单号，尚未执行任何操作。");
        }
        if (other.size() == 1) {
            return new Expansion(question, "对话里没有以 " + digits + " 结尾的" + (order ? "生产订单" : "工单")
                    + "，只对上" + (order ? "工单 " : "生产订单 ") + other.getFirst()
                    + "。请改说完整单号，尚未执行任何操作。");
        }
        return new Expansion(question, null);
    }

    private static boolean hasFullNumber(String question) {
        return FULL_ORDER.matcher(question).find() || FULL_WORK_ORDER.matcher(question).find();
    }

    private static boolean aboutDocument(String question) {
        return question.contains("订单") || question.contains("工单") || question.contains("到哪")
                || question.contains("下一步") || question.contains("进行到") || question.contains("做到哪")
                || question.contains("哪一步") || question.contains("状态") || question.contains("完成");
    }

    private static List<String> endingWith(Pattern pattern, String text, String digits) {
        LinkedHashSet<String> numbers = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(text);
        String tail = "-" + digits;
        while (matcher.find()) {
            String number = matcher.group(1).toUpperCase(Locale.ROOT);
            if (number.endsWith(tail)) {
                numbers.add(number);
            }
        }
        return new ArrayList<>(numbers);
    }

    private static String replaceFirst(String question, String digits, String fullNumber) {
        return question.replaceFirst("(?<![0-9])" + Pattern.quote(digits) + "(?![0-9])", fullNumber);
    }
}
