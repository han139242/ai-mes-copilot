package com.neuedu.ems.service.impl.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neuedu.ems.entity.ai.AiAutomationRule;
import com.neuedu.ems.entity.mdm.MdmMaterial;
import com.neuedu.ems.entity.wms.WmsInventory;
import com.neuedu.ems.entity.wms.WmsWarehouse;
import com.neuedu.ems.mapper.ai.AiAutomationRuleMapper;
import com.neuedu.ems.mapper.ai.AiDataQueryMapper;
import com.neuedu.ems.mapper.mdm.MdmMaterialMapper;
import com.neuedu.ems.mapper.mdm.MdmProductMapper;
import com.neuedu.ems.mapper.wms.WmsInventoryMapper;
import com.neuedu.ems.mapper.wms.WmsWarehouseMapper;
import com.neuedu.ems.service.ai.AiAutomationService;
import com.neuedu.ems.exception.AiServiceException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 低于安全库存的 AI 侧渲染：阈值与缺口都来自自动化规则的同一套口径，不在这里另算。
 */
class AiInventoryBelowSafetyTest {

    @Test
    void readsInventoryInOneBatchAndCountsMissingBalancesAsZero() {
        var rules = mock(AiAutomationRuleMapper.class);
        var materials = mock(MdmMaterialMapper.class);
        var inventory = mock(WmsInventoryMapper.class);
        var warehouses = mock(WmsWarehouseMapper.class);
        var rule = new AiAutomationRule();
        rule.setRuleType("INVENTORY_BELOW_SAFETY");
        rule.setStatus("ENABLED");
        rule.setDefaultThreshold(BigDecimal.TEN);
        when(rules.selectList(any())).thenReturn(List.of(rule));
        var first = new MdmMaterial();
        first.setMaterialId(1L);
        first.setMaterialCode("M01");
        var second = new MdmMaterial();
        second.setMaterialId(2L);
        second.setMaterialCode("M02");
        when(materials.selectList(any())).thenReturn(List.of(first, second));
        var warehouse = new WmsWarehouse();
        warehouse.setWarehouseId(1L);
        when(warehouses.selectList(any())).thenReturn(List.of(warehouse));
        var balance = new WmsInventory();
        balance.setMaterialId(1L);
        balance.setAvailableQuantity(BigDecimal.valueOf(3));
        var extraBalance = new WmsInventory();
        extraBalance.setMaterialId(1L);
        extraBalance.setAvailableQuantity(BigDecimal.valueOf(4));
        when(inventory.selectList(any())).thenReturn(List.of(balance, extraBalance));

        var service = new AiAutomationServiceImpl(rules, null, null, null, materials,
                inventory, warehouses, null, new ObjectMapper());
        var shortages = service.lowStockMaterials("RAW", null);

        assertEquals(List.of("M01", "M02"), shortages.stream().map(row -> row.get("materialCode")).toList());
        assertEquals(List.of(BigDecimal.valueOf(3), BigDecimal.TEN),
                shortages.stream().map(row -> row.get("shortageQuantity")).toList());
        verify(warehouses).selectList(any());
        verify(inventory).selectList(any());
    }

    private AiDataQueryServiceImpl newService(AiAutomationService automationService) throws Exception {
        AiDataQueryServiceImpl service = new AiDataQueryServiceImpl(mock(AiDataQueryMapper.class),
                mock(MdmMaterialMapper.class), mock(MdmProductMapper.class), mock(WmsWarehouseMapper.class));
        Field field = AiDataQueryServiceImpl.class.getDeclaredField("automationService");
        field.setAccessible(true);
        field.set(service, automationService);
        return service;
    }

    private Map<String, Object> shortageRow(String code, String name, String warehouseType,
                                            String available, String threshold, String shortage) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("warehouseType", warehouseType);
        row.put("ruleNo", "RULE-1");
        row.put("ruleName", "线边仓安全库存预警");
        row.put("ruleStatus", "ENABLED");
        row.put("materialId", 17L);
        row.put("materialCode", code);
        row.put("materialName", name);
        row.put("availableQuantity", new BigDecimal(available));
        row.put("thresholdQuantity", new BigDecimal(threshold));
        row.put("shortageQuantity", new BigDecimal(shortage));
        return row;
    }

    @Test
    void rendersShortagesWithThresholdAndRuleStatus() throws Exception {
        AiAutomationService automationService = mock(AiAutomationService.class);
        when(automationService.lowStockMaterials("LINE_SIDE", null))
                .thenReturn(List.of(shortageRow("LAMP-M01", "控制板", "LINE_SIDE", "0", "20", "20")));

        var result = newService(automationService).queryInventoryBelowSafety("line_side", null);

        assertEquals(1, result.getRows().size());
        Map<String, Object> row = result.getRows().get(0);
        assertEquals("LAMP-M01", row.get("materialCode"));
        assertEquals("线边仓", row.get("warehouseTypeName"));
        assertEquals("20", String.valueOf(row.get("shortageQuantity")));
        assertEquals("启用", row.get("ruleStatusName"));
        assertTrue(result.getTitle().contains("线边仓"));
        assertTrue(result.getSummary().contains("缺口数量合计 20"));
        assertEquals(1, result.getBlocks().size());
    }

    @Test
    void mixedMaterialsDoNotProduceMeaninglessTotalOrComparisonChart() throws Exception {
        AiAutomationService automationService = mock(AiAutomationService.class);
        when(automationService.lowStockMaterials("LINE_SIDE", null)).thenReturn(List.of(
                shortageRow("LAMP-M01", "控制板", "LINE_SIDE", "0", "20", "20"),
                shortageRow("LAMP-M02", "灯臂", "LINE_SIDE", "0", "5", "5")));
        var result = newService(automationService).queryInventoryBelowSafety("LINE_SIDE", null);
        assertEquals(2, result.getRows().size());
        assertTrue(result.getSummary().contains("各物料缺口"));
        assertFalse(result.getSummary().contains("缺口数量合计"));
        assertTrue(result.getBlocks().isEmpty());
    }

    @Test
    void emptyResultDoesNotClaimEverythingIsEnough() throws Exception {
        AiAutomationService automationService = mock(AiAutomationService.class);
        when(automationService.lowStockMaterials("RAW", null)).thenReturn(List.of());

        var result = newService(automationService).queryInventoryBelowSafety("RAW", null);

        assertEquals(0, result.getRows().size());
        assertTrue(result.getSummary().contains("没有触发预警"));
        assertTrue(result.getSummary().contains("不等于"));
        assertTrue(result.getBlocks().isEmpty());
    }

    @Test
    void pausedRuleIsCalledOutInsteadOfHidden() throws Exception {
        AiAutomationService automationService = mock(AiAutomationService.class);
        Map<String, Object> row = shortageRow("LAMP-M02", "灯臂", "RAW", "5", "20", "15");
        row.put("ruleStatus", "PAUSED");
        when(automationService.lowStockMaterials("RAW", null)).thenReturn(List.of(row));

        var result = newService(automationService).queryInventoryBelowSafety("RAW", null);

        assertEquals("暂停", result.getRows().get(0).get("ruleStatusName"));
        assertTrue(result.getSummary().contains("未处于启用状态"));
    }

    @Test
    void missingServiceFailsLoudly() throws Exception {
        var service = newService(null);
        assertThrows(AiServiceException.class,
                () -> service.queryInventoryBelowSafety(null, null));
    }
}
