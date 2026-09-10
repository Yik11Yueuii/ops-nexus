package com.opsnexus.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsnexus.resilience.AiProvider;
import com.opsnexus.resilience.AiProviderException;
import com.opsnexus.resilience.AiProviderFailure;
import com.opsnexus.resilience.AiResilienceProperties;
import com.opsnexus.resilience.ExternalAiResilience;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class DashScopeEmbedding implements EmbeddingModel {
    private final String key;
    private final String endpoint;
    private final String model;
    private final ObjectMapper json;
    private final HttpClient http;
    private final Duration responseTimeout;
    private final ExternalAiResilience resilience;

    /** Retained for existing offline evaluation tests; production uses the configured Spring constructor below. */
    public DashScopeEmbedding(String key, String endpoint, String model, ObjectMapper json) {
        AiResilienceProperties properties = new AiResilienceProperties();
        this.key = key;
        this.endpoint = endpoint;
        this.model = model;
        this.json = json;
        var settings = properties.getEmbedding();
        this.http = HttpClient.newBuilder().connectTimeout(settings.getConnectTimeout()).build();
        this.responseTimeout = settings.getResponseTimeout();
        this.resilience = new ExternalAiResilience(properties, new com.opsnexus.resilience.AiProviderAuditService());
    }

    @Autowired
    public DashScopeEmbedding(@Value("${DASHSCOPE_API_KEY:}") String key,
            @Value("${ops.embedding-url}") String endpoint, @Value("${ops.embedding-model}") String model,
            ObjectMapper json, AiResilienceProperties properties, ExternalAiResilience resilience) {
        this.key = key;
        this.endpoint = endpoint;
        this.model = model;
        this.json = json;
        this.resilience = resilience;
        var settings = properties.getEmbedding();
        this.http = HttpClient.newBuilder().connectTimeout(settings.getConnectTimeout()).build();
        this.responseTimeout = settings.getResponseTimeout();
    }

    public boolean configured() { return !key.isBlank(); }
    public String modelName() { return model; }

    @Override public float[] embed(Document document) { return embed(document.getText()); }

    @Override
    public EmbeddingResponse call(EmbeddingRequest input) {
        requireConfigured();
        try {
            var vectors = new ArrayList<Embedding>();
            var texts = input.getInstructions();
            for (int offset = 0; offset < texts.size(); offset += 25) {
                var batch = texts.subList(offset, Math.min(offset + 25, texts.size()));
                int batchOffset = offset;
                vectors.addAll(resilience.execute(AiProvider.DASHSCOPE_EMBEDDING, "embed", () -> embedBatch(batch, batchOffset)));
            }
            return new EmbeddingResponse(vectors);
        } catch (AiProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AiProviderException(AiProvider.DASHSCOPE_EMBEDDING, AiProviderFailure.MALFORMED_RESPONSE, exception);
        }
    }

    private ArrayList<Embedding> embedBatch(java.util.List<String> batch, int offset) {
        try {
            String body = json.writeValueAsString(Map.of("model", model, "input", Map.of("texts", batch),
                "parameters", Map.of("text_type", "document")));
            var request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(responseTimeout)
                .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw resilience.httpFailure(AiProvider.DASHSCOPE_EMBEDDING, response.statusCode());
            }
            var rows = json.readTree(response.body()).path("output").path("embeddings");
            if (!rows.isArray() || rows.size() != batch.size()) {
                throw new AiProviderException(AiProvider.DASHSCOPE_EMBEDDING, AiProviderFailure.MALFORMED_RESPONSE);
            }
            float[][] ordered = new float[batch.size()][];
            for (var row : rows) {
                int index = row.path("text_index").asInt(-1);
                var values = row.path("embedding");
                if (index < 0 || index >= ordered.length || ordered[index] != null || values.isEmpty()) {
                    throw new AiProviderException(AiProvider.DASHSCOPE_EMBEDDING, AiProviderFailure.MALFORMED_RESPONSE);
                }
                float[] vector = new float[values.size()];
                for (int i = 0; i < vector.length; i++) {
                    vector[i] = (float) values.get(i).asDouble();
                    if (!Float.isFinite(vector[i])) {
                        throw new AiProviderException(AiProvider.DASHSCOPE_EMBEDDING, AiProviderFailure.MALFORMED_RESPONSE);
                    }
                }
                ordered[index] = vector;
            }
            var embeddings = new ArrayList<Embedding>();
            for (int i = 0; i < ordered.length; i++) {
                if (ordered[i] == null) {
                    throw new AiProviderException(AiProvider.DASHSCOPE_EMBEDDING, AiProviderFailure.MALFORMED_RESPONSE);
                }
                embeddings.add(new Embedding(ordered[i], offset + i));
            }
            return embeddings;
        } catch (AiProviderException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AiProviderException(AiProvider.DASHSCOPE_EMBEDDING, AiProviderFailure.CANCELLED, exception);
        } catch (HttpTimeoutException exception) {
            throw new AiProviderException(AiProvider.DASHSCOPE_EMBEDDING, AiProviderFailure.TIMEOUT, exception);
        } catch (Exception exception) {
            throw new AiProviderException(AiProvider.DASHSCOPE_EMBEDDING, AiProviderFailure.UNAVAILABLE, exception);
        }
    }

    private void requireConfigured() {
        if (!configured()) {
            throw new AiProviderException(AiProvider.DASHSCOPE_EMBEDDING, AiProviderFailure.CONFIGURATION);
        }
    }
}
