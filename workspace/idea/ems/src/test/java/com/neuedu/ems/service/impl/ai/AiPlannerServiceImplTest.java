package com.neuedu.ems.service.impl.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neuedu.ems.config.AiProperties;
import com.neuedu.ems.service.ai.AiPlan;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 计划器复审（{@code revise}）的单元测试：确认第二轮的提示词真的带上了工具返回值，
 * 且模型给出的「再查一项 / 直接回答 / 补问」都能被正确解析。
 */
class AiPlannerServiceImplTest {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void workflowExplanationUsesFocusedPromptAndBoundedOutput() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        var planner = new AiPlannerServiceImpl(enabledProperties(),
                capturingClient("{\"mode\":\"chat\",\"answer\":\"先转线边，再派工\"}", body), objectMapper);
        var plan = planner.revise("领料完了然后呢", List.of(Map.of("resource", "work_order_start_plan",
                "summary", "物料尚未足额转线边", "verifiedNextSteps", List.of(Map.of("action", "领料转线边")))));
        assertNotNull(plan);
        var request = objectMapper.readTree(body.get());
        assertEquals(700, request.path("max_tokens").asInt());
        assertTrue(body.get().contains("物料尚未足额转线边"));
        assertTrue(body.get().length() < 5000, "解释既有结果时无需重发完整能力目录");
    }

    @Test
    void revisePutsRealObservationsIntoThePrompt() {
        AtomicReference<String> body = new AtomicReference<>();
        AiPlannerServiceImpl planner = new AiPlannerServiceImpl(enabledProperties(),
                capturingClient("{\"mode\":\"chat\",\"answer\":\"够用\",\"suggestions\":[]}", body),
                objectMapper);

        AiPlan plan = planner.revise("物料还够不够用", List.of(Map.of(
                "resource", "material",
                "filters", Map.of(),
                "summary", "查询物料，命中 1 条。",
                "rowCount", 1,
                "columns", List.of("物料编码"),
                "sampleRows", List.of(Map.of("materialCode", "LAMP-L200")))));

        assertNotNull(plan);
        assertEquals(AiPlan.MODE_CHAT, plan.mode());
        assertEquals("够用", plan.answer());
        String request = body.get();
        assertTrue(request.contains("已执行的查询与真实结果"), request);
        assertTrue(request.contains("material"), request);
        assertTrue(request.contains("LAMP-L200"), request);
        assertTrue(request.contains("查询物料，命中 1 条。"), request);
    }

    @Test
    void reviseReturnsFollowUpQueryPlan() {
        AiPlannerServiceImpl planner = new AiPlannerServiceImpl(enabledProperties(),
                capturingClient("{\"mode\":\"query\",\"capability\":\"data_query\","
                        + "\"params\":{\"resource\":\"supplier\",\"filters\":{\"keyword\":\"灯珠\"}},"
                        + "\"reason\":\"还缺供应商信息\"}", new AtomicReference<>()),
                objectMapper);

        AiPlan plan = planner.revise("灯珠谁供的", List.of(Map.of("resource", "material", "rowCount", 3)));

        assertNotNull(plan);
        assertEquals(AiPlan.MODE_QUERY, plan.mode());
        assertEquals("data_query", plan.capability());
        assertEquals("supplier", plan.params().get("resource"));
        assertTrue(String.valueOf(plan.params().get("filters")).contains("灯珠"));
    }

    @Test
    void reviseSkipsCallWhenThereIsNothingObserved() {
        AtomicReference<String> body = new AtomicReference<>();
        AiPlannerServiceImpl planner = new AiPlannerServiceImpl(enabledProperties(),
                capturingClient("{\"mode\":\"chat\",\"answer\":\"不该调用\"}", body), objectMapper);

        assertNull(planner.revise("物料还够不够用", List.of()));
        assertNull(planner.revise("物料还够不够用", null));
        assertNull(body.get(), "没有观测时不应该发起模型请求");
    }

    @Test
    void reviseTreatsUpstreamFailureAsNoInstruction() {
        AiPlannerServiceImpl planner = new AiPlannerServiceImpl(enabledProperties(),
                errorClient(500), objectMapper);

        assertNull(planner.revise("物料还够不够用", List.of(Map.of("resource", "material"))));
    }

    private AiProperties enabledProperties() {
        AiProperties properties = new AiProperties();
        properties.setEnabled(true);
        properties.setApiUrl("https://example.test/chat/completions");
        properties.setApiKey("test-api-key");
        properties.setModel("test-model");
        return properties;
    }

    private OkHttpClient capturingClient(String content, AtomicReference<String> body) {
        return new OkHttpClient.Builder().addInterceptor(chain -> {
            okio.Buffer buffer = new okio.Buffer();
            if (chain.request().body() != null) {
                chain.request().body().writeTo(buffer);
            }
            body.set(buffer.readUtf8());
            return new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create(upstream(content), JSON))
                    .build();
        }).build();
    }

    private OkHttpClient errorClient(int code) {
        return new OkHttpClient.Builder().addInterceptor(chain -> new Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("boom")
                .body(ResponseBody.create("{}", JSON))
                .build()).build();
    }

    private String upstream(String content) {
        var root = objectMapper.createObjectNode();
        root.put("model", "test-model");
        root.putArray("choices").addObject().putObject("message").put("content", content);
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
