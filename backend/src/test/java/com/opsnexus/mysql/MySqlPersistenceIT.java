package com.opsnexus.mysql;

import com.opsnexus.analytics.AnalyticsService;
import com.opsnexus.assistant.AssistantService;
import com.opsnexus.assistant.BusinessToolService;
import com.opsnexus.assistant.DeepSeekChat;
import com.opsnexus.governance.KnowledgeGapService;
import com.opsnexus.ingestion.VectorIndex;
import com.opsnexus.knowledge.KnowledgeService;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Explicit Docker-backed verification for the MySQL persistence profile; excluded from mvn test. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(properties = {
    "spring.profiles.active=mysql", "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:schema-mysql.sql", "ops.seed-samples=false",
    "spring.data.redis.port=1", "spring.data.redis.connect-timeout=100ms", "spring.data.redis.timeout=100ms",
    "ops.data-dir=./target/mysql-it-runtime"
})
class MySqlPersistenceIT {
    private static final String ANALYTICS_USER = "opsnexus_analytics";
    private static final String ANALYTICS_PASSWORD = "opsnexus_test_only";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("opsnexus").withUsername("opsnexus").withPassword("opsnexus_test_only");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        // Spring may resolve dynamic properties before the JUnit extension starts @Container.
        if (!MYSQL.isRunning()) {
            MYSQL.start();
        }
        provisionAnalyticsUser();
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("ops.analytics-url", MYSQL::getJdbcUrl);
        registry.add("ops.analytics-username", () -> ANALYTICS_USER);
        registry.add("ops.analytics-password", () -> ANALYTICS_PASSWORD);
    }

    private static void provisionAnalyticsUser() {
        try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("SET GLOBAL log_bin_trust_function_creators = 1");
            statement.execute("CREATE USER IF NOT EXISTS 'opsnexus_analytics'@'%' IDENTIFIED BY 'opsnexus_test_only'");
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to provision MySQL integration-test analytics user", exception);
        }
    }
    @Autowired JdbcTemplate db;
    @Autowired KnowledgeService knowledge;
    @Autowired KnowledgeGapService gaps;
    @Autowired AssistantService assistant;
    @Autowired BusinessToolService tools;
    @Autowired AnalyticsService analytics;
    @MockitoBean VectorIndex vectors;
    @MockitoBean DeepSeekChat chat;

    @BeforeEach
    void reset() throws Exception {
        grantAnalyticsSelect();
        db.update("DELETE FROM gap_verification");
        db.update("DELETE FROM gap_occurrence");
        db.update("DELETE FROM message_feedback");
        db.update("DELETE FROM message_citation");
        db.update("DELETE FROM chat_message");
        db.update("DELETE FROM conversation");
        db.update("DELETE FROM document_chunk_ref");
        db.update("DELETE FROM document_version");
        db.update("DELETE FROM knowledge_document");
        db.update("DELETE FROM knowledge_gap");
        db.update("DELETE FROM knowledge_base");
        db.update("DELETE FROM sql_query_audit");
        db.update("DELETE FROM tool_call_audit");
        db.update("DELETE FROM ai_call_log");
        when(vectors.has(anyString())).thenReturn(true);
        when(vectors.search(anyString(), org.mockito.ArgumentMatchers.eq(8))).thenReturn(List.of());
        when(chat.complete(anyString(), anyString())).thenReturn("SELECT service_name,current_version FROM service_catalog");
        when(chat.modelName()).thenReturn("mysql-it-mock");
    }

    @Test
    void authenticationUsersDocumentsAndPublishTransactionUseMySql() {
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM app_user WHERE username IN ('admin','user')", Integer.class));
        long kb = insert("INSERT INTO knowledge_base(name,created_by) VALUES(?,?)", "mysql-doc-base", 1);
        long document = insert("INSERT INTO knowledge_document(kb_id,title,created_by) VALUES(?,?,?)", kb, "MySQL 发布手册", 1);
        long v1 = version(document, "v1");
        long v2 = version(document, "v2");

        knowledge.publish(v1);
        assertEquals("PUBLISHED", value("SELECT publish_status FROM document_version WHERE id=?", v1));
        assertEquals(v1, db.queryForObject("SELECT current_version_id FROM knowledge_document WHERE id=?", Long.class, document));

        db.execute("CREATE TRIGGER mysql_publish_failure BEFORE UPDATE ON knowledge_document FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced publish rollback'");
        try {
            assertThrows(RuntimeException.class, () -> knowledge.publish(v2));
        } finally {
            db.execute("DROP TRIGGER mysql_publish_failure");
        }
        assertEquals("PUBLISHED", value("SELECT publish_status FROM document_version WHERE id=?", v1));
        assertEquals("DRAFT", value("SELECT publish_status FROM document_version WHERE id=?", v2));

        knowledge.publish(v2);
        assertEquals("ARCHIVED", value("SELECT publish_status FROM document_version WHERE id=?", v1));
        assertEquals("PUBLISHED", value("SELECT publish_status FROM document_version WHERE id=?", v2));
        assertEquals(v2, db.queryForObject("SELECT current_version_id FROM knowledge_document WHERE id=?", Long.class, document));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM document_version WHERE document_id=? AND publish_status='PUBLISHED'", Integer.class, document));
    }

    @Test
    void conversationFeedbackGapLifecycleAndAuditsUseRealConstraints() {
        long kb = insert("INSERT INTO knowledge_base(name,created_by) VALUES(?,?)", "mysql-gap-base", 1);
        long conversation = insert("INSERT INTO conversation(user_id,title) VALUES(?,?)", 1, "MySQL conversation");
        long question = insert("INSERT INTO chat_message(conversation_id,role,content,kb_id) VALUES(?,'USER',?,?)", conversation, "缺少 MySQL SOP 吗？", kb);
        long answer = insert("INSERT INTO chat_message(conversation_id,role,content,kb_id,confidence_level,evidence_sufficiency) VALUES(?,'ASSISTANT',?,?, 'LOW','INSUFFICIENT')", conversation, "暂无证据", kb);

        assistant.feedback(1, answer, "DOWN", "请补充 SOP");
        assistant.feedback(1, answer, "DOWN", "重复提交不应新建");
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM message_feedback WHERE message_id=? AND user_id=1", Integer.class, answer));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM gap_occurrence", Integer.class));
        long gap = db.queryForObject("SELECT id FROM knowledge_gap", Long.class);
        gaps.startProcessing(gap, 1, "管理员核验 MySQL 文档");
        assertEquals("NO_EVIDENCE", gaps.revalidate(gap, 1, "真实向量检索为空").get("result"));
        gaps.resolve(gap, 1, "MANUAL_RESOLUTION", "确认在当前知识库范围外");
        gaps.record(new KnowledgeGapService.Occurrence(kb, conversation, question, answer, null, "缺少 MySQL SOP 吗？", "NO_EVIDENCE", "再次未命中"));
        assertEquals("OPEN", value("SELECT status FROM knowledge_gap WHERE id=?", gap));
        assertEquals(2, db.queryForObject("SELECT occurrence_count FROM knowledge_gap WHERE id=?", Integer.class, gap));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM gap_verification WHERE gap_id=?", Integer.class, gap));

        tools.query(1, "order-service 最近有哪些发布记录？");
        analytics.query(1, "查询服务当前版本");
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM tool_call_audit", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM sql_query_audit WHERE execution_status='SUCCESS'", Integer.class));
    }

    @Test
    void analyticsAccountCanSelectButDatabaseDeniesWritesAndUnauthorizedTables() throws Exception {
        try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), ANALYTICS_USER, ANALYTICS_PASSWORD);
             Statement statement = connection.createStatement()) {
            assertTrue(statement.executeQuery("SELECT service_name FROM service_catalog").next());
            assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE service_catalog SET runtime_status='BAD'"));
            assertThrows(SQLException.class, () -> statement.executeUpdate("DELETE FROM service_catalog"));
            assertThrows(SQLException.class, () -> statement.executeUpdate("INSERT INTO service_catalog(service_name,display_name) VALUES('forbidden','forbidden')"));
            assertThrows(SQLException.class, () -> statement.execute("CREATE TABLE analytics_write_probe(id BIGINT)"));
            assertThrows(SQLException.class, () -> statement.executeQuery("SELECT username FROM app_user"));
            assertThrows(SQLException.class, () -> statement.executeQuery("SELECT question FROM sql_query_audit"));
        }
    }

    private long version(long document, String version) {
        long id = insert("INSERT INTO document_version(document_id,version_no,original_name,file_type,file_size,checksum,storage_path,process_status,chunk_count) VALUES(?,?,?,?,?,?,?,?,?)", document, version, version + ".md", "MD", 1, version + "-checksum", version + ".md", "READY", 1);
        db.update("INSERT INTO document_chunk_ref(version_id,vector_id,chunk_index,content) VALUES(?,?,0,?)", id, "mysql-vector-" + id, "MySQL verification evidence");
        return id;
    }

    private long insert(String sql, Object... args) {
        var key = new GeneratedKeyHolder();
        db.update(connection -> { var statement = connection.prepareStatement(sql, new String[]{"id"}); for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]); return statement; }, key);
        return key.getKey().longValue();
    }

    private String value(String sql, long id) { return db.queryForObject(sql, String.class, id); }

    private void grantAnalyticsSelect() throws SQLException {
        try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", ANALYTICS_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("GRANT SELECT ON opsnexus.service_catalog TO 'opsnexus_analytics'@'%'");
            statement.execute("GRANT SELECT ON opsnexus.release_record TO 'opsnexus_analytics'@'%'");
            statement.execute("GRANT SELECT ON opsnexus.incident_record TO 'opsnexus_analytics'@'%'");
        }
    }
}
