package com.neuedu.ems.service.impl.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

import com.neuedu.ems.common.AuthorizationContext;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.exception.DataScopeAccessDeniedException;
import com.neuedu.ems.service.ai.AiDataResourceCatalog;

/**
 * 只读数据查询的准入边界。
 * 这些用例都故意在拼 SQL 之前就应当被拦住，因此不需要真实数据库：
 * 一旦有人把校验挪到查询之后，或者把「全局数据范围」这个前置条件删掉，这里会立刻失败。
 */
class AiReadOnlyQueryServiceImplTest {

    @Test
    void productionTaskLookupRequiresTaskPermissionBeforeReadingAnyRows() {
        assertThrows(DataScopeAccessDeniedException.class, () -> service.query("mfg_task",
                Map.of("workOrderNo", "WO-1"), context("ALL", "ai:assistant:use", "mfg:work-order:view")));
    }

    private final AiReadOnlyQueryServiceImpl service =
            new AiReadOnlyQueryServiceImpl(mock(DataSource.class));

    private AuthorizationContext context(String dataScope, String... permissions) {
        return new AuthorizationContext(1L, 1L, 1L, dataScope, Set.of("SOME_ROLE"),
                Set.of(permissions), Set.of());
    }

    @Test
    void missingLoginIsRejected() {
        DataScopeAccessDeniedException error = assertThrows(DataScopeAccessDeniedException.class,
                () -> service.query("sys_role", Map.of(), null));
        assertTrue(error.getMessage().contains("缺少登录信息"));
    }

    @Test
    void unknownResourceIsRejectedAsParameterError() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.query("information_schema.tables", Map.of(),
                        context("ALL", "sys:role:view")));
        // 资源名不在白名单是参数问题，不能报成「没查到数据」。
        assertEquals(ResultCode.PARAM_ERROR.getCode(), error.getCode());
        assertTrue(error.getMessage().contains("不支持的数据资源"));
    }

    @Test
    void missingPermissionIsRejectedWithReason() {
        DataScopeAccessDeniedException error = assertThrows(DataScopeAccessDeniedException.class,
                () -> service.query("sys_role", Map.of(), context("ALL")));
        assertTrue(error.getMessage().contains("没有查看"));
    }

    @Test
    void restrictedDataScopeIsRejectedEvenWithPermission() {
        DataScopeAccessDeniedException error = assertThrows(DataScopeAccessDeniedException.class,
                () -> service.query("sys_role", Map.of(), context("DEPARTMENT", "sys:role:view")));
        assertTrue(error.getMessage().contains("全局数据范围"));
    }

    @Test
    void superAdminPassesAdmissionEvenWithoutExplicitPermission() {
        AuthorizationContext admin = new AuthorizationContext(1L, 1L, 1L, "ALL",
                Set.of(AuthorizationContext.SUPER_ADMIN_ROLE), Set.of(), Set.of());
        // 准入通过后才会去碰数据库；mock 出来的连接拿不到真实数据，
        // 现在会明确说「数据库连接不可用」，而不是静默返回 null 让人以为没数据。
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.query("sys_role", Map.of(), admin));
        assertTrue(error.getMessage().contains("数据库连接不可用"), error.getMessage());
    }

    @Test
    void softDeletedRowsAreAlwaysExcluded() {
        // 基线条件必须无条件存在：AI 说的话要和业务页面看到的一致，
        // 已删除的单据不能再出现在「我的订单」这类回答里。
        assertEquals(" WHERE t.deleted_flag = 0", AiReadOnlyQueryServiceImpl.whereClause(List.of()));
        assertEquals(" WHERE t.deleted_flag = 0 AND t.status = :status",
                AiReadOnlyQueryServiceImpl.whereClause(List.of("t.status = :status")));
        assertTrue(AiReadOnlyQueryServiceImpl.baseConditions().contains("t.deleted_flag = 0"));
    }

    @Test
    void everyResourceExposesBaseTableAsAliasT() {
        // 软删除基线写成 t.deleted_flag，依赖「每个资源的基表别名都是 t」。
        // 谁以后改了某个资源的 from 却换了别名，这里会立刻失败，避免过滤条件悄悄失效。
        for (AiDataResourceCatalog.Resource resource : AiDataResourceCatalog.resources()) {
            assertTrue(resource.from().matches("[a-z_0-9]+ t\\b.*"),
                    resource.code() + " 的基表别名必须是 t，实际：" + resource.from());
        }
    }

    @Test
    void descriptionExposesFiltersButNoSql() {
        List<Map<String, Object>> resources = service.describeResources();
        assertNotNull(resources);
        assertTrue(resources.size() >= 20, "可查资源应覆盖系统主要维度，实际 " + resources.size());
        for (Map<String, Object> item : resources) {
            assertNotNull(item.get("resource"));
            assertNotNull(item.get("label"));
            assertTrue(item.get("maxRows") instanceof Integer value && value > 0);
            String text = String.valueOf(item);
            assertTrue(!text.contains("SELECT ") && !text.contains("password"), "描述里不能出现 SQL 或敏感字段");
        }
    }
}
