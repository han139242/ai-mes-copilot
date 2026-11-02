package com.neuedu.ems.service.impl.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.neuedu.ems.entity.ai.AiColumn;
import com.neuedu.ems.entity.ai.AiKnowledgeSection;
import com.neuedu.ems.mapper.ai.AiKnowledgeSectionMapper;
import com.neuedu.ems.service.ai.AiKnowledgeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 知识助手实现：说明书章节检索。
 *
 * <p>中文没有空格，这里用「二到四元字符片段」做召回，再用 IDF 加权排序：
 * 一个片段在越少的章节里出现，说明它越能定位到具体内容；「设计」「要求」这种满篇都是的词
 * 权重自然被压到很低。除了词频，还要求命中的词在同一章里靠得足够近，
 * 避免「安全」和「库存」隔了两百字也被当成「安全库存」这个概念。</p>
 *
 * <p>分数不够就返回空列表，让上层明确回答「说明书里没有写到」，而不是编一段话。</p>
 */
@Service
public class AiKnowledgeServiceImpl implements AiKnowledgeService {

    /** 命中总分门槛，低于它视为没有真正命中。 */
    private static final double MIN_TOTAL_SCORE = 3.0;

    /** 标题命中权重放大倍数：标题是作者提炼过的词，比正文命中更可信。 */
    private static final double TITLE_BOOST = 1.6;

    /** 命中词占问题有效词权重的比例下限，低于它说明只蹭到了一两个词。 */
    private static final double MIN_COVERAGE = 0.45;

    /** 两个命中词在正文里的最大间距（字符），超过就认为各说各的。 */
    private static final int PROXIMITY_WINDOW = 40;

    /** 单个二字词命中时允许的最大文档频率，太常见的词单独出现不足以定位章节。 */
    private static final int SINGLE_TERM_MAX_DF = 30;

    /** 章节层级权重：末级章节最具体，父章节正文由子章节拼成，要压下去。 */
    private static final Map<Integer, Double> LEVEL_WEIGHT = Map.of(1, 0.45, 2, 0.7, 3, 1.0, 4, 1.0);

    /** 正文片段返回给前端的长度。 */
    private static final int SNIPPET_CHARS = 260;

    /** 停用片段：检索时没有区分度。 */
    private static final Set<String> STOP_TERMS = Set.of(
            "什么", "怎么", "如何", "哪些", "多少", "是否", "可以", "我们", "你们", "这个", "那个",
            "请问", "一下", "告诉", "介绍", "说明", "解释", "一个", "以及", "还有", "现在", "目前",
            "系统", "文档", "报告", "里面", "设计", "总体", "要求", "规定", "内容", "方法",
            "流程", "业务", "功能", "实现", "具体", "相关", "进行", "主要", "做法", "怎样",
            "怎么设", "如何设", "怎么规", "如何规", "设计的", "是怎", "么样", "多久", "多长", "时间");

    /** 片段首尾出现这些字时基本是切词切错的假片段，例如「计的」「料怎」。 */
    private static final String EDGE_STOP_CHARS = "的了是在和与及或要会能把被为对从到上下中里么怎样个些什吗呢吧啊也就都还很更最可该其此这那我你他它";

    private final AiKnowledgeSectionMapper sectionMapper;

    @Autowired
    public AiKnowledgeServiceImpl(AiKnowledgeSectionMapper sectionMapper) {
        this.sectionMapper = sectionMapper;
    }

    @Override
    public long sectionCount() {
        return sectionMapper.selectCount(new LambdaQueryWrapper<AiKnowledgeSection>()
                .eq(AiKnowledgeSection::getDeletedFlag, 0));
    }

    @Override
    public List<Map<String, Object>> search(String question, int limit) {
        SearchContext context = prepareSearch(question);
        if (context == null) {
            return List.of();
        }
        List<Map<String, Object>> hits = collectHits(context.sections(), context.frequency(),
                context.total(), context.totalWeight());
        return topHits(hits, limit);
    }

    /** 检索前的准备：切词、取片段、算文档频率和总权重，任一步没料就返回 null。 */
    private SearchContext prepareSearch(String question) {
        Set<String> terms = queryTerms(question);
        List<AiKnowledgeSection> sections = loadSections(terms);
        if (sections.isEmpty()) {
            return null;
        }
        Map<String, Integer> frequency = sectionFrequency(terms, sections);
        double totalWeight = totalWeight(frequency, sections.size());
        return totalWeight <= 0 ? null : new SearchContext(sections, frequency, sections.size(), totalWeight);
    }

    /** 问题里的检索词，空问题直接给空集合。 */
    private Set<String> queryTerms(String question) {
        return StringUtils.hasText(question) ? extractTerms(question) : Set.of();
    }

    /** 取全部未删除的说明书片段，没有检索词时不必查库。 */
    private List<AiKnowledgeSection> loadSections(Set<String> terms) {
        if (terms.isEmpty()) {
            return List.of();
        }
        return sectionMapper.selectList(new LambdaQueryWrapper<AiKnowledgeSection>()
                .eq(AiKnowledgeSection::getDeletedFlag, 0));
    }

    /** 按得分倒序，同分按文档编码和章节标题排，再截断到 limit 条。 */
    private List<Map<String, Object>> topHits(List<Map<String, Object>> hits, int limit) {
        hits.sort(Comparator.comparingInt((Map<String, Object> hit) -> -((Integer) hit.get("score")))
                .thenComparing(hit -> String.valueOf(hit.get("docCode")))
                .thenComparing(hit -> String.valueOf(hit.get("title"))));
        int max = Math.max(1, limit);
        return hits.size() > max ? new ArrayList<>(hits.subList(0, max)) : hits;
    }

    /** 准备阶段算出来的检索上下文。 */
    private record SearchContext(List<AiKnowledgeSection> sections,
                                 Map<String, Integer> frequency,
                                 int total,
                                 double totalWeight) {
    }

    /** 逐章打分，不达标的章节不进结果。 */
    private List<Map<String, Object>> collectHits(List<AiKnowledgeSection> sections,
                                                  Map<String, Integer> frequency,
                                                  int total,
                                                  double totalWeight) {
        List<Map<String, Object>> hits = new ArrayList<>();
        for (AiKnowledgeSection section : sections) {
            Map<String, Object> hit = scoreSection(section, frequency, total, totalWeight);
            if (hit != null) {
                hits.add(hit);
            }
        }
        return hits;
    }

    /**
     * 给单个片段打分，任一道门槛没过就返回 null（等价于原循环里的 continue）。
     */
    private Map<String, Object> scoreSection(AiKnowledgeSection section,
                                             Map<String, Integer> frequency,
                                             int total,
                                             double totalWeight) {
        SectionMatch match = matchSection(section, frequency, total);
        Double score = sectionScore(section, match, frequency, total, totalWeight);
        return score == null ? null : toHit(section, score);
    }

    /** 算单个片段的最终得分，命中词为空或没过门槛时返回 null。 */
    private Double sectionScore(AiKnowledgeSection section,
                                SectionMatch match,
                                Map<String, Integer> frequency,
                                int total,
                                double totalWeight) {
        // 只有长词命中，或两个以上短词在正文里彼此靠近，才算真的讲到这个话题。
        if (match.matched().isEmpty() || !passesTermRule(match, frequency)) {
            return null;
        }
        double coverage = coverageOf(match.matched(), frequency, total) / totalWeight;
        double score = match.raw() * coverage * LEVEL_WEIGHT.getOrDefault(
                section.getLevelNo() == null ? 3 : section.getLevelNo(), 0.9);
        boolean kept = coverage >= MIN_COVERAGE && score >= MIN_TOTAL_SCORE;
        return kept ? score : null;
    }

    /**
     * 短词兜底规则：没有长词命中时，要么两个以上短词靠得够近，要么这个词本身足够稀有。
     */
    private boolean passesTermRule(SectionMatch match, Map<String, Integer> frequency) {
        if (match.longHits() > 0) {
            return true;
        }
        if (match.matched().size() >= 2) {
            return proximity(match.content(), match.matched()) >= 2;
        }
        return frequency.get(match.matched().get(0)) <= SINGLE_TERM_MAX_DF;
    }

    /** 一个片段命中了哪些词、原始得分多少、有几个长词命中。 */
    private record SectionMatch(List<String> matched, double raw, int longHits, boolean titleHit, String content) {
    }

    /** 扫描单个片段，收集命中的词和原始权重。 */
    private SectionMatch matchSection(AiKnowledgeSection section,
                                      Map<String, Integer> frequency,
                                      int total) {
        List<String> matched = new ArrayList<>();
        double raw = 0;
        int longHits = 0;
        boolean titleHit = false;
        String title = lower(section.getTitle());
        String content = lower(section.getContent());
        for (Map.Entry<String, Integer> entry : frequency.entrySet()) {
            String term = entry.getKey();
            boolean inTitle = title.contains(term);
            if (!inTitle && !content.contains(term)) {
                continue;
            }
            matched.add(term);
            if (inTitle) {
                titleHit = true;
            }
            if (term.length() >= 3) {
                longHits++;
            }
            raw += weight(entry.getValue(), total) * (inTitle ? TITLE_BOOST : 1.0);
        }
        return new SectionMatch(matched, raw, longHits, titleHit, content);
    }

    /** 命中词的覆盖率，取「命中词权重和 / 全部词权重和」。 */
    private double coverageOf(List<String> matched, Map<String, Integer> frequency, int total) {
        double coverage = 0;
        for (String term : matched) {
            coverage += weight(frequency.get(term), total);
        }
        return coverage;
    }

    /** 把片段转成对外返回的命中结构。 */
    private Map<String, Object> toHit(AiKnowledgeSection section, double score) {
        Map<String, Object> hit = new LinkedHashMap<>();
        hit.put("docCode", section.getDocCode());
        hit.put("docName", section.getDocName());
        hit.put("title", section.getTitle());
        hit.put("levelNo", section.getLevelNo());
        hit.put("pageNo", section.getPageNo());
        hit.put("snippet", snippet(section.getContent()));
        hit.put("score", (int) Math.round(score));
        return hit;
    }

    /** 每个词出现在多少个片段里；一次都没出现的不放进 map。 */
    private Map<String, Integer> sectionFrequency(Set<String> terms, List<AiKnowledgeSection> sections) {
        Map<String, Integer> frequency = new LinkedHashMap<>();
        for (String term : terms) {
            int count = countSections(sections, term);
            if (count > 0) {
                frequency.put(term, count);
            }
        }
        return frequency;
    }

    /** 某个词在多少个片段里出现。 */
    private int countSections(List<AiKnowledgeSection> sections, String term) {
        int count = 0;
        for (AiKnowledgeSection section : sections) {
            if (containsTerm(section, term)) {
                count++;
            }
        }
        return count;
    }

    /** 全部词的权重合计，用来算覆盖率。 */
    private double totalWeight(Map<String, Integer> frequency, int total) {
        double totalWeight = 0;
        for (Map.Entry<String, Integer> entry : frequency.entrySet()) {
            totalWeight += weight(entry.getValue(), total);
        }
        return totalWeight;
    }

    @Override
    public String composeAnswer(String question, List<Map<String, Object>> hits) {
        if (hits == null || hits.isEmpty()) {
            return "说明书里没有检索到与「" + safeQuestion(question)
                    + "」对应的章节。可以换一个业务词再问，例如「工单冻结快照」「逐件全检」「线边退料」「安全库存预警」。";
        }
        StringBuilder answer = new StringBuilder();
        answer.append("说明书里与「").append(safeQuestion(question)).append("」相关的章节有 ")
                .append(hits.size()).append(" 处：");
        int index = 0;
        for (Map<String, Object> hit : hits) {
            index++;
            answer.append("\n\n").append(index).append(". ").append(hit.get("title"));
            String snippet = String.valueOf(hit.get("snippet"));
            if (StringUtils.hasText(snippet)) {
                answer.append("\n").append(snippet);
            }
            answer.append("\n出处：").append(hit.get("docName"))
                    .append(" · ").append(hit.get("title"));
            Object page = hit.get("pageNo");
            if (page != null) {
                answer.append(" · 第 ").append(page).append(" 页");
            }
        }
        return answer.toString();
    }

    @Override
    public Map<String, Object> buildBlock(List<Map<String, Object>> hits) {
        List<AiColumn> columns = List.of(
                new AiColumn("docName", "文档"),
                new AiColumn("title", "章节"),
                new AiColumn("pageNo", "页码"),
                new AiColumn("snippet", "原文摘要"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> hit : hits) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("docName", hit.get("docName"));
            row.put("title", hit.get("title"));
            row.put("pageNo", hit.get("pageNo"));
            row.put("snippet", hit.get("snippet"));
            rows.add(row);
        }
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("title", "说明书出处");
        block.put("columns", columns);
        block.put("rows", rows);
        return block;
    }

    /* ==================== 检索内部实现 ==================== */

    /** IDF 权重：片段在越少的章节出现，权重越高。 */
    private double weight(int documentFrequency, int totalSections) {
        return Math.log(1.0 + (double) totalSections / documentFrequency);
    }

    private boolean containsTerm(AiKnowledgeSection section, String term) {
        return lower(section.getTitle()).contains(term) || lower(section.getContent()).contains(term);
    }

    private String lower(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }

    /**
     * 从问题里抽检索片段：中文按 4、3、2 字依次切，英文与编号按整词。
     * 过滤停用词，以及首尾是虚词、明显切错位置的片段。
     */
    private Set<String> extractTerms(String question) {
        String text = question.trim().toLowerCase(Locale.ROOT);
        Set<String> terms = new LinkedHashSet<>();
        StringBuilder chinese = new StringBuilder();
        StringBuilder latin = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (isChinese(ch)) {
                flushLatin(latin, terms);
                chinese.append(ch);
                continue;
            }
            flushChinese(chinese, terms);
            if (Character.isLetterOrDigit(ch)) {
                latin.append(ch);
            } else {
                flushLatin(latin, terms);
            }
        }
        flushChinese(chinese, terms);
        flushLatin(latin, terms);
        terms.removeIf(this::isNoiseTerm);
        return terms;
    }

    private boolean isNoiseTerm(String term) {
        if (term.length() < 2 || STOP_TERMS.contains(term)) {
            return true;
        }
        for (String stop : STOP_TERMS) {
            if (term.contains(stop)) {
                return true;
            }
        }
        char first = term.charAt(0);
        char last = term.charAt(term.length() - 1);
        return EDGE_STOP_CHARS.indexOf(first) >= 0 || EDGE_STOP_CHARS.indexOf(last) >= 0;
    }

    private void flushChinese(StringBuilder buffer, Set<String> terms) {
        if (buffer.length() == 0) {
            return;
        }
        String block = buffer.toString();
        buffer.setLength(0);
        for (int size = 4; size >= 2; size--) {
            for (int i = 0; i + size <= block.length(); i++) {
                terms.add(block.substring(i, i + size));
            }
        }
    }

    private void flushLatin(StringBuilder buffer, Set<String> terms) {
        if (buffer.length() < 2) {
            buffer.setLength(0);
            return;
        }
        terms.add(buffer.toString());
        buffer.setLength(0);
    }

    private boolean isChinese(char ch) {
        return ch >= 0x4e00 && ch <= 0x9fff;
    }

    /**
     * 命中的词在正文里最多能凑成几个彼此靠近的。
     *
     * <p>「安全库存预警」里的「安全」和「库存」如果相隔两百字，那是两件不同的事，
     * 不能当成一个业务概念。这里返回 1 表示各自孤立的，返回 2 及以上表示确实挨在一起。</p>
     */
    private int proximity(String content, List<String> terms) {
        Map<String, List<Integer>> positions = termPositions(content, terms);
        if (positions.size() < 2) {
            return positions.size();
        }
        int best = 1;
        for (Map.Entry<String, List<Integer>> entry : positions.entrySet()) {
            for (Integer spot : entry.getValue()) {
                best = Math.max(best, nearCount(spot, entry.getKey(), positions));
            }
        }
        return best;
    }

    /** 每个词在正文里出现的位置，最多记 40 个。 */
    private Map<String, List<Integer>> termPositions(String content, List<String> terms) {
        Map<String, List<Integer>> positions = new LinkedHashMap<>();
        for (String term : terms) {
            List<Integer> spots = positionsOf(content, term);
            if (!spots.isEmpty()) {
                positions.put(term, spots);
            }
        }
        return positions;
    }

    /** 单个词在正文里的全部出现位置，最多 40 个。 */
    private List<Integer> positionsOf(String content, String term) {
        List<Integer> spots = new ArrayList<>();
        int start = 0;
        while (spots.size() < 40) {
            int at = content.indexOf(term, start);
            if (at < 0) {
                break;
            }
            spots.add(at);
            start = at + 1;
        }
        return spots;
    }

    /** 以某个位置为基准，数有几个词（含自己）在旁边。 */
    private int nearCount(int spot, String selfTerm, Map<String, List<Integer>> positions) {
        int near = 0;
        for (Map.Entry<String, List<Integer>> other : positions.entrySet()) {
            if (other.getKey().equals(selfTerm)) {
                near++;
                continue;
            }
            if (hasClosePosition(spot, other.getValue())) {
                near++;
            }
        }
        return near;
    }

    /** 目标词的任一出现位置是否落在窗口内。 */
    private boolean hasClosePosition(int spot, List<Integer> targets) {
        for (Integer target : targets) {
            if (Math.abs(spot - target) <= PROXIMITY_WINDOW) {
                return true;
            }
        }
        return false;
    }

    private String snippet(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        String text = content.replaceAll("\\s+", " ").trim();
        return text.length() <= SNIPPET_CHARS ? text : text.substring(0, SNIPPET_CHARS) + "…";
    }

    private String safeQuestion(String question) {
        if (!StringUtils.hasText(question)) {
            return "这个问题";
        }
        String text = question.trim();
        return text.length() <= 40 ? text : text.substring(0, 40) + "…";
    }
}
