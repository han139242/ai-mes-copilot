package com.neuedu.ems.service.impl.wms;

import com.neuedu.ems.entity.wms.WmsInventory;
import com.neuedu.ems.entity.wms.WmsReplenishmentSuggestion;
import com.neuedu.ems.mapper.wms.WmsInventoryMapper;
import com.neuedu.ems.mapper.wms.WmsReplenishmentSuggestionMapper;
import com.neuedu.ems.service.sys.BusinessNumberService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 补货建议生成：预警中心与自动化规则共用同一出口，同一物料只能挂一条待确认建议。
 */
class WmsReplenishmentSuggestionServiceImplTest {

    private final WmsReplenishmentSuggestionMapper suggestionMapper =
            mock(WmsReplenishmentSuggestionMapper.class);
    private final WmsInventoryMapper inventoryMapper = mock(WmsInventoryMapper.class);
    private final WmsResponsibilityNotificationService notificationService =
            mock(WmsResponsibilityNotificationService.class);
    private final BusinessNumberService numbers = mock(BusinessNumberService.class);

    private WmsReplenishmentSuggestionServiceImpl newService() {
        WmsReplenishmentSuggestionServiceImpl service = new WmsReplenishmentSuggestionServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", suggestionMapper);
        ReflectionTestUtils.setField(service, "wmsInventoryMapper", inventoryMapper);
        ReflectionTestUtils.setField(service, "responsibilityNotificationService", notificationService);
        ReflectionTestUtils.setField(service, "numbers", numbers);
        return service;
    }

    @Test
    void generateReusesPendingSuggestionInsteadOfCreatingASecondOne() {
        WmsReplenishmentSuggestion pending = new WmsReplenishmentSuggestion();
        pending.setSuggestionId(88L);
        pending.setSuggestionNo("RP-88");
        pending.setStatus("SUBMITTED");
        pending.setSourceType("INVENTORY_WARNING");
        when(suggestionMapper.selectOne(any())).thenReturn(pending);

        Long suggestionId = newService().generateSuggestion(1L, null, new BigDecimal("50"));

        // 预警中心和自动化规则都会调到这里，第二条链路必须复用原单
        assertEquals(88L, suggestionId);
        verify(suggestionMapper, never()).insert(any(WmsReplenishmentSuggestion.class));
        verify(notificationService, never()).publishReplenishmentPending(any(), any(), any());
    }

    @Test
    void generateCreatesSuggestionAndNotifiesWhenNothingPending() {
        when(suggestionMapper.selectOne(any())).thenReturn(null);
        WmsInventory inventory = new WmsInventory();
        inventory.setAvailableQuantity(new BigDecimal("5"));
        when(inventoryMapper.selectList(any())).thenReturn(List.of(inventory));
        when(numbers.next("RP", null)).thenReturn("RP-NEW");
        when(suggestionMapper.insert(any(WmsReplenishmentSuggestion.class))).thenAnswer(invocation -> {
            WmsReplenishmentSuggestion saved = invocation.getArgument(0);
            saved.setSuggestionId(66L);
            return 1;
        });

        Long suggestionId = newService().generateSuggestion(1L, null, new BigDecimal("50"));

        assertEquals(66L, suggestionId);
        verify(suggestionMapper).insert(any(WmsReplenishmentSuggestion.class));
        verify(notificationService).publishReplenishmentPending(any(), any(), any());
    }

    @Test
    void findPendingReturnsNullForMissingMaterial() {
        assertNull(newService().findPendingInventoryWarning(null));
        verify(suggestionMapper, never()).selectOne(any());
    }

    @Test
    void findPendingReturnsLatestPendingSuggestion() {
        WmsReplenishmentSuggestion pending = new WmsReplenishmentSuggestion();
        pending.setSuggestionId(9L);
        when(suggestionMapper.selectOne(any())).thenReturn(pending);

        assertSame(pending, newService().findPendingInventoryWarning(3L));
    }
}
