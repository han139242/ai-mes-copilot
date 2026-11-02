package com.neuedu.ems.service.sys;

import com.neuedu.ems.mapper.sys.BusinessNumberMapper;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessNumberServiceTest {
    private final BusinessNumberMapper mapper=mock(BusinessNumberMapper.class);
    private final Clock clock=Clock.fixed(Instant.parse("2026-10-26T16:30:00Z"),ZoneId.of("Asia/Shanghai"));
    private final BusinessNumberService service=new BusinessNumberService(mapper,clock);

    @Test void usesShanghaiBusinessDateAndThreeDigitSequence() {
        when(mapper.customerPrefix(10L)).thenReturn("B");
        when(mapper.current("B","PO",LocalDate.of(2026,10,27))).thenReturn(1L);
        assertEquals("B-PO-20261027-001",service.next("PO",10L));
        verify(mapper).increment("B","PO",LocalDate.of(2026,10,27));
    }
    @Test void growsBeyond999WithoutTruncation() {
        when(mapper.current("SYS","IN",LocalDate.of(2026,10,27))).thenReturn(1000L);
        assertEquals("SYS-IN-20261027-1000",service.next("IN",null));
    }
    @Test void followsSourceCustomerAndSupportsHistoricalAliases() {
        when(mapper.customerId("WORK_ORDER",108L)).thenReturn(11L);
        when(mapper.customerPrefix(11L)).thenReturn("B");
        when(mapper.current("B","WO",LocalDate.of(2026,10,27))).thenReturn(2L);
        assertEquals("B-WO-20261027-002",service.nextFor("WO","WORK_ORDER",108L));
        when(mapper.currentNumbers("WO-C-OLD")).thenReturn(List.of("B-WO-20261027-002"));
        assertEquals("B-WO-20261027-002",service.canonical("WO-C-OLD"));
    }
    @Test void rejectsInvalidPrefixesAndUnknownDocumentTypes() {
        when(mapper.customerPrefix(10L)).thenReturn("BAD-PREFIX");
        assertThrows(IllegalStateException.class,()->service.next("PO",10L));
        assertThrows(IllegalArgumentException.class,()->service.next("UNKNOWN",null));
        verify(mapper,never()).increment(any(),any(),any());
    }
}
