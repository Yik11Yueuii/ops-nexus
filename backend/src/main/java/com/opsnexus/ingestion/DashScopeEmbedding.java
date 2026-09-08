package com.opsnexus.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.*;
import org.springframework.stereotype.Component;

@Component
public class DashScopeEmbedding implements EmbeddingModel {
    private final String key;
    private final String endpoint;
    private final String model;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public DashScopeEmbedding(@Value("${DASHSCOPE_API_KEY:}") String key,
            @Value("${ops.embedding-url:https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding}") String endpoint,
            @Value("${ops.embedding-model:text-embedding-v2}") String model, ObjectMapper json) {
        this.key = key; this.endpoint = endpoint; this.model = model; this.json = json;
    }
    public boolean configured() { return !key.isBlank(); }
    public String modelName() { return model; }
    @Override public float[] embed(Document doc) { return embed(doc.getText()); }
    @Override public EmbeddingResponse call(EmbeddingRequest input) {
        if (!configured()) throw new IllegalStateException("未配置 DASHSCOPE_API_KEY；设置后重启后端再重试");
        try {
            var vectors = new ArrayList<Embedding>();
            var texts = input.getInstructions();
            for (int offset = 0; offset < texts.size(); offset += 25) {
                var batch = texts.subList(offset, Math.min(offset + 25, texts.size()));
                String body = json.writeValueAsString(Map.of("model", model, "input", Map.of("texts", batch),
                    "parameters", Map.of("text_type", "document")));
                var req = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                var response = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200)
                    throw new IllegalStateException("向量服务请求失败（HTTP " + response.statusCode() + "），请检查密钥、地域和模型配置");
                var rows = json.readTree(response.body()).path("output").path("embeddings");
                if (!rows.isArray() || rows.size() != batch.size()) throw new IllegalStateException("向量服务返回数量不匹配");
                float[][] ordered = new float[batch.size()][];
                for (var row : rows) {
                    int index = row.path("text_index").asInt(-1);
                    var values = row.path("embedding");
                    if (index < 0 || index >= ordered.length || ordered[index] != null || values.isEmpty())
                        throw new IllegalStateException("向量服务返回结构不正确");
                    float[] v = new float[values.size()];
                    for (int i = 0; i < v.length; i++) {
                        v[i] = (float) values.get(i).asDouble();
                        if (!Float.isFinite(v[i])) throw new IllegalStateException("向量数据无效");
                    }
                    ordered[index] = v;
                }
                for (int i = 0; i < ordered.length; i++) {
                    if (ordered[i] == null) throw new IllegalStateException("向量数据缺失");
                    vectors.add(new Embedding(ordered[i], offset + i));
                }
            }
            return new EmbeddingResponse(vectors);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("向量化已中断");
        } catch (IllegalStateException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("向量服务连接或响应异常，请检查网络与模型配置"); }
    }
}
