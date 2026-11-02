package com.neuedu.ems.service.sys;

import com.neuedu.ems.PhoneCryptoTestProperties;
import com.neuedu.ems.mapper.sys.BusinessNumberMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"mes.notification.scheduling-enabled=false","mes.ai.enabled=false",PhoneCryptoTestProperties.ACTIVE_KEY,
        PhoneCryptoTestProperties.KEY_IDS,PhoneCryptoTestProperties.ENCRYPTION_KEY,PhoneCryptoTestProperties.LOOKUP_HMAC_KEY})
@EnabledIfEnvironmentVariable(named="DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:(3307|3310|3311)/mes_lamp_lineside_.*")
class BusinessNumberIntegrationTest {
    @Autowired BusinessNumberService numbers;
    @Autowired BusinessNumberMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    private final List<Long> ids=new ArrayList<>();
    private final List<String> prefixes=new ArrayList<>();
    private Long customer;
    private String prefix;
    @BeforeEach void setup() {customer=createCustomer();prefix=prefixes.getFirst();}
    private Long createCustomer() {
        String value="N"+UUID.randomUUID().toString().replace("-","").substring(0,7).toUpperCase(Locale.ROOT);
        jdbc.update("INSERT INTO mdm_customer(customer_code,customer_name,number_prefix,status,deleted_flag) VALUES(?,?,?,'ENABLE',0)","NUMBER-TEST-"+value,"编号测试客户",value);
        Long id=jdbc.queryForObject("SELECT customer_id FROM mdm_customer WHERE number_prefix=?",Long.class,value);
        ids.add(id);prefixes.add(value);return id;
    }
    @AfterEach void cleanup() {
        for(String value:prefixes)jdbc.update("DELETE FROM sys_document_sequence WHERE scope_prefix=?",value);
        for(Long id:ids)jdbc.update("DELETE FROM mdm_customer WHERE customer_id=? AND customer_code LIKE 'NUMBER-TEST-%'",id);
    }
    @Test void concurrentRequestsAreUniqueAndContinuous() throws Exception {
        ExecutorService executor=Executors.newFixedThreadPool(8);
        try {
            List<Callable<String>> requests=new ArrayList<>();
            for(int i=0;i<128;i++)requests.add(()->numbers.next("PO",customer));
            Set<String> results=new HashSet<>();
            for(Future<String> result:executor.invokeAll(requests))assertTrue(results.add(result.get(20,TimeUnit.SECONDS)));
            assertEquals(128,results.size());
            assertTrue(numbers.next("PO",customer).endsWith("-129"));
        } finally {executor.shutdownNow();}
    }
    @Test void customerAndTypeHaveIndependentSequences() {
        Long second=createCustomer();
        assertTrue(numbers.next("PO",customer).startsWith(prefix+"-PO-"));
        assertTrue(numbers.next("SO",customer).endsWith("-001"));
        assertTrue(numbers.next("PO",second).endsWith("-001"));
        assertTrue(numbers.next("PO",customer).endsWith("-002"));
    }
    @Test void freshServiceKeepsSequenceAndDifferentDaysReset() {
        TransactionTemplate tx=new TransactionTemplate(transactions);
        Clock first=Clock.fixed(Instant.parse("2026-10-26T16:30:00Z"),ZoneId.of("Asia/Shanghai"));
        String one=tx.execute(status->new BusinessNumberService(mapper,first).next("WO",customer));
        String two=tx.execute(status->new BusinessNumberService(mapper,first).next("WO",customer));
        Clock tomorrow=Clock.fixed(Instant.parse("2026-10-27T16:30:00Z"),ZoneId.of("Asia/Shanghai"));
        String next=tx.execute(status->new BusinessNumberService(mapper,tomorrow).next("WO",customer));
        assertEquals(prefix+"-WO-20261027-001",one);
        assertEquals(prefix+"-WO-20261027-002",two);
        assertEquals(prefix+"-WO-20261028-001",next);
    }
    @Test void failedBusinessTransactionRollsBackItsUnpublishedNumber() {
        TransactionTemplate tx=new TransactionTemplate(transactions);
        tx.execute(status->{numbers.next("PO",customer);status.setRollbackOnly();return null;});
        assertTrue(numbers.next("PO",customer).endsWith("-001"));
    }
    @Test void outgoingInspectionInheritsCustomerWithoutAWorkOrder() {
        new TransactionTemplate(transactions).execute(status -> {
            String marker="NUMBER-TEST-"+UUID.randomUUID();
            jdbc.update("INSERT INTO mfg_production_order(production_order_no,customer_id,order_quantity,status,deleted_flag) VALUES(?,?,1,'DRAFT',0)",marker,customer);
            Long order=jdbc.queryForObject("SELECT production_order_id FROM mfg_production_order WHERE production_order_no=?",Long.class,marker);
            jdbc.update("INSERT INTO qms_inspection_order(inspection_no,inspection_type,status,deleted_flag) VALUES(?,'OUTGOING','DRAFT',0)",marker);
            Long inspection=jdbc.queryForObject("SELECT inspection_id FROM qms_inspection_order WHERE inspection_no=?",Long.class,marker);
            jdbc.update("INSERT INTO wms_outbound_order(outbound_no,outbound_type,source_production_order_id,inspection_id,audit_status,deleted_flag) VALUES(?,'SALES',?,?,'SUBMITTED',0)",marker,order,inspection);
            assertTrue(numbers.nextFor("NC","INSPECTION",inspection).startsWith(prefix+"-NC-"));
            status.setRollbackOnly();return null;
        });
    }
}
