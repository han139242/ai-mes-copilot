package com.neuedu.ems.service.impl.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpokenDocumentResolverTest {
    @Test
    void orderWordUsesTheProductionOrderNotTheWorkOrderWithTheSameTail() {
        var expanded = SpokenDocumentResolver.expand("005订单进行到哪一步了", List.of(
                "工单 C1-WO-20261101-005 已完成",
                "生产订单 C1-PO-20261101-005 已下达"));
        assertEquals("C1-PO-20261101-005订单进行到哪一步了", expanded.question());
        assertNull(expanded.clarify());
    }

    @Test
    void bareTailThatMatchesBothAsksInsteadOfGuessing() {
        var expanded = SpokenDocumentResolver.expand("005进行到哪一步了", List.of(
                "C1-WO-20261101-005",
                "C1-PO-20261101-005"));
        assertTrue(expanded.clarified());
        assertTrue(expanded.clarify().contains("C1-PO-20261101-005"));
        assertTrue(expanded.clarify().contains("C1-WO-20261101-005"));
    }

    @Test
    void nextStepKeepsTheLatestOrderInTheSameSentence() {
        assertEquals("C1-PO-20261101-005", SpokenDocumentResolver.lastOrderNumber(
                "先看 C1-PO-20261101-002，再看 C1-PO-20261101-005"));
    }
}
