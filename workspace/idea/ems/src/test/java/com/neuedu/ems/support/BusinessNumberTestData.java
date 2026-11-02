package com.neuedu.ems.support;

import com.neuedu.ems.service.sys.BusinessNumberService;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** 业务流单测只替代取号边界；真实并发、事务和重启连续性由数据库集成测试覆盖。 */
public final class BusinessNumberTestData {
    private BusinessNumberTestData() {}
    public static void inject(Object target) {
        AtomicLong sequence=new AtomicLong();
        BusinessNumberService numbers=Mockito.mock(BusinessNumberService.class,invocation -> {
            return switch(invocation.getMethod().getName()) {
                case "next", "nextFor" -> "T-"+invocation.getArgument(0)+"-20261027-"+String.format("%03d",sequence.incrementAndGet());
                case "canonical" -> invocation.getArgument(0);
                case "aliasIds" -> List.of();
                default -> org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
            };
        });
        ReflectionTestUtils.setField(target,"numbers",numbers);
    }
}
