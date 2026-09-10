package com.opsnexus.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opsnexus.knowledge.KnowledgeException;
import com.opsnexus.observability.OpsNexusMetrics;
import com.opsnexus.security.PromptTrustBoundary;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Component;

/**
 * Explicit Spring AI tool allowlist and the backend execution boundary. The model can request a callback by
 * its Spring AI definition, but cannot bypass this class's authorization, argument validation or limits.
 */
@Component
public class DiagnosisToolRegistry {
    private static final Pattern SERVICE = Pattern.compile("[a-z][a-z0-9-]{1,99}");
    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final int MAX_ROWS = 3;
    private static final int MAX_FIELD_CHARS = 120;
    private static final int MAX_RESULT_CHARS = 2_000;

    public record ToolRequest(String id, String name, String arguments) { }
    public record ToolResult(String toolName, String summary, String safePayload, boolean truncated) { }
    private record Registered(ToolCallback callback, boolean adminOnly) { }

    private final ObjectMapper json;
    private final DiagnosisToolFunctions tools;
    private final DiagnosisToolAuditService audit;
    private final PromptTrustBoundary trustBoundary;
    private final OpsNexusMetrics metrics;
    private final Map<String, Registered> registered = new LinkedHashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    public DiagnosisToolRegistry(ObjectMapper json, DiagnosisToolFunctions tools, DiagnosisToolAuditService audit,
            PromptTrustBoundary trustBoundary, OpsNexusMetrics metrics) {
        this.json = json;
        this.tools = tools;
        this.audit = audit;
        this.trustBoundary = trustBoundary;
        this.metrics = metrics;
        for (ToolCallback callback : MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks()) {
            String name = callback.getToolDefinition().name();
            registered.put(name, new Registered(callback, "lookup_recent_incidents".equals(name)));
        }
    }

    public List<Map<String, Object>> specifications() {
        var output = new ArrayList<Map<String, Object>>();
        registered.values().forEach(item -> {
            ToolDefinition definition = item.callback().getToolDefinition();
            try {
                JsonNode parameters = json.readTree(definition.inputSchema());
                output.add(Map.of("type", "function", "function", Map.of("name", definition.name(),
                    "description", definition.description(), "parameters", parameters)));
            } catch (Exception exception) {
                throw new IllegalStateException("Spring AI generated an invalid tool schema", exception);
            }
        });
        return List.copyOf(output);
    }

    public void requireKnownService(String service) {
        if (service == null || !SERVICE.matcher(service).matches() || !tools.exists(service)) {
            throw new KnowledgeException(404, "NOT_FOUND", "服务不存在");
        }
    }

    public ToolResult execute(long userId, boolean admin, String diagnosisRequestId, ToolRequest request) {
        long started = System.nanoTime();
        String name = safeName(request == null ? null : request.name());
        Registered item = registered.get(name);
        if (item == null) {
            record(userId, diagnosisRequestId, name, false, false, "TOOL_VALIDATION_FAILED", started, 0);
            throw new KnowledgeException(400, "TOOL_VALIDATION_FAILED", "请求了未注册的诊断工具");
        }
        if (item.adminOnly() && !admin) {
            record(userId, diagnosisRequestId, name, false, false, "TOOL_ACCESS_DENIED", started, 0);
            throw new KnowledgeException(403, "TOOL_ACCESS_DENIED", "当前用户没有该诊断工具权限");
        }
        String safeArguments;
        try {
            safeArguments = validateArguments(request.arguments());
        } catch (KnowledgeException exception) {
            record(userId, diagnosisRequestId, name, true, false, "TOOL_VALIDATION_FAILED", started, 0);
            throw exception;
        }
        Future<String> future = workers.submit(() -> item.callback().call(safeArguments));
        try {
            String raw = future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            ToolResult result = bound(name, raw);
            record(userId, diagnosisRequestId, name, true, true, null, started, result.safePayload().length());
            return result;
        } catch (TimeoutException exception) {
            future.cancel(true);
            record(userId, diagnosisRequestId, name, true, false, "TOOL_TIMEOUT", started, 0);
            throw new KnowledgeException(504, "TOOL_TIMEOUT", "诊断工具执行超时");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            record(userId, diagnosisRequestId, name, true, false, "TOOL_EXECUTION_FAILED", started, 0);
            throw new KnowledgeException(503, "TOOL_EXECUTION_FAILED", "诊断工具执行中断");
        } catch (ExecutionException | RuntimeException exception) {
            record(userId, diagnosisRequestId, name, true, false, "TOOL_EXECUTION_FAILED", started, 0);
            throw new KnowledgeException(503, "TOOL_EXECUTION_FAILED", "诊断工具执行失败");
        }
    }

    private String validateArguments(String raw) {
        try {
            JsonNode node = json.readTree(raw);
            JsonNode input = node == null ? null : node.path("input");
            if (node == null || !node.isObject() || node.size() != 1 || !input.isObject() || input.size() != 1
                    || !input.path("service").isTextual()) {
                throw invalid();
            }
            String service = input.path("service").asText();
            if (!SERVICE.matcher(service).matches() || !tools.exists(service)) {
                throw invalid();
            }
            // MethodToolCallback binds JSON by Java method parameter name; its sole parameter is the typed DTO `input`.
            return json.writeValueAsString(Map.of("input", Map.of("service", service)));
        } catch (KnowledgeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private ToolResult bound(String toolName, String raw) {
        try {
            JsonNode data = json.readTree(raw);
            Truncation state = new Truncation();
            JsonNode bounded = boundNode(data, state);
            ObjectNode envelope = json.createObjectNode();
            envelope.put("toolName", toolName);
            envelope.put("success", true);
            envelope.put("truncated", state.value);
            envelope.put("summary", toolName + " returned bounded read-only data.");
            envelope.set("data", bounded);
            String payload = trustBoundary.redactSecrets(json.writeValueAsString(envelope));
            if (payload.length() > MAX_RESULT_CHARS) {
                state.value = true;
                envelope.removeAll();
                envelope.put("toolName", toolName);
                envelope.put("success", true);
                envelope.put("truncated", true);
                envelope.put("summary", toolName + " returned data exceeding the safe result budget.");
                envelope.put("data", "Result omitted after deterministic size truncation.");
                payload = trustBoundary.redactSecrets(json.writeValueAsString(envelope));
            }
            return new ToolResult(toolName, toolName + " completed", payload, state.value);
        } catch (Exception exception) {
            throw new IllegalStateException("Tool callback returned an invalid result", exception);
        }
    }

    private JsonNode boundNode(JsonNode node, Truncation state) {
        if (node.isTextual()) {
            String value = node.asText();
            if (value.length() > MAX_FIELD_CHARS) {
                state.value = true;
                return json.getNodeFactory().textNode(value.substring(0, MAX_FIELD_CHARS) + "…");
            }
            return node;
        }
        if (node.isArray()) {
            var array = json.createArrayNode();
            for (int index = 0; index < Math.min(node.size(), MAX_ROWS); index++) {
                array.add(boundNode(node.get(index), state));
            }
            if (node.size() > MAX_ROWS) state.value = true;
            return array;
        }
        if (node.isObject()) {
            var object = json.createObjectNode();
            node.fields().forEachRemaining(entry -> object.set(entry.getKey(), boundNode(entry.getValue(), state)));
            return object;
        }
        return node;
    }

    private KnowledgeException invalid() {
        return new KnowledgeException(400, "TOOL_VALIDATION_FAILED", "诊断工具参数无效");
    }

    private String safeName(String name) {
        return name == null ? "unknown" : name.replaceAll("[^a-z0-9_-]", "_").substring(0, Math.min(name.length(), 80));
    }

    private void record(long userId, String requestId, String name, boolean authorized, boolean success,
            String failure, long started, int resultSize) {
        audit.record(userId, requestId, name, authorized, success, failure,
            (System.nanoTime() - started) / 1_000_000, resultSize);
        metrics.tool(name, authorized ? "EXECUTED" : "DENIED", success, failure, (System.nanoTime() - started) / 1_000_000);
    }

    private static final class Truncation { boolean value; }
}
