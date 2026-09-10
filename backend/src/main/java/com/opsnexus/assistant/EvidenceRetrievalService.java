package com.opsnexus.assistant;

import com.opsnexus.ingestion.VectorIndex;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import com.opsnexus.resilience.AiProviderException;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Executes the production RAG retrieval path shared by answering and gap revalidation.
 * Keeping filtering here prevents a governance check from accidentally using unpublished
 * versions or a different VectorIndex threshold.
 */
@Service
public class EvidenceRetrievalService {
    private final VectorIndex vectors;
    private final JdbcTemplate db;

    public EvidenceRetrievalService(VectorIndex vectors, JdbcTemplate db) {
        this.vectors = vectors;
        this.db = db;
    }

    public List<AssistantService.Evidence> retrieve(long knowledgeBaseId, String question) {
        List<Document> candidates;
        try {
            candidates = vectors.search(question, 8);
        } catch (AiProviderException e) {
            throw new com.opsnexus.knowledge.KnowledgeException(503, e.errorCode(), e.getMessage());
        } catch (Exception e) {
            throw new com.opsnexus.knowledge.KnowledgeException(503, "VECTOR_UNAVAILABLE", e.getMessage());
        }
        var result = new ArrayList<AssistantService.Evidence>();
        Set<String> seen = new HashSet<>();
        for (var document : candidates) {
            Object rawVersionId = document.getMetadata().get("versionId");
            if (!(rawVersionId instanceof Number number)) {
                continue;
            }
            long versionId = number.longValue();
            var rows = db.queryForList("""
                SELECT v.version_no,d.title FROM document_version v
                JOIN knowledge_document d ON d.id=v.document_id JOIN knowledge_base k ON k.id=d.kb_id
                WHERE v.id=? AND d.kb_id=? AND d.current_version_id=v.id AND v.publish_status='PUBLISHED'
                AND v.process_status='READY' AND k.status='ACTIVE'
                """, versionId, knowledgeBaseId);
            if (rows.isEmpty() || !seen.add(document.getId())) {
                continue;
            }
            var row = rows.getFirst();
            Integer page = document.getMetadata().get("pageNumber") instanceof Number value ? value.intValue() : null;
            String text = Objects.requireNonNullElse(document.getText(), "");
            String quote = text.length() > 900 ? text.substring(0, 900) + "…" : text;
            result.add(new AssistantService.Evidence(versionId, document.getId(), row.get("TITLE").toString(),
                row.get("VERSION_NO").toString(), page, quote, Objects.requireNonNullElse(document.getScore(), 0.0)));
            if (result.size() == 4) {
                break;
            }
        }
        return result;
    }
}
