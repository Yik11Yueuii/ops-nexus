package com.opsnexus.analytics;

import com.opsnexus.assistant.DeepSeekChat;
import com.opsnexus.governance.AiGovernanceService;
import com.opsnexus.knowledge.KnowledgeException;
import com.opsnexus.resilience.AiProviderException;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;

@Service
public class AnalyticsService {
    private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
        "(?i)(password|api[_ -]?key|token|secret)(\\s*[=:]\\s*)(['\\\"]?)[^\\s,;'\\\"]+"
    );
    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)bearer\\s+[a-z0-9._-]+");

    private final String schema;
    private final JdbcTemplate audit;
    private final DeepSeekChat ai;
    private final SqlSafetyValidator validator;
    private final AiGovernanceService governance;
    private final String url;
    private final String user;
    private final String password;

    public AnalyticsService(
        JdbcTemplate audit,
        DeepSeekChat ai,
        SqlSafetyValidator validator,
        AiGovernanceService governance,
        @Value("${ops.analytics-url}") String url,
        @Value("${ops.analytics-username}") String user,
        @Value("${ops.analytics-password}") String password,
        @Value("${ops.analytics-dialect:H2}") String dialect
    ) {
        this.audit = audit;
        this.ai = ai;
        this.validator = validator;
        this.governance = governance;
        this.url = url;
        this.user = user;
        this.password = password;
        this.schema = "You generate exactly one " + dialect + " SELECT statement and no explanation. "
            + "Allowed tables/columns: service_catalog(id,service_name,display_name,owner_name,current_version,runtime_status); "
            + "release_record(id,service_name,version_no,environment,status,released_at,summary); "
            + "incident_record(id,service_name,symptom,root_cause,resolution,status,occurred_at,resolved_at). "
            + "Allowed functions: COUNT,SUM,AVG,MIN,MAX,DATEADD,DATE_ADD. "
            + "No SELECT *, CTE, UNION, subquery, system table, duplicate table, or more than two JOINs. "
            + "A JOIN must use explicit aliases and only service_catalog.service_name = release_record.service_name "
            + "or service_catalog.service_name = incident_record.service_name. "
            + "Use aliases only from selected expressions. LIMIT is at most 100.";
    }

    public Map<String, Object> query(long userId, String question) {
        if (question == null || question.isBlank() || question.length() > 300) {
            throw new KnowledgeException(400, "INVALID_INPUT", "分析问题不能为空且不能超过 300 字");
        }
        String requestId = UUID.randomUUID().toString();
        String sql = null;
        long auditId = createAudit(requestId, userId, question);
        long started = System.nanoTime();
        try {
            var permit = governance.enter(userId, "TEXT_TO_SQL", ai.modelName(), question.length());
            try {
                sql = ai.complete(schema, question);
                permit.output(sql.length());
            } catch (Exception exception) {
                permit.fail("SQL_GENERATION_FAILED");
                throw exception;
            } finally {
                permit.close();
            }
            String safeSql = validator.validate(sql);
            audit.update("UPDATE sql_query_audit SET generated_sql=?,validation_status='PASSED' WHERE id=?", safeSql, auditId);
            Result result = execute(safeSql);
            long latency = (System.nanoTime() - started) / 1_000_000;
            audit.update("UPDATE sql_query_audit SET execution_status='SUCCESS',latency_ms=?,row_count=? WHERE id=?", latency, result.rows.size(), auditId);
            return Map.of(
                "auditId", auditId,
                "generatedSql", safeSql,
                "columns", result.columns,
                "rows", result.rows,
                "rowCount", result.rows.size(),
                "summary", summarize(result.rows)
            );
        } catch (Exception exception) {
            String reason = exception instanceof KnowledgeException ? exception.getMessage() : "查询生成或执行失败";
            audit.update(
                "UPDATE sql_query_audit SET generated_sql=?,validation_status=CASE WHEN validation_status='PENDING' THEN 'REJECTED' ELSE validation_status END,execution_status='FAILED',latency_ms=?,failure_reason=? WHERE id=?",
                redact(sql), (System.nanoTime() - started) / 1_000_000, redact(reason), auditId
            );
            if (exception instanceof KnowledgeException knowledgeException) {
                throw knowledgeException;
            }
            if (exception instanceof AiProviderException providerException) {
                throw new KnowledgeException(503, providerException.errorCode(), providerException.getMessage());
            }
            throw new KnowledgeException(503, "ANALYTICS_FAILED", reason);
        }
    }

    public List<Map<String, Object>> audits() {
        return audit.queryForList("SELECT id,request_id,user_id,question,generated_sql,validation_status,execution_status,latency_ms,row_count,failure_reason,created_at FROM sql_query_audit ORDER BY id DESC LIMIT 30");
    }

    private long createAudit(String requestId, long userId, String question) {
        try {
            var keyHolder = new GeneratedKeyHolder();
            audit.update(connection -> {
                var statement = connection.prepareStatement(
                    "INSERT INTO sql_query_audit(request_id,user_id,question,validation_status,execution_status) VALUES(?,?,?,'PENDING','NOT_STARTED')",
                    new String[]{"ID"}
                );
                statement.setString(1, requestId);
                statement.setLong(2, userId);
                statement.setString(3, redact(question));
                return statement;
            }, keyHolder);
            return Objects.requireNonNull(keyHolder.getKey()).longValue();
        } catch (Exception exception) {
            throw new KnowledgeException(503, "AUDIT_UNAVAILABLE", "审计不可用，已拒绝执行查询");
        }
    }

    private Result execute(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(readOnlyUrl(), user, password)) {
            connection.setReadOnly(true);
            try (Statement statement = connection.createStatement()) {
                statement.setMaxRows(100);
                statement.setQueryTimeout(3);
                try (ResultSet resultSet = statement.executeQuery(sql)) {
                    var metadata = resultSet.getMetaData();
                    var columns = new ArrayList<String>();
                    for (int i = 1; i <= metadata.getColumnCount(); i++) {
                        columns.add(metadata.getColumnLabel(i));
                    }
                    var rows = new ArrayList<Map<String, Object>>();
                    while (resultSet.next()) {
                        var row = new LinkedHashMap<String, Object>();
                        for (int i = 1; i <= metadata.getColumnCount(); i++) {
                            row.put(columns.get(i - 1), resultSet.getObject(i));
                        }
                        rows.add(row);
                    }
                    return new Result(columns, rows);
                }
            }
        }
    }

    private String readOnlyUrl() {
        return url.startsWith("jdbc:h2:")
            ? url.replaceAll(";DB_CLOSE_DELAY=[^;]*", "").replaceAll(";DB_CLOSE_ON_EXIT=[^;]*", "")
            : url;
    }

    private String summarize(List<Map<String, Object>> rows) {
        return rows.isEmpty() ? "查询成功，但没有符合条件的记录。" : "安全查询成功，共返回 " + rows.size() + " 行；结论仅基于页面展示的数据。";
    }

    /** Keeps credentials and bearer values out of business audit storage, including rejected model output. */
    private String redact(String value) {
        if (value == null) {
            return null;
        }
        String masked = SENSITIVE_ASSIGNMENT.matcher(value).replaceAll("$1$2$3***");
        return BEARER_TOKEN.matcher(masked).replaceAll("Bearer ***");
    }

    private record Result(List<String> columns, List<Map<String, Object>> rows) { }
}
