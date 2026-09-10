package com.opsnexus.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsnexus.resilience.AiProvider;
import com.opsnexus.resilience.AiProviderException;
import com.opsnexus.resilience.AiProviderFailure;
import com.opsnexus.resilience.AiResilienceProperties;
import com.opsnexus.resilience.ExternalAiResilience;
import com.opsnexus.security.PromptTrustBoundary;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DeepSeekChat {
    public record ConversationMessage(String role, String content) { }
    /** A provider tool request is untrusted model output and must be authorized by DiagnosisToolRegistry. */
    public record ToolDecision(String content, List<DiagnosisToolRegistry.ToolRequest> toolCalls) { }

    private final String key;
    private final String url;
    private final String model;
    private final ObjectMapper json;
    private final HttpClient http;
    private final Duration responseTimeout;
    private final ExternalAiResilience resilience;
    private final PromptTrustBoundary trustBoundary;

    public DeepSeekChat(@Value("${DEEPSEEK_API_KEY:}") String key,
            @Value("${ops.chat-url}") String url, @Value("${ops.chat-model}") String model,
            ObjectMapper json, AiResilienceProperties properties, ExternalAiResilience resilience,
            PromptTrustBoundary trustBoundary) {
        this.key = key;
        this.url = url;
        this.model = model;
        this.json = json;
        this.resilience = resilience;
        this.trustBoundary = trustBoundary;
        var settings = properties.getChat();
        this.http = HttpClient.newBuilder().connectTimeout(settings.getConnectTimeout()).build();
        this.responseTimeout = settings.getResponseTimeout();
    }

    public boolean configured() { return !key.isBlank(); }
    public String modelName() { return model; }

    public String complete(String system, String question) {
        return complete(system, List.of(), question, "complete");
    }

    /**
     * Runs a bounded, non-streaming completion with supplied data kept in separately labelled
     * untrusted contexts. Callers must still validate the returned text before using it.
     */
    public String complete(String system, List<PromptTrustBoundary.UntrustedContext> contexts,
            String question, String operation) {
        requireConfigured();
        return resilience.execute(AiProvider.DEEPSEEK_CHAT, operation, () -> {
            try {
                var body = Map.of("model", model, "stream", false, "temperature", 0, "max_tokens", 500,
                    "messages", streamingMessages(system, List.of(), contexts, question));
                var response = sendString(request(body));
                var content = json.readTree(response.body()).path("choices").path(0).path("message").path("content");
                if (!content.isTextual() || content.asText().isBlank()) {
                    throw new AiProviderException(AiProvider.DEEPSEEK_CHAT, AiProviderFailure.MALFORMED_RESPONSE);
                }
                return content.asText();
            } catch (AiProviderException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new AiProviderException(AiProvider.DEEPSEEK_CHAT, AiProviderFailure.MALFORMED_RESPONSE, exception);
            }
        });
    }

    /**
     * Runs a non-streaming tool-selection turn. Spring AI produces the supplied tool definitions while this
     * OpenAI-compatible client only transports the provider protocol; it never executes a requested tool.
     */
    public ToolDecision decideTools(String system, List<PromptTrustBoundary.UntrustedContext> contexts,
            String question, List<Map<String, Object>> tools) {
        requireConfigured();
        return resilience.execute(AiProvider.DEEPSEEK_CHAT, "tool_decision", () -> {
            try {
                var body = new java.util.LinkedHashMap<String, Object>();
                body.put("model", model);
                body.put("stream", false);
                body.put("temperature", 0);
                body.put("max_tokens", 500);
                body.put("messages", streamingMessages(system, List.of(), contexts, question));
                body.put("tools", tools);
                body.put("tool_choice", "auto");
                var response = sendString(request(body));
                var message = json.readTree(response.body()).path("choices").path(0).path("message");
                if (message.isMissingNode() || message.isNull()) {
                    throw new AiProviderException(AiProvider.DEEPSEEK_CHAT, AiProviderFailure.MALFORMED_RESPONSE);
                }
                var calls = new ArrayList<DiagnosisToolRegistry.ToolRequest>();
                for (var call : message.path("tool_calls")) {
                    calls.add(new DiagnosisToolRegistry.ToolRequest(call.path("id").asText(""),
                        call.path("function").path("name").asText(""),
                        call.path("function").path("arguments").asText("")));
                }
                String content = message.path("content").isTextual() ? message.path("content").asText() : "";
                if (calls.isEmpty() && content.isBlank()) {
                    throw new AiProviderException(AiProvider.DEEPSEEK_CHAT, AiProviderFailure.MALFORMED_RESPONSE);
                }
                return new ToolDecision(content, List.copyOf(calls));
            } catch (AiProviderException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new AiProviderException(AiProvider.DEEPSEEK_CHAT, AiProviderFailure.MALFORMED_RESPONSE, exception);
            }
        });
    }

    public void stream(String system, String question, Consumer<String> output) {
        stream(system, List.of(), question, output);
    }

    /** Provider-native SSE retries only its pre-token handshake; replaying emitted deltas would duplicate text. */
    public void stream(String system, List<ConversationMessage> history, String question, Consumer<String> output) {
        stream(system, history, List.of(), question, output);
    }

    /** Sends untrusted contexts as separately labelled user messages, never by appending them to policy. */
    public void stream(String system, List<ConversationMessage> history, List<PromptTrustBoundary.UntrustedContext> contexts,
            String question, Consumer<String> output) {
        requireConfigured();
        var messages = streamingMessages(system, history, contexts, question);
        HttpResponse<Stream<String>> response = resilience.execute(AiProvider.DEEPSEEK_CHAT, "stream_connect", () -> {
            try {
                return sendLines(request(Map.of("model", model, "stream", true, "temperature", 0.2, "max_tokens", 800, "messages", messages)));
            } catch (AiProviderException exception) {
                throw exception;
            } catch (Exception exception) {
                throw transportFailure(exception);
            }
        });
        try (Stream<String> lines = response.body()) {
            lines.forEach(line -> consumeLine(line, output));
        } catch (AiProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw resilience.recordStreamingFailure(AiProvider.DEEPSEEK_CHAT, "stream_body", exception);
        }
    }

    /** Package-visible for deterministic verification that data cannot be appended to trusted policy. */
    List<Map<String, String>> streamingMessages(String system, List<ConversationMessage> history,
            List<PromptTrustBoundary.UntrustedContext> contexts, String question) {
        var messages = new ArrayList<Map<String, String>>();
        messages.add(Map.of("role", "system", "content", system));
        for (var item : history) {
            if (("user".equals(item.role()) || "assistant".equals(item.role())) && !item.content().isBlank()) {
                messages.add(Map.of("role", item.role(), "content", trustBoundary.wrap(
                    new PromptTrustBoundary.UntrustedContext("conversation_" + item.role(), item.content()))));
            }
        }
        for (var context : contexts) {
            messages.add(Map.of("role", "user", "content", trustBoundary.wrap(context)));
        }
        messages.add(Map.of("role", "user", "content", trustBoundary.wrap(
            new PromptTrustBoundary.UntrustedContext("current_user_request", question))));
        return List.copyOf(messages);
    }

    private HttpRequest request(Object body) throws Exception {
        return HttpRequest.newBuilder(URI.create(url)).timeout(responseTimeout)
            .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
    }

    private HttpResponse<String> sendString(HttpRequest request) {
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            requireSuccess(response.statusCode());
            return response;
        } catch (AiProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw transportFailure(exception);
        }
    }

    private HttpResponse<Stream<String>> sendLines(HttpRequest request) {
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofLines());
            requireSuccess(response.statusCode());
            return response;
        } catch (AiProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw transportFailure(exception);
        }
    }

    private void consumeLine(String line, Consumer<String> output) {
        if (!line.startsWith("data:")) {
            return;
        }
        String data = line.substring(5).strip();
        if (data.equals("[DONE]") || data.isBlank()) {
            return;
        }
        try {
            var content = json.readTree(data).path("choices").path(0).path("delta").path("content");
            if (content.isTextual() && !content.asText().isEmpty()) {
                output.accept(content.asText());
            }
        } catch (Exception exception) {
            throw new AiProviderException(AiProvider.DEEPSEEK_CHAT, AiProviderFailure.MALFORMED_RESPONSE, exception);
        }
    }

    private void requireConfigured() {
        if (!configured()) {
            throw new AiProviderException(AiProvider.DEEPSEEK_CHAT, AiProviderFailure.CONFIGURATION);
        }
    }

    private void requireSuccess(int statusCode) {
        if (statusCode != 200) {
            throw resilience.httpFailure(AiProvider.DEEPSEEK_CHAT, statusCode);
        }
    }

    private AiProviderException transportFailure(Exception exception) {
        if (exception instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return new AiProviderException(AiProvider.DEEPSEEK_CHAT, AiProviderFailure.CANCELLED, exception);
        }
        if (exception instanceof HttpTimeoutException) {
            return new AiProviderException(AiProvider.DEEPSEEK_CHAT, AiProviderFailure.TIMEOUT, exception);
        }
        return new AiProviderException(AiProvider.DEEPSEEK_CHAT, AiProviderFailure.UNAVAILABLE, exception);
    }
}
