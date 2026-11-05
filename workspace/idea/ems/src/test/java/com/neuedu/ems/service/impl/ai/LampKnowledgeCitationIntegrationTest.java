package com.neuedu.ems.service.impl.ai;

import com.neuedu.ems.PhoneCryptoTestProperties;
import com.neuedu.ems.entity.ai.AiKnowledgeSection;
import com.neuedu.ems.mapper.ai.AiKnowledgeSectionMapper;
import com.neuedu.ems.service.ai.AiKnowledgeMarkdownIndexer;
import com.neuedu.ems.service.ai.AiKnowledgeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用自带的合成需求样例建章节索引，回答必须带文档名和章节；不依赖私人课程材料。
 */
@SpringBootTest(properties = {"mes.notification.scheduling-enabled=false", "mes.ai.enabled=false",
        PhoneCryptoTestProperties.ACTIVE_KEY, PhoneCryptoTestProperties.KEY_IDS,
        PhoneCryptoTestProperties.ENCRYPTION_KEY, PhoneCryptoTestProperties.LOOKUP_HMAC_KEY})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:(3307|3310)/mes_lamp_lineside_[A-Za-z0-9_]+.*")
class LampKnowledgeCitationIntegrationTest {
    private static final String DOC_CODE = "REQ";
    private static final String DOC_NAME = "智能调光台灯 MES 需求规格说明书";

    @Autowired JdbcTemplate jdbc;
    @Autowired AiKnowledgeSectionMapper sections;
    @Autowired AiKnowledgeService knowledge;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void requirementSpecCitesTheSectionThatMentionsTheSensor() throws Exception {
        var resource = getClass().getResource("/knowledge/lamp-requirements-test.md");
        assertNotNull(resource, "检索测试样例必须随源码提供");
        Path source = Path.of(resource.toURI());
        assertTrue(Files.isRegularFile(source), "找不到需求规格说明书：" + source);
        String markdown = Files.readString(source);
        List<AiKnowledgeSection> indexed = AiKnowledgeMarkdownIndexer.index(markdown, DOC_CODE, DOC_NAME);
        assertFalse(indexed.isEmpty());

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            status.setRollbackOnly();
            jdbc.update("DELETE FROM ai_knowledge_section WHERE doc_code=?", DOC_CODE);
            for (AiKnowledgeSection section : indexed) {
                sections.insert(section);
            }
            List<Map<String, Object>> hits = knowledge.search("环境光传感器装在哪", 3);
            assertFalse(hits.isEmpty());
            Map<String, Object> top = hits.get(0);
            assertTrue(String.valueOf(top.get("docName")).contains("需求规格说明书"));
            assertTrue(String.valueOf(top.get("title")).contains("产品与制造范围"));
            String snippet = String.valueOf(top.get("snippet"));
            assertTrue(snippet.contains("环境光传感器"));
            String answer = knowledge.composeAnswer("环境光传感器装在哪", hits);
            assertTrue(answer.contains("出处："));
            assertTrue(answer.contains("需求规格说明书"));
            assertTrue(answer.contains("产品与制造范围"));
        });
    }
}
