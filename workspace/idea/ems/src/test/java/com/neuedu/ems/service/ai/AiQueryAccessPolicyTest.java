package com.neuedu.ems.service.ai;
import com.neuedu.ems.common.AuthorizationContext;
import com.neuedu.ems.exception.DataScopeAccessDeniedException;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
class AiQueryAccessPolicyTest {
    private AuthorizationContext context(String scope,Set<String> permissions) {
        return new AuthorizationContext(1L,1L,1L,scope,Set.of(),permissions,Set.of());
    }
    @Test void assistantPermissionDoesNotGrantWarehouseAccess() {
        assertThrows(DataScopeAccessDeniedException.class,()->AiQueryAccessPolicy.requireIntent(context("ALL",Set.of("ai:assistant:use")),"inventory_stock"));
    }
    @Test void selfScopeCannotReadGlobalStockEvenWithViewPermission() {
        assertThrows(DataScopeAccessDeniedException.class,()->AiQueryAccessPolicy.requireIntent(context("SELF",Set.of("ai:assistant:use","wms:inventory:view")),"inventory_stock"));
    }
    @Test void allScopeWithBusinessPermissionCanQuery() {
        assertDoesNotThrow(()->AiQueryAccessPolicy.requireIntent(context("ALL",Set.of("ai:assistant:use","wms:inventory:view")),"inventory_stock"));
    }
    @Test void missingAuthorizationFailsClosed() {
        assertThrows(DataScopeAccessDeniedException.class,()->AiQueryAccessPolicy.requireIntent(null,"inventory_stock"));
    }
    @Test void moduleCaseAndWhitespaceCannotBypassBusinessPermission() {
        assertThrows(DataScopeAccessDeniedException.class,()->AiQueryAccessPolicy.requireModule(context("ALL",Set.of("ai:assistant:use"))," INVENTORY "));
    }
}
