package com.neuedu.ems.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.neuedu.ems.service.ai.AiDataResourceCatalog.Column;
import com.neuedu.ems.service.ai.AiDataResourceCatalog.Resource;

/**
 * 只读数据资源登记表的约束测试。
 * 这张表是模型能看到的全部数据边界，任何一条漏了权限码或把敏感列放进来，都会变成线上事故，
 * 所以这里把边界当成硬约束逐条钉住，而不是只测「能取到 23 条」。
 */
class AiDataResourceCatalogTest {

    private static final List<String> SENSITIVE_PROPS = List.of(
            "passwordHash", "password_hash", "loginFailCount", "lockedTime",
            "phone", "contactPhone", "phoneLookupHash", "contactPhoneLookupHash", "phone_lookup_hash");

    @Test
    void everyResourceDeclaresPermissionAndFrom() {
        List<Resource> resources = AiDataResourceCatalog.resources();
        assertFalse(resources.isEmpty(), "资源登记表不能为空");
        for (Resource resource : resources) {
            assertNotNull(resource.code(), resource.code() + " 缺少资源编码");
            assertNotNull(resource.label(), resource.code() + " 缺少中文名");
            assertNotNull(resource.from(), resource.code() + " 缺少 FROM 子句");
            assertNotNull(resource.permission(), resource.code() + " 缺少权限码，会导致任何人可查");
            assertTrue(resource.permission().contains(":view"),
                    resource.code() + " 的权限码应为 *:view，实际是 " + resource.permission());
            assertTrue(resource.maxRows() > 0, resource.code() + " 的行数上限必须为正");
            assertFalse(resource.columns().isEmpty(), resource.code() + " 没有任何列");
        }
    }

    @Test
    void resourceCodesAreUniqueAndLowerCase() {
        Set<String> seen = new HashSet<>();
        for (Resource resource : AiDataResourceCatalog.resources()) {
            assertTrue(seen.add(resource.code()), "资源编码重复：" + resource.code());
            assertEquals(resource.code().toLowerCase(), resource.code(), "资源编码必须全小写：" + resource.code());
        }
    }

    @Test
    void columnPropsAreUniqueWithinResource() {
        for (Resource resource : AiDataResourceCatalog.resources()) {
            Set<String> props = new HashSet<>();
            for (Column column : resource.columns()) {
                assertNotNull(column.prop(), resource.code() + " 有列缺少别名");
                assertNotNull(column.expr(), resource.code() + "." + column.prop() + " 缺少表达式");
                assertNotNull(column.label(), resource.code() + "." + column.prop() + " 缺少中文标签");
                assertTrue(props.add(column.prop()),
                        resource.code() + " 的列别名重复：" + column.prop());
            }
        }
    }

    @Test
    void sensitiveColumnsNeverExposed() {
        for (Resource resource : AiDataResourceCatalog.resources()) {
            for (Column column : resource.columns()) {
                for (String banned : SENSITIVE_PROPS) {
                    assertFalse(column.expr().contains(banned),
                            resource.code() + "." + column.prop() + " 暴露了敏感列 " + banned);
                }
            }
        }
    }

    @Test
    void resourceLookupIsCaseInsensitiveAndRejectsUnknown() {
        assertNotNull(AiDataResourceCatalog.find("SYS_ROLE"));
        assertNotNull(AiDataResourceCatalog.find(" sys_user "));
        assertNull(AiDataResourceCatalog.find("information_schema.tables"));
        assertNull(AiDataResourceCatalog.find(null));
    }

    @Test
    void describeMentionsEveryResourceButNoTableName() {
        String text = AiDataResourceCatalog.describe();
        for (Resource resource : AiDataResourceCatalog.resources()) {
            assertTrue(text.contains(resource.code()), "提示词里漏了资源 " + resource.code());
            assertTrue(text.contains(resource.label()), "提示词里漏了资源中文名 " + resource.label());
        }
        assertFalse(text.contains("SELECT"), "提示词不应该出现 SQL 片段");
        assertFalse(text.contains("password"), "提示词不应该出现敏感表结构");
    }
}
