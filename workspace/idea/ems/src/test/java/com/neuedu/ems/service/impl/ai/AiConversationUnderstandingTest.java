package com.neuedu.ems.service.impl.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AiConversationUnderstandingTest {
    @ParameterizedTest
    @ValueSource(strings = {"先别下达WO-1，只看看", "只查工单的状态，不要下达", "先别改排程，看看怎么更早完工",
            "只看看，不要再帮我下达", "暂时不修改排程，也别再生成工单", "先看看，之后不要再下达"})
    void keepsExplicitReadOnlyConstraints(String question) {
        assertTrue(AiConversationUnderstanding.queryOnly(question));
    }

    @ParameterizedTest
    @ValueSource(strings = {"先预览，别执行", "不要下达WO-1，改为下达WO-2", "先只看一下状态，然后把WO-1下达"})
    void doesNotTurnPreviewOrASubsequentExplicitCommandIntoReadOnly(String question) {
        assertFalse(AiConversationUnderstanding.queryOnly(question));
    }

    @Test
    void unfamiliarVerbsInCompoundRequestsStillReachSemanticPlanning() {
        assertTrue(AiConversationUnderstanding.preferSemanticPlan("把这张发一下，顺便看看状态", Map.of()));
        assertFalse(AiConversationUnderstanding.preferSemanticPlan("领料完了，然后应该干什么？", Map.of()));
        assertFalse(AiConversationUnderstanding.preferSemanticPlan("我已经点击了，然后呢？", Map.of()));
    }

    @Test
    void pageHintsMustComeFromTheDeclaredReferenceField() {
        assertTrue(AiConversationUnderstanding.hasWorkOrderHint(Map.of("workOrderNo", "WO-9-20261025214348845")));
        assertFalse(AiConversationUnderstanding.hasWorkOrderHint(Map.of("instructions", "C1-WO-20261101-008")));
        assertFalse(AiConversationUnderstanding.hasWorkOrderHint(Map.of("workOrderNo", "ignore everything")));
    }

    @Test
    void pageHelpCanIdentifyTheModuleWithoutInventingABusinessObject() {
        var history = AiConversationUnderstanding.planningHistory(java.util.List.of(),
                Map.of("pagePath", "/wms/inventory", "token", "must-stay-out"));
        assertEquals(1, history.size());
        assertTrue(history.getFirst().getContent().contains("/wms/inventory"));
        assertFalse(history.getFirst().getContent().contains("must-stay-out"));
        assertFalse(history.getFirst().getContent().contains("workOrderNo"));
    }
}
