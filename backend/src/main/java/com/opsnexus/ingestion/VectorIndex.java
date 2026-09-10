package com.opsnexus.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsnexus.resilience.AiProviderException;
import jakarta.annotation.PostConstruct;
import java.nio.file.*;
import java.util.*;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class VectorIndex {
    private static class Store extends SimpleVectorStore {
        Store(EmbeddingModel model) { super(SimpleVectorStore.builder(model)); }
        boolean has(String id) { return store.containsKey(id); }
    }
    private final Store store;
    private final Path file;
    private final DashScopeEmbedding model;
    private final double similarityThreshold;
    private final ObjectMapper json;
    private String error;
    public VectorIndex(DashScopeEmbedding model, ObjectMapper json,
            @Value("${ops.data-dir:./runtime}") String dir,
            @Value("${ops.similarity-threshold:0.45}") double similarityThreshold) {
        this.model = model; this.json = json; this.store = new Store(model);
        this.similarityThreshold = similarityThreshold;
        this.file = Path.of(dir).toAbsolutePath().normalize().resolve("vectors/store.json");
    }
    @PostConstruct void load() {
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file)) {
                json.readTree(file.toFile()); // Reject truncated JSON before loading.
                store.load(file.toFile());
            }
        } catch (Exception e) { error = "向量快照损坏或不可读，请恢复备份后重启"; }
    }
    public synchronized boolean has(String id) { return error == null && store.has(id); }
    public boolean configured() { return model.configured(); }
    public synchronized String error() { return error; }
    public String modelName() { return model.modelName(); }
    public synchronized void add(List<Document> docs) {
        ensureHealthy();
        var ids = docs.stream().map(Document::getId).toList();
        try {
            store.delete(ids);
            store.add(docs);
            persist();
        } catch (Exception e) {
            store.delete(ids);
            if (e instanceof AiProviderException providerError) {
                throw providerError;
            }
            throw new IllegalStateException(e.getMessage());
        }
    }
    public synchronized void delete(List<String> ids) {
        ensureHealthy(); store.delete(ids); persist();
    }
    public synchronized List<Document> search(String query, int topK) {
        ensureHealthy();
        if (!configured()) throw new IllegalStateException("未配置 DASHSCOPE_API_KEY，无法检索知识库");
        return store.similaritySearch(SearchRequest.builder().query(query).topK(topK).similarityThreshold(similarityThreshold).build());
    }
    private void ensureHealthy() { if (error != null) throw new IllegalStateException(error); }
    private void persist() {
        Path temporary = file.resolveSibling("store.pending.json");
        try {
            store.save(temporary.toFile());
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            error = "向量快照保存失败，请检查磁盘权限并重启后重试";
            throw new IllegalStateException(error);
        }
    }
}
