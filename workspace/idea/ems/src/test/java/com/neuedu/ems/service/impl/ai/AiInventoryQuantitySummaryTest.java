package com.neuedu.ems.service.impl.ai;

import com.neuedu.ems.mapper.ai.AiDataQueryMapper;
import com.neuedu.ems.mapper.mdm.MdmMaterialMapper;
import com.neuedu.ems.mapper.mdm.MdmProductMapper;
import com.neuedu.ems.mapper.wms.WmsWarehouseMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiInventoryQuantitySummaryTest {
    @Test
    void flowDoesNotSumDifferentMaterials() {
        AiDataQueryMapper mapper = mock(AiDataQueryMapper.class);
        var service = new AiDataQueryServiceImpl(mapper, mock(MdmMaterialMapper.class),
                mock(MdmProductMapper.class), mock(WmsWarehouseMapper.class));
        when(mapper.selectInventoryFlow(isNull(), isNull(), any(), any())).thenReturn(List.of(
                Map.of("materialCode", "A", "materialName", "灯板", "unitName", "块",
                        "inboundQuantity", new BigDecimal("10")),
                Map.of("materialCode", "B", "materialName", "螺丝", "unitName", "颗",
                        "inboundQuantity", new BigDecimal("20"))));
        var result = service.queryInventoryByMaterial(null, null, LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 30));
        assertEquals(2, result.getRows().size());
        assertTrue(result.getSummary().contains("按物料及单位"));
        assertFalse(result.getSummary().contains("入库合计"));
        assertEquals(1, result.getBlocks().size());
    }

    @Test
    void differentMaterialsAreNotAddedOrGraphedAsOneQuantity() {
        AiDataQueryMapper mapper = mock(AiDataQueryMapper.class);
        var service = new AiDataQueryServiceImpl(mapper, mock(MdmMaterialMapper.class),
                mock(MdmProductMapper.class), mock(WmsWarehouseMapper.class));
        when(mapper.selectInventoryDetail(isNull(), isNull(), isNull())).thenReturn(List.of(
                Map.of("materialCode", "A", "materialName", "灯板", "unitName", "块",
                        "availableQuantity", new BigDecimal("10")),
                Map.of("materialCode", "B", "materialName", "螺丝", "unitName", "颗",
                        "availableQuantity", new BigDecimal("20"))));
        var result = service.queryInventoryByWarehouse(null, null, null);
        assertEquals(2, result.getRows().size());
        assertTrue(result.getSummary().contains("2 种物料"));
        assertFalse(result.getSummary().contains("数量合计"));
        assertTrue(result.getBlocks().isEmpty());
    }
}
