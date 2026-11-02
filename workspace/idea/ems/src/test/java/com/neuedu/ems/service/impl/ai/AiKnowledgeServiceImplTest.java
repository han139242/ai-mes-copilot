package com.neuedu.ems.service.impl.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.neuedu.ems.entity.ai.AiKnowledgeSection;
import com.neuedu.ems.mapper.ai.AiKnowledgeSectionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 知识助手章节检索测试。
 * 关注三件事：能按中文片段命中标题与正文、命中门槛能挡住无关问题、回答里必须带文档名与页码。
 */
class AiKnowledgeServiceImplTest {

    private AiKnowledgeSectionMapper mapper;
    private AiKnowledgeServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(AiKnowledgeSectionMapper.class);
        service = new AiKnowledgeServiceImpl(mapper);
        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(sections());
        when(mapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(3L);
    }

    @Test
    void searchMatchesTitleAndReturnsDocumentWithPage() {
        List<Map<String, Object>> hits = service.search("线边退料怎么设计的", 3);
        assertFalse(hits.isEmpty());
        Map<String, Object> top = hits.get(0);
        assertEquals("2.2.4 库存与单据事务 DS-WMS", top.get("title"));
        assertEquals(18, top.get("pageNo"));
        assertEquals("系统设计说明书", top.get("docName"));
        assertTrue(((Integer) top.get("score")) >= 5);
    }

    @Test
    void searchMatchesContentWhenTitleMisses() {
        List<Map<String, Object>> hits = service.search("工位同一时间只能执行一道工序", 3);
        assertFalse(hits.isEmpty());
        assertEquals("2.2.3 多层BOM、工单与排程 DS-MFG", hits.get(0).get("title"));
    }

    @Test
    void unrelatedQuestionReturnsNoHit() {
        List<Map<String, Object>> hits = service.search("今天天气怎么样", 3);
        assertTrue(hits.isEmpty());
    }

    @Test
    void answerCarriesDocumentChapterAndPage() {
        List<Map<String, Object>> hits = service.search("线边退料", 3);
        String answer = service.composeAnswer("线边退料", hits);
        assertTrue(answer.contains("系统设计说明书"));
        assertTrue(answer.contains("2.2.4 库存与单据事务 DS-WMS"));
        assertTrue(answer.contains("第 18 页"));
        assertTrue(answer.contains("出处："));
    }

    @Test
    void emptyHitExplainsNoMatchInsteadOfInventing() {
        String answer = service.composeAnswer("火星基地怎么排产", List.of());
        assertTrue(answer.contains("没有检索到"));
        assertTrue(answer.contains("火星基地怎么排产"));
    }

    @Test
    void blockCarriesFourColumnsAndRows() {
        Map<String, Object> block = service.buildBlock(service.search("线边退料", 2));
        assertEquals("说明书出处", block.get("title"));
        assertEquals(4, ((List<?>) block.get("columns")).size());
        assertFalse(((List<?>) block.get("rows")).isEmpty());
    }

    @Test
    void sectionCountReadsFromMapper() {
        assertEquals(3L, service.sectionCount());
    }

    /**
     * 泛词「设计」会出现在很多章标题里，不能把「总体设计」顶到真正讲业务的章节前面。
     */
    @Test
    void rareTermBeatsGenericTermInRanking() {
        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(genericTitleSections());
        List<Map<String, Object>> hits = service.search("线边退料怎么设计的", 3);
        assertFalse(hits.isEmpty());
        assertEquals("2.2.4 库存与单据事务 DS-WMS", hits.get(0).get("title"));
    }

    private List<AiKnowledgeSection> genericTitleSections() {
        List<AiKnowledgeSection> list = new ArrayList<>();
        // 五章标题都带「设计」，用来模拟说明书里满篇都是「xx设计」的情况。
        list.add(section("DES", "系统设计说明书", 1, 1, "2. 总体设计", 9, "总体架构说明，未涉及具体单据业务。"));
        list.add(section("DES", "系统设计说明书", 2, 2, "2.1体系结构设计", 9, "前后端分离的模块化单体架构。"));
        list.add(section("DES", "系统设计说明书", 3, 2, "2.2功能设计", 10, "按业务域划分功能模块。"));
        list.add(section("DES", "系统设计说明书", 4, 2, "2.3技术架构设计", 30, "技术选型与工程分层。"));
        list.add(section("DES", "系统设计说明书", 5, 2, "3. 界面设计", 35, "页面布局与交互约定。"));
        // 唯一带具体业务词的一章，正文长度不如上面几章，靠标题稀有度胜出。
        list.add(section("DES", "系统设计说明书", 6, 3, "2.2.4 库存与单据事务 DS-WMS", 17,
                "线边退料必须先生成退料单，确认时成对写流水。"));
        return list;
    }

    private List<AiKnowledgeSection> sections() {
        List<AiKnowledgeSection> list = new ArrayList<>();
        list.add(section("DES", "系统设计说明书", 1, 3, "2.2.4 库存与单据事务 DS-WMS", 18,
                "库存扣减与线边退料在同一事务内完成，线边退料必须成对写流水，先减线边占用再补原仓可用量。"));
        list.add(section("DES", "系统设计说明书", 2, 3, "2.2.3 多层BOM、工单与排程 DS-MFG", 12,
                "同一工位同一时间只能执行一道工序，开工前校验工位是否已被其他运行中任务占用。"));
        list.add(section("REQ", "需求规格说明书", 3, 2, "3.4 检验与放行", 22,
                "逐件全检要求每件产品的每个必检项都有独立检验结果，缺项视为检验未完成。"));
        return list;
    }

    private AiKnowledgeSection section(String docCode, String docName, int seq, int level,
                                       String title, int page, String content) {
        AiKnowledgeSection section = new AiKnowledgeSection();
        section.setDocCode(docCode);
        section.setDocName(docName);
        section.setSeqNo(seq);
        section.setLevelNo(level);
        section.setTitle(title);
        section.setPageNo(page);
        section.setContent(content);
        section.setDeletedFlag(0);
        return section;
    }
}
