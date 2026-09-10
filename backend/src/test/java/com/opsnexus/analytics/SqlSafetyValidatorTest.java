package com.opsnexus.analytics;

import com.opsnexus.knowledge.KnowledgeException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SqlSafetyValidatorTest {
    private final SqlSafetyValidator validator = new SqlSafetyValidator();

    @Test
    void acceptsWhitelistedAliasesAndOnlyApprovedJoinRelationship() {
        String sql = validator.validate("""
            SELECT sc.service_name, COUNT(i.id) AS incident_count
            FROM service_catalog sc
            JOIN incident_record i ON sc.service_name = i.service_name
            GROUP BY sc.service_name
            ORDER BY incident_count DESC
            LIMIT 7
            """);

        assertTrue(sql.contains("LIMIT 7"));
        assertTrue(sql.contains("incident_count"));
    }

    @Test
    void rejectsCrossTableColumnsUnknownAliasesAndUnsafeJoinsWithStableCodes() {
        assertRejected("SELECT service_catalog.version_no FROM service_catalog", "SQL_COLUMN_NOT_ALLOWED");
        assertRejected("SELECT x.service_name FROM service_catalog sc", "SQL_COLUMN_NOT_ALLOWED");
        assertRejected("SELECT sc.service_name FROM service_catalog sc JOIN incident_record i ON sc.id = i.id", "SQL_JOIN_NOT_ALLOWED");
        assertRejected("SELECT sc.service_name FROM service_catalog sc JOIN incident_record i ON sc.service_name = sc.service_name", "SQL_JOIN_NOT_ALLOWED");
        assertRejected("SELECT sc.service_name FROM service_catalog sc CROSS JOIN incident_record i", "SQL_JOIN_NOT_ALLOWED");
    }

    @Test
    void rejectsNestedQueriesAndCteBeforeTheyCanReachTheReadOnlyConnection() {
        assertRejected("SELECT service_name FROM service_catalog WHERE id IN (SELECT id FROM incident_record)", "SQL_SUBQUERY_NOT_ALLOWED");
        assertRejected("WITH x AS (SELECT service_name FROM service_catalog) SELECT service_name FROM x", "SQL_CTE_NOT_ALLOWED");
    }

    @Test
    void preservesSmallerLimitAndTruncatesOnlyOversizedLimit() {
        assertTrue(validator.validate("SELECT service_name FROM service_catalog LIMIT 3").contains("LIMIT 3"));
        assertTrue(validator.validate("SELECT service_name FROM service_catalog LIMIT 1000").contains("LIMIT 100"));
        assertRejected("SELECT service_name FROM service_catalog LIMIT 5 OFFSET 1", "SQL_LIMIT_NOT_ALLOWED");
    }

    @Test
    void rejectsComplexAndUnsupportedStatementsWithStableCodes() {
        assertRejected("SELECT * FROM service_catalog", "SQL_WILDCARD_NOT_ALLOWED");
        assertRejected("SELECT service_name FROM service_catalog; DELETE FROM service_catalog", "SQL_MULTIPLE_STATEMENTS");
        assertRejected("DELETE FROM service_catalog", "SQL_STATEMENT_NOT_ALLOWED");
    }

    private void assertRejected(String sql, String code) {
        KnowledgeException exception = assertThrows(KnowledgeException.class, () -> validator.validate(sql));
        assertEquals(code, exception.code);
    }
}
