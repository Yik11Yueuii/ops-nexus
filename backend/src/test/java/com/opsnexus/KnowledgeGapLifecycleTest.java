package com.opsnexus;

import com.opsnexus.assistant.AssistantService;
import com.opsnexus.assistant.DeepSeekChat;
import com.opsnexus.governance.KnowledgeGapService;
import com.opsnexus.ingestion.VectorIndex;
import com.opsnexus.knowledge.KnowledgeException;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:gap-lifecycle-test;DB_CLOSE_DELAY=-1","spring.data.redis.port=1","spring.data.redis.connect-timeout=100ms","spring.data.redis.timeout=100ms","ops.seed-samples=false","ops.data-dir=./target/gap-lifecycle-test-runtime"})
class KnowledgeGapLifecycleTest {
    @Autowired KnowledgeGapService gaps;
    @Autowired AssistantService assistant;
    @Autowired JdbcTemplate db;
    @MockitoBean VectorIndex vectors;
    @MockitoBean DeepSeekChat chat;

    @BeforeEach
    void setup() {
        db.update("DELETE FROM gap_verification");
        db.update("DELETE FROM gap_occurrence");
        db.update("DELETE FROM message_feedback");
        db.update("DELETE FROM message_citation");
        db.update("DELETE FROM chat_message");
        db.update("DELETE FROM conversation");
        db.update("DELETE FROM knowledge_gap");
        db.update("DELETE FROM document_chunk_ref");
        db.update("UPDATE knowledge_document SET current_version_id=NULL");
        db.update("DELETE FROM document_version");
        db.update("DELETE FROM knowledge_document");
        db.update("DELETE FROM knowledge_base");
        db.update("INSERT INTO knowledge_base(id,name,created_by) VALUES(100,'测试知识库',1)");
        db.update("INSERT INTO knowledge_document(id,kb_id,title,current_version_id,created_by) VALUES(200,100,'运行手册',302,1)");
        db.update("INSERT INTO document_version(id,document_id,version_no,original_name,file_type,file_size,checksum,storage_path,process_status,publish_status) VALUES(302,200,'v2','new.md','MD',1,'b','b','READY','PUBLISHED')");
        when(vectors.configured()).thenReturn(true);
        when(chat.configured()).thenReturn(true);
    }

    @Test
    void noEvidenceCreatesTraceableGapAndOccurrence() {
        when(vectors.search(anyString(), eq(8))).thenReturn(List.of());
        var answer = assistant.answer(1, null, 100, "未知组件如何升级？", ignored -> {});
        var gap = db.queryForMap("SELECT source_type,priority,occurrence_count,status FROM knowledge_gap");
        assertEquals("NO_EVIDENCE", gap.get("SOURCE_TYPE"));
        assertEquals("HIGH", gap.get("PRIORITY"));
        assertEquals("OPEN", gap.get("STATUS"));
        assertEquals(1, ((Number) gap.get("OCCURRENCE_COUNT")).intValue());
        var occurrence = db.queryForMap("SELECT conversation_id,question_message_id,assistant_message_id,question,source_type FROM gap_occurrence");
        assertEquals(answer.conversationId(), ((Number) occurrence.get("CONVERSATION_ID")).longValue());
        assertEquals("未知组件如何升级？", occurrence.get("QUESTION"));
        assertEquals("NO_EVIDENCE", occurrence.get("SOURCE_TYPE"));
    }

    @Test
    void lowConfidenceSourceAndSameQuestionAggregateDeterministically() {
        gaps.record(new KnowledgeGapService.Occurrence(100, null, null, null, null, "如何配置未知组件？", "LOW_CONFIDENCE", "当前可信等级为 LOW"));
        gaps.record(new KnowledgeGapService.Occurrence(100, null, null, null, null, "  如何配置未知组件？ ", "LOW_CONFIDENCE", "当前可信等级为 LOW"));
        var gap = db.queryForMap("SELECT source_type,priority,occurrence_count FROM knowledge_gap");
        assertEquals("LOW_CONFIDENCE", gap.get("SOURCE_TYPE"));
        assertEquals("MEDIUM", gap.get("PRIORITY"));
        assertEquals(2, ((Number) gap.get("OCCURRENCE_COUNT")).intValue());
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM gap_occurrence", Integer.class));
    }

    @Test
    void downvoteCreatesOneOccurrenceEvenWhenFeedbackIsChangedRepeatedly() {
        stubAnswerWithEvidence();
        var answer = assistant.answer(1, null, 100, "发布顺序是什么？", ignored -> {});
        assistant.feedback(1, answer.messageId(), "DOWN", null);
        assistant.feedback(1, answer.messageId(), "DOWN", "仍然不够明确");
        assistant.feedback(1, answer.messageId(), "UP", null);
        assistant.feedback(1, answer.messageId(), "DOWN", null);
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM knowledge_gap WHERE source_type='USER_DOWNVOTE'", Integer.class));
        assertEquals(1, db.queryForObject("SELECT occurrence_count FROM knowledge_gap", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM gap_occurrence WHERE source_type='USER_DOWNVOTE'", Integer.class));
    }

    @Test
    void processingRevalidateAndVerifiedResolutionKeepCountAndHistory() {
        long id = newGap("连接池如何设置？");
        assertThrows(KnowledgeException.class, () -> gaps.revalidate(id, 9, null));
        gaps.startProcessing(id, 9, "等待管理员补充运行手册");
        assertThrows(KnowledgeException.class, () -> gaps.startProcessing(id, 9, "重复处理"));
        when(vectors.search(anyString(), eq(8))).thenReturn(List.of(currentEvidence()));
        var result = gaps.revalidate(id, 9, "发布 v2 后复测");
        assertEquals(true, result.get("evidenceSufficient"));
        assertEquals("PROCESSING", db.queryForObject("SELECT status FROM knowledge_gap WHERE id=?", String.class, id));
        assertEquals(1, db.queryForObject("SELECT occurrence_count FROM knowledge_gap WHERE id=?", Integer.class, id));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM gap_verification WHERE gap_id=?", Integer.class, id));
        gaps.resolve(id, 9, "VERIFIED_RESOLUTION", "新发布版本已提供可追溯依据");
        assertEquals("RESOLVED", db.queryForObject("SELECT status FROM knowledge_gap WHERE id=?", String.class, id));
        var detail = gaps.detail(id);
        assertEquals(1, ((List<?>) detail.get("occurrences")).size());
        assertEquals(1, ((List<?>) detail.get("verifications")).size());
    }

    @Test
    void noHitDoesNotResolveAndManualCloseRequiresReason() {
        long id = newGap("没有依据的问题？");
        gaps.startProcessing(id, 9, "待处理");
        when(vectors.search(anyString(), eq(8))).thenReturn(List.of());
        var result = gaps.revalidate(id, 9, null);
        assertEquals(false, result.get("evidenceSufficient"));
        assertThrows(KnowledgeException.class, () -> gaps.resolve(id, 9, "VERIFIED_RESOLUTION", "误判为已解决"));
        assertThrows(KnowledgeException.class, () -> gaps.resolve(id, 9, "MANUAL_RESOLUTION", ""));
        gaps.resolve(id, 9, "MANUAL_RESOLUTION", "业务确认该问题不在本知识库范围");
        assertEquals("MANUAL_RESOLUTION", db.queryForObject("SELECT resolution_type FROM knowledge_gap WHERE id=?", String.class, id));
    }

    @Test
    void resolvedGapReopensWhenSameFailureRecurs() {
        long id = newGap("历史缺口会复发吗？");
        gaps.startProcessing(id, 9, "处理");
        when(vectors.search(anyString(), eq(8))).thenReturn(List.of(currentEvidence()));
        gaps.revalidate(id, 9, "此前已验证");
        gaps.resolve(id, 9, "VERIFIED_RESOLUTION", "此前验证通过");
        gaps.record(new KnowledgeGapService.Occurrence(100, null, null, null, null, "历史缺口会复发吗？", "NO_EVIDENCE", "再次没有证据"));
        var row = db.queryForMap("SELECT status,occurrence_count,resolved_at,resolution_note,last_verification_result FROM knowledge_gap WHERE id=?", id);
        assertEquals("OPEN", row.get("STATUS"));
        assertEquals(2, ((Number) row.get("OCCURRENCE_COUNT")).intValue());
        assertNull(row.get("RESOLVED_AT"));
        assertNull(row.get("RESOLUTION_NOTE"));
        assertNull(row.get("LAST_VERIFICATION_RESULT"));
    }

    private long newGap(String question) {
        gaps.record(new KnowledgeGapService.Occurrence(100, null, null, null, null, question, "NO_EVIDENCE", "未命中"));
        return db.queryForObject("SELECT id FROM knowledge_gap", Long.class);
    }

    private Document currentEvidence() {
        return Document.builder().id("current").text("当前发布版本要求先灰度再全量").metadata("versionId", 302L).score(.81).build();
    }

    private void stubAnswerWithEvidence() {
        when(vectors.search(anyString(), eq(8))).thenReturn(List.of(currentEvidence()));
        doAnswer(invocation -> { Consumer<String> output = invocation.getArgument(4); output.accept("应先灰度发布。"); return null; }).when(chat).stream(anyString(), anyList(), anyList(), anyString(), any());
    }
}
